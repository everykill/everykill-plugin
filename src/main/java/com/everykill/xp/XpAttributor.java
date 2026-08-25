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
	/**
	 * Our damage on an NPC. Accumulates into this tick's pool and nothing else —
	 * allocation happens on the tick boundary, in {@link #settle(int)}.
	 */
	public void damage(int npcId, int amount, int tick)
	{
		if (amount <= 0)
		{
			return;
		}

		// this used to call settle() right here, and that made a multi-monster pool
		// impossible. xp lands a tick before its hitsplat, so it's always already
		// waiting - the FIRST hitsplat of the next tick would allocate the whole drop
		// against a pool holding only itself, and everything else hit on that tick got
		// nothing. 392 measured allocations over two venues, every one single-monster,
		// cannon or not. FINDINGS 2026-08-24.
		//
		// core does not do this anywhere. SpecialCounterPlugin accumulates hitsplats
		// and decides in onGameTick, LootManager collects item spawns and clears them
		// on the tick, XpTrackerPlugin the same. their reason is ours: "the weapon
		// hitsplat is always last, after other hitsplats which occur on the same tick".
		// you cannot judge a tick from its first event.
		damageByTick.computeIfAbsent(tick, t -> new LinkedHashMap<>()).merge(npcId, amount, Integer::sum);
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

		// queue it, don't place it. xp arrives a tick before the hitsplat that earned
		// it, so at this moment the damage pool it belongs to usually does not exist
		// yet - and placing it against whatever pool IS open pays the wrong monster.
		// settle() on the tick boundary does the placing.
		pending.add(new Pending(skill, delta, tick, !damageByTick.isEmpty()));
		return delta;
	}

	// own tick, then forward, then back. order matters: xp lands early so the damage
	// ahead of it is its own and the damage behind belongs to the last drop. flip it,
	// or crank SETTLE_TICKS till something sticks, and you pay the wrong monster and
	// never hear about it. don't.
	private boolean allocateAt(CombatSkill skill, long xp, int tick)
	{
		// Prefer the pool whose damage ACTUALLY EXPLAINS this xp before falling back to
		// the nearest one with anything in it.
		//
		// The xp amount is a measurement of the damage, not just a number attached to
		// it: 4 xp per point of damage for melee and ranged, 2 for magic, 1.33 for
		// hitpoints (GAME-MECHANICS.md, sourced from the Combat and Hitpoints pages).
		// So a 76 xp ranged drop was earned by 19 damage, and a pool holding 1 damage
		// cannot have produced it whatever tick it sits on.
		//
		// This is what the snakeling case needed. A recoil ping puts 1 damage in tick
		// 100's pool; our 19 on Zulrah lands in 101 because "NPCs are processed earlier
		// than players each tick, so this effect will make all hits on NPCs delayed by
		// an additional one tick" (Hit delay, Processing order delay). Searching by
		// nearness finds the snakeling first and hands it 76 xp for one point of chip
		// damage. Searching by fit skips it, because 1 damage explains 4 xp, not 76.
		final int expected = damageFor(skill, xp);
		if (expected > 0)
		{
			for (int t = tick - SETTLE_TICKS; t <= tick + SETTLE_TICKS; t++)
			{
				final Map<Integer, Integer> pool = damageByTick.get(t);
				if (pool != null && sum(pool) == expected && split(skill, xp, t, tick))
				{
					return true;
				}
			}
		}

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

	/**
	 * The damage that would produce exactly this much xp, or 0 when we cannot say.
	 *
	 * <p>Rates from {@code GAME-MECHANICS.md}. Hitpoints is deliberately excluded: at
	 * 1.33 xp per damage the rate is stored in tenths and rounded, so an exact integer
	 * match is not reliable and a near-match would be a guess. Defence is excluded for
	 * the same reason — Controlled pays 1.33 to three skills, so a Defence drop does
	 * not have one rate.
	 */
	private static int damageFor(CombatSkill skill, long xp)
	{
		final int rate;
		switch (skill)
		{
			case ATTACK:
			case STRENGTH:
			case RANGED:
				rate = 4;
				break;
			case MAGIC:
				rate = 2;
				break;
			default:
				return 0;
		}

		// only an exact multiple is evidence. anything else means this skill was paid
		// at a rate we did not model, and guessing is worse than falling through.
		return xp % rate == 0 ? (int) (xp / rate) : 0;
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
