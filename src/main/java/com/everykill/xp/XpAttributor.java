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
	 * How far {@link #allocateAt} may search for the damage that earned an XP drop.
	 * No longer provisional — see the measurement below.
	 */
	// measured 2026-08-24 over two venues, 392 allocations. goblins: +1 x108, +2 x1.
	// dagannoths: +1 x272, 0 x8, -1 x3. xp USUALLY turns up a tick before the
	// hitsplat that earned it, but not always, and the backward arm does fire.
	//
	// the first session was single-target goblins and showed zero at offset 0, which
	// made a case for searching forward before own-tick. a second venue killed that
	// idea. don't reorder this on one venue's data.
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

	// npcId -> skill -> xp. per skill because "8.1k from dagannoths" is fine, but
	// "8.1k attack, 2.7k hitpoints" is the bit nobody else tracks. we already knew the
	// skill at allocation time and were throwing it away.
	private final Map<Integer, Map<CombatSkill, Long>> xpByNpc = new HashMap<>();

	// xp that arrived while we weren't fighting anything. teleports, alching,
	// superheat - all magic xp with no monster attached, all completely normal.
	// verified 2026-08-21: two write-offs of exactly 35 were both varrock teleport.
	private long unallocatedXp;

	// xp that arrived WHILE we had damage on record and still couldn't be placed.
	// this one is a real problem. don't let the two share a counter, or every alch
	// buries the signal that actually matters.
	private long strandedXp;

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
		strandedXp = 0L;
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

			if (allocateAt(p.skill, p.xp, p.tick))
			{
				it.remove();
			}
			else if (now - p.tick > SETTLE_TICKS)
			{
				if (p.duringCombat)
				{
					log.debug("XP stranded mid-fight: xp={} arrivedAt={} now={} pools={}",
						p.xp, p.tick, now, damageByTick.keySet());
					strandedXp += p.xp;
				}
				else
				{
					unallocatedXp += p.xp;
				}
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

		allocate(skill, delta, tick);
		return delta;
	}

	private void allocate(CombatSkill skill, long xp, int tick)
	{
		if (!allocateAt(skill, xp, tick))
		{
			// remember whether we were mid-fight when this turned up. by the time it
			// gets written off the pools may have been trimmed, and "was there damage
			// when it arrived" is the question that matters.
			pending.add(new Pending(skill, xp, tick, !damageByTick.isEmpty()));
		}
	}

	// own tick, then forward, then back. order matters: xp lands early so the damage
	// ahead of it is its own and the damage behind belongs to the last drop. flip it,
	// or crank SETTLE_TICKS till something sticks, and you pay the wrong monster and
	// never hear about it. don't.
	private boolean allocateAt(CombatSkill skill, long xp, int tick)
	{
		for (int t = tick; t <= tick + SETTLE_TICKS; t++)
		{
			if (split(skill, xp, t, tick))
			{
				return true;
			}
		}

		for (int t = tick - 1; t >= tick - SETTLE_TICKS; t--)
		{
			if (split(skill, xp, t, tick))
			{
				return true;
			}
		}

		return false;
	}

	private boolean split(CombatSkill skill, long xp, int poolTick, int xpTick)
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
			skillsFor(e.getKey()).merge(skill, share, Long::sum);
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
			skillsFor(biggestShareNpc).merge(skill, remainder, Long::sum);
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

	private Map<CombatSkill, Long> skillsFor(int npcId)
	{
		return xpByNpc.computeIfAbsent(npcId, k -> new EnumMap<>(CombatSkill.class));
	}

	private static final class Pending
	{
		private final CombatSkill skill;
		private final long xp;
		private final int tick;
		private final boolean duringCombat;

		private Pending(CombatSkill skill, long xp, int tick, boolean duringCombat)
		{
			this.skill = skill;
			this.xp = xp;
			this.tick = tick;
			this.duringCombat = duringCombat;
		}
	}

	// ------------------------------------------------------------------

	/** Total across every skill, for one monster. */
	public long xpFor(int npcId)
	{
		long total = 0L;
		for (long v : xpByNpc.getOrDefault(npcId, java.util.Collections.emptyMap()).values())
		{
			total += v;
		}
		return total;
	}

	/** Per-skill breakdown for one monster, empty if we've attributed none. */
	public Map<CombatSkill, Long> xpBySkillFor(int npcId)
	{
		return xpByNpc.getOrDefault(npcId, java.util.Collections.emptyMap());
	}

	/** Everything we couldn't place, combat or not. Diagnostics only. */
	public long getUnallocatedXp()
	{
		return unallocatedXp + strandedXp;
	}

	/** Only the xp that went missing mid-fight. This is the number worth showing. */
	public long getStrandedXp()
	{
		return strandedXp;
	}

	/** Hand over what has accumulated and clear. The ledger holds lifetime totals. */
	public Map<Integer, Map<CombatSkill, Long>> drain()
	{
		final Map<Integer, Map<CombatSkill, Long>> out = new HashMap<>(xpByNpc);
		xpByNpc.clear();
		return out;
	}
}
