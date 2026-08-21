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
 * Per-monster experience: <b>measured from the client, allocated by damage share.</b>
 *
 * The attribution-maths doc proposed the reverse — deriving XP from damage and
 * demoting the client's updates to a checksum. XP is indeed paid per damage point,
 * but derivation cannot be the source of truth (verified 2026-08-16, wiki):
 *
 * <ol>
 *   <li><b>Overkill grants no XP.</b> It is paid on damage applied, capped at
 *       remaining HP; hitsplats report damage rolled. Every killing blow overstates,
 *       in one direction.</li>
 *   <li><b>The per-monster bonus cannot be computed.</b> 0.025x–2.875x, and manual
 *       overrides ignore the published formula — Vorkath computes +20% against a
 *       listed +0%. It needs the P0 reference table, which does not exist yet.</li>
 *   <li><b>Rounding is undocumented.</b> XP is stored in tenths; 1.33 per damage
 *       cannot be represented in tenths.</li>
 * </ol>
 *
 * So the client's updates are the measurement — already correct for overkill,
 * bonuses and rounding — and damage only answers which monster it came from.
 *
 * <p>A derived figure is still worth computing as a checksum that can flag a bad
 * allocation, but it needs the player's attack style, which nothing reads yet. The
 * rates it would use are recorded in {@code docs/GAME-MECHANICS.md}; build it when
 * Step 5 needs it rather than carrying an unused copy here.
 *
 * <p>XP arriving with no damage on record is never forced onto the nearest monster;
 * it accumulates in {@link #getUnallocatedXp()} and is surfaced on the panel.
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

	/**
	 * Record damage we dealt. Zero is not damage: RuneLite reports a block as ours
	 * with amount zero, and admitting one creates a zero-total tick that swallows the
	 * experience owed to the tick before it.
	 */
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
			// Diagnostic, P1. Unallocated XP was climbing with nothing landing on any
			// monster, and the cause was not obvious from reading. Print the whole
			// decision so the reason is observed rather than theorised.
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
