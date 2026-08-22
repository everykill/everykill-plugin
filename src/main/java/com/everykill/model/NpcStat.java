/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

import com.google.gson.annotations.SerializedName;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

// all-time totals for one npc. grades stay as separate counts - collapse them into
// one number here and the site can never break them apart again.
public class NpcStat
{
	public int npcId;
	public String name;

	// two dagannoths, both called "Dagannoth", 40 and 30 kills, no way to tell which
	// is which. we've been capturing this on every kill and binning it.
	public int combatLevel;

	// was "exact" until the ceiling came down. alternate= keeps every ledger written
	// before that readable, so nobody loses a count over a rename.
	@SerializedName(value = "uncontested", alternate = {"exact"})
	public int uncontested;

	public int inferred;
	public int ambiguous;

	/** measured from the client, split by damage share. see XpAttributor */
	public long xp;

	// per-skill breakdown behind that total. keyed by skill name rather than the enum
	// so the stored json stays readable and gson doesn't need help. absent on rows
	// recorded before this existed - that's fine, it fills in on the next kill.
	public Map<String, Long> xpBySkill;

	public long firstKillMillis;
	public long lastKillMillis;

	// yyyy-mm-dd -> that day's tally, local time, because "today" means the player's
	// today. only exists for days you actually killed the thing, and pruned past
	// RETAINED_DAYS - enough for today/week/month and nothing beyond it.
	public Map<String, DayTally> days;

	public static final int RETAINED_DAYS = 35;

	public static final class DayTally
	{
		@SerializedName(value = "uncontested", alternate = {"exact"})
		public int uncontested;
		public int inferred;
		public int ambiguous;
		public long xp;

		public int total()
		{
			return uncontested + inferred + ambiguous;
		}
	}

	public NpcStat()
	{
	}

	public NpcStat(int npcId, String name)
	{
		this.npcId = npcId;
		this.name = name;
	}

	public void record(Confidence grade, long whenMillis)
	{
		switch (grade)
		{
			case UNCONTESTED:
				uncontested++;
				break;
			case INFERRED:
				inferred++;
				break;
			case AMBIGUOUS:
				ambiguous++;
				break;
		}

		final DayTally today = dayOf(whenMillis);
		switch (grade)
		{
			case UNCONTESTED:
				today.uncontested++;
				break;
			case INFERRED:
				today.inferred++;
				break;
			case AMBIGUOUS:
				today.ambiguous++;
				break;
		}

		if (firstKillMillis == 0L)
		{
			firstKillMillis = whenMillis;
		}
		lastKillMillis = whenMillis;
	}

	/** That day's tally, created on demand, with anything ancient pruned. */
	public DayTally dayOf(long whenMillis)
	{
		if (days == null)
		{
			days = new HashMap<>();
		}

		final LocalDate day = Instant.ofEpochMilli(whenMillis)
			.atZone(ZoneId.systemDefault()).toLocalDate();

		final String cutoff = day.minusDays(RETAINED_DAYS).toString();
		days.keySet().removeIf(k -> k.compareTo(cutoff) < 0);

		return days.computeIfAbsent(day.toString(), k -> new DayTally());
	}

	/**
	 * Kills per day for the last {@code window} days, oldest first, gaps as zeroes.
	 * Days you didn't fight it have no bucket, and a sparkline needs them anyway.
	 */
	public int[] dailyCounts(int window)
	{
		final int[] out = new int[window];
		if (days == null || days.isEmpty())
		{
			return out;
		}

		final LocalDate today = LocalDate.now(ZoneId.systemDefault());
		for (int i = 0; i < window; i++)
		{
			final DayTally t = days.get(today.minusDays(window - 1L - i).toString());
			out[i] = t == null ? 0 : t.total();
		}
		return out;
	}

	/** Most kills in a single day. A personal best we can work out without a server. */
	public int bestDay()
	{
		int best = 0;
		if (days != null)
		{
			for (DayTally t : days.values())
			{
				best = Math.max(best, t.total());
			}
		}
		return best;
	}

	/** Kills in the last {@code window} calendar days, today included. */
	public int totalSince(int window)
	{
		return sum(window, DayTally::total);
	}

	public long xpSince(int window)
	{
		long total = 0L;
		for (DayTally t : recent(window))
		{
			total += t.xp;
		}
		return total;
	}

	public int gradeSince(int window, Confidence grade)
	{
		switch (grade)
		{
			case UNCONTESTED:
				return sum(window, t -> t.uncontested);
			case INFERRED:
				return sum(window, t -> t.inferred);
			default:
				return sum(window, t -> t.ambiguous);
		}
	}

	private int sum(int window, ToIntFunction<DayTally> field)
	{
		int total = 0;
		for (DayTally t : recent(window))
		{
			total += field.applyAsInt(t);
		}
		return total;
	}

	// string dates compare lexicographically because yyyy-mm-dd is sortable. no date
	// parsing per row, and no timezone surprises beyond the one we already made.
	private List<DayTally> recent(int window)
	{
		if (days == null || days.isEmpty())
		{
			return Collections.emptyList();
		}

		final String from = LocalDate.now(ZoneId.systemDefault()).minusDays(window - 1L).toString();
		final List<DayTally> out = new ArrayList<>();
		for (Map.Entry<String, DayTally> e : days.entrySet())
		{
			if (e.getKey().compareTo(from) >= 0)
			{
				out.add(e.getValue());
			}
		}
		return out;
	}

	public int total()
	{
		return uncontested + inferred + ambiguous;
	}
}
