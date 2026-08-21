/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.xp;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-monster xp: measured from the client, allocated by damage share.
 *
 * <p>Don't "fix" this by deriving xp from damage. Overkill pays nothing but hitsplats
 * report the full roll, the per-monster bonus has hand-edited overrides (Vorkath
 * computes +20% against a listed +0%), and the tenths rounding is documented nowhere.
 * The client already did the maths. Sources in docs/GAME-MECHANICS.md.
 *
 * <p>Unmatched xp is never dumped on the nearest monster - it piles up in
 * {@link #getUnallocatedXp()} and the panel shows it. Bad allocation should look bad.
 */
@Slf4j
public class XpAttributor
{
	/**
	 * How many ticks back a damage record may still claim an XP drop. Experience
	 * normally lands on the same tick as the hitsplat or the one after.
	 * Provisional — the "XP settle window" is an open measurement in P1.
	 */
	static final int SETTLE_TICKS = 2;

	private final Map<CombatSkill, Integer> lastKnownXp = new EnumMap<>(CombatSkill.class);

	// tick -> npcId -> damage. keeps a few ticks because the xp shows up before the
	// hitsplat does. yes, before. so we sit on it waiting for damage that hasn't
	// happened yet, which is a stupid way to live but it's what the client does.
	private final Map<Integer, Map<Integer, Integer>> damageByTick = new LinkedHashMap<>();

	// xp with no damage to explain it yet. settled when the hitsplat turns up, written
	// off if it never does.
	private final List<Pending> pending = new ArrayList<>();

	// pool tick -> the xp arrival tick that claimed it, so a pool can't pay twice
	private final Map<Integer, Integer> poolClaimedBy = new HashMap<>();

	/** npcId to total attributed XP. */
	private final Map<Integer, Long> xpByNpc = new HashMap<>();

	private long unallocatedXp;
	private boolean primed;

	// ------------------------------------------------------------------

	/**
	 * Seed a skill's total without treating it as a gain. Without this the session's
	 * first update reads as a delta from zero and credits one monster with a lifetime.
	 */
	public void prime(CombatSkill skill, int totalXp)
	{
		lastKnownXp.put(skill, totalXp);
		primed = true;
	}

	public boolean isPrimed()
	{
		return primed;
	}

	public void reset()
	{
		lastKnownXp.clear();
		damageByTick.clear();
		poolClaimedBy.clear();
		pending.clear();
		xpByNpc.clear();
		unallocatedXp = 0L;
		primed = false;
	}

	// ------------------------------------------------------------------

	// zero is not damage. blocks come through as ours with amount 0, and an empty pool
	// would soak up xp that belongs to a real hit. leave the guard.
	public void damage(int npcId, int amount, int tick)
	{
		if (amount <= 0)
		{
			return;
		}

		damageByTick.computeIfAbsent(tick, t -> new LinkedHashMap<>()).merge(npcId, amount, Integer::sum);
		settle(tick);
	}

	/**
	 * Match parked xp against damage, and write off anything too old to explain.
	 * Call every tick.
	 */
	public void settle(int now)
	{
		for (Iterator<Pending> it = pending.iterator(); it.hasNext(); )
		{
			final Pending p = it.next();

			if (allocateAt(p.xp, p.tick))
			{
				it.remove();
			}
			else if (now - p.tick > SETTLE_TICKS)
			{
				log.debug("XP written off: xp={} arrivedAt={} now={} pools={}", p.xp, p.tick, now, damageByTick.keySet());
				unallocatedXp += p.xp;
				it.remove();
			}
		}

		damageByTick.keySet().removeIf(t -> now - t > SETTLE_TICKS + 1);
		poolClaimedBy.keySet().removeIf(t -> now - t > SETTLE_TICKS + 1);
	}

	/**
	 * A skill's experience total changed.
	 *
	 * @param totalXp the skill's new lifetime total, as reported by the client
	 * @return the XP delta that was attributed, or 0 if there was nothing to do
	 */
	public long xpChanged(CombatSkill skill, int totalXp, int tick)
	{
		final Integer previous = lastKnownXp.put(skill, totalXp);

		if (previous == null)
		{
			// Not primed: record the baseline, attribute nothing.
			return 0L;
		}

		final long delta = (long) totalXp - previous;
		if (delta <= 0L)
		{
			return 0L;
		}

		allocate(delta, tick);
		return delta;
	}

	private void allocate(long xp, int tick)
	{
		if (!allocateAt(xp, tick))
		{
			pending.add(new Pending(xp, tick));
		}
	}

	// own tick, then forward, then back. order matters: xp lands early so the damage
	// ahead of it is its own and the damage behind belongs to the last drop. flip it,
	// or crank SETTLE_TICKS till something sticks, and you pay the wrong monster and
	// never hear about it. don't.
	private boolean allocateAt(long xp, int tick)
	{
		for (int t = tick; t <= tick + SETTLE_TICKS; t++)
		{
			if (split(xp, t, tick))
			{
				return true;
			}
		}

		for (int t = tick - 1; t >= tick - SETTLE_TICKS; t--)
		{
			if (split(xp, t, tick))
			{
				return true;
			}
		}

		return false;
	}

	private boolean split(long xp, int poolTick, int xpTick)
	{
		final Map<Integer, Integer> pool = damageByTick.get(poolTick);
		if (pool == null)
		{
			return false;
		}

		// one pool, one xp arrival. every skill from a hit lands on the same tick and
		// shares it. a later drop grabbing it is nicking the last one's damage.
		final Integer claimedBy = poolClaimedBy.get(poolTick);
		if (claimedBy != null && claimedBy != xpTick)
		{
			return false;
		}

		final int totalDamage = sum(pool);
		if (totalDamage <= 0)
		{
			return false;
		}

		poolClaimedBy.put(poolTick, xpTick);

		// largest remainder, so the parts add up to the whole. rounding each share
		// independently leaks or invents xp on every split.
		long assigned = 0L;
		int biggestShareNpc = 0;
		int biggestShare = -1;

		for (Map.Entry<Integer, Integer> e : pool.entrySet())
		{
			final long share = xp * e.getValue() / totalDamage;
			xpByNpc.merge(e.getKey(), share, Long::sum);
			assigned += share;

			if (e.getValue() > biggestShare)
			{
				biggestShare = e.getValue();
				biggestShareNpc = e.getKey();
			}
		}

		final long remainder = xp - assigned;
		if (remainder > 0L)
		{
			xpByNpc.merge(biggestShareNpc, remainder, Long::sum);
		}

		return true;
	}

	private static int sum(Map<Integer, Integer> pool)
	{
		int total = 0;
		for (int d : pool.values())
		{
			total += d;
		}
		return total;
	}

	private static final class Pending
	{
		private final long xp;
		private final int tick;

		private Pending(long xp, int tick)
		{
			this.xp = xp;
			this.tick = tick;
		}
	}

	// ------------------------------------------------------------------

	public long xpFor(int npcId)
	{
		return xpByNpc.getOrDefault(npcId, 0L);
	}

	public long getUnallocatedXp()
	{
		return unallocatedXp;
	}

	/** Hand over what has accumulated and clear. The ledger holds lifetime totals. */
	public Map<Integer, Long> drain()
	{
		final Map<Integer, Long> out = new HashMap<>(xpByNpc);
		xpByNpc.clear();
		return out;
	}
}
