/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.xp;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

	/** npcId to damage, for the tick currently being accumulated. */
	private final Map<Integer, Integer> currentTickDamage = new LinkedHashMap<>();
	private int currentTick = Integer.MIN_VALUE;

	/** The previous tick's damage, kept so a late XP drop can still be placed. */
	private final Map<Integer, Integer> previousTickDamage = new LinkedHashMap<>();
	private int previousTick = Integer.MIN_VALUE;

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
		currentTickDamage.clear();
		previousTickDamage.clear();
		currentTick = Integer.MIN_VALUE;
		previousTick = Integer.MIN_VALUE;
		xpByNpc.clear();
		unallocatedXp = 0L;
		primed = false;
	}

	// ------------------------------------------------------------------

	// zero is not damage. blocks come through as ours with amount 0, and one of those
	// makes a zero-total tick that eats the xp owed to the tick before. leave the guard.
	public void damage(int npcId, int amount, int tick)
	{
		rollTo(tick);
		if (amount > 0)
		{
			currentTickDamage.merge(npcId, amount, Integer::sum);
		}
	}

	private void rollTo(int tick)
	{
		if (tick == currentTick)
		{
			return;
		}

		previousTickDamage.clear();
		previousTickDamage.putAll(currentTickDamage);
		previousTick = currentTick;

		currentTickDamage.clear();
		currentTick = tick;
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
		// This tick first, then the one before. A pool must be recent and carry real
		// damage; an empty or zero-total one falls through rather than absorbing.
		Map<Integer, Integer> pool = null;
		int totalDamage = 0;

		if (tick - currentTick <= SETTLE_TICKS)
		{
			final int sum = sum(currentTickDamage);
			if (sum > 0)
			{
				pool = currentTickDamage;
				totalDamage = sum;
			}
		}

		if (pool == null && tick - previousTick <= SETTLE_TICKS)
		{
			final int sum = sum(previousTickDamage);
			if (sum > 0)
			{
				pool = previousTickDamage;
				totalDamage = sum;
			}
		}

		if (pool == null)
		{
			// temp: unallocated kept climbing and nothing landed anywhere. dump the
			// whole decision, reading the code got us nowhere.
			log.debug("XP unallocated: xp={} tick={} currentTick={} currentPool={}({}) previousTick={} previousPool={}({}) settle={}",
				xp, tick, currentTick, currentTickDamage.size(), sum(currentTickDamage),
				previousTick, previousTickDamage.size(), sum(previousTickDamage), SETTLE_TICKS);

			unallocatedXp += xp;
			return;
		}

		// Largest remainder, so the parts sum exactly to the whole. A naive round
		// would leak or invent experience on every split.
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
