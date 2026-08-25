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
import java.util.HashSet;
import java.util.Set;
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

	// damage share, summed across every kill on this row. the grades tell you HOW we
	// knew about a kill; these tell you how much of it was actually ours, and those
	// are different questions. AMBIGUOUS covers both "a mate and i split it" and "i
	// hit a 20-man boss once" - same label, nothing alike, and only damage separates
	// them.
	//
	// the game itself decides ownership by damage, and not with one rule:
	//   ordinary monsters and world bosses - most damage sees the drop
	//   team bosses (nex)                  - a minimum threshold, then shares
	//   instanced (vorkath, zulrah)        - nobody else is there
	// see docs/reference-boss-encounter-classes.md. we store the raw numbers and let
	// the consumer apply whichever rule fits the encounter. picking one here would
	// bake in the wrong one for half the boss list.
	//
	// absent on rows written before this existed, same as xpBySkill - fills in on the
	// next kill. a zero here means "never recorded", not "dealt no damage", so don't
	// read a share off a row whose killsWithDamage is 0.
	public long myDamageTotal;
	public long othersDamageTotal;

	/** kills that contributed to the damage totals above. the denominator. */
	public int killsWithDamage;

	// what this monster has dropped, keyed by item id. a tally, not a log - storing
	// every drop event grows without bound on something you kill thousands of times,
	// and the panel wants "how many bones have i had", not when each one landed.
	// null until the first drop, so lootless monsters cost nothing in the saved json.
	public Map<String, DropTally> drops;

	// yyyy-mm-dd -> that day's tally, local time, because "today" means the player's
	// today. only exists for days you actually killed the thing, and pruned past
	// RETAINED_DAYS - enough for today/week/month and nothing beyond it.
	public Map<String, DayTally> days;

	public static final int RETAINED_DAYS = 35;

	/**
	 * Files a kill's drops against this monster.
	 *
	 * <p><b>Call this after {@code record}</b>, so {@code total()} already includes the
	 * kill that produced the loot. A drop landing on kill 500 should read as "0 kills
	 * dry", not 1.
	 *
	 * <p>Several of the same item in one kill count as <b>one</b> drop with a summed
	 * quantity — a monster dropping bones twice in one death is still one roll, and
	 * counting it twice would inflate the numerator of every rate built on this.
	 */
	public void recordDrops(List<Drop> kill, long whenMillis)
	{
		if (kill == null || kill.isEmpty())
		{
			return;
		}

		if (drops == null)
		{
			drops = new HashMap<>();
		}

		final Set<String> seenThisKill = new HashSet<>();

		for (Drop drop : kill)
		{
			final String key = String.valueOf(drop.itemId);
			final DropTally tally = drops.computeIfAbsent(key, k -> new DropTally());

			if (drop.name != null)
			{
				tally.name = drop.name;
			}
			tally.quantity += drop.quantity;
			tally.killCountAtLast = total();
			tally.lastMillis = whenMillis;

			if (seenThisKill.add(key))
			{
				tally.drops++;
			}
		}
	}

	/**
	 * Kills since this item last dropped, or -1 if it never has.
	 *
	 * <p>The honest half of a dry streak. Whether that number is <i>unlucky</i> needs
	 * the item's published rate, which {@code spec-reference-data.md} keeps server-side
	 * — so the client can say "312 kills since" and must not say "you are 2.4x dry".
	 */
	public int killsSince(int itemId)
	{
		if (drops == null)
		{
			return -1;
		}

		final DropTally tally = drops.get(String.valueOf(itemId));
		return tally == null ? -1 : total() - tally.killCountAtLast;
	}

	/**
	 * One item's history against this monster.
	 *
	 * <p>{@code killCountAtLast} is what makes a dry streak possible: current kill count
	 * minus that number is how many kills since it last dropped. Storing the count at
	 * the time rather than a running "dry" counter means the answer stays right no
	 * matter what happens in between, and survives the file being reloaded.
	 */
	public static final class DropTally
	{
		/**
		 * The item's name, resolved when the drop was recorded.
		 *
		 * <p>Stored rather than looked up at paint time because {@code ItemManager}
		 * reads through to the client, and the panel paints on the Swing thread. Core
		 * resolves names in the plugin for the same reason. It also means the ledger
		 * stays readable on its own.
		 */
		public String name;

		/** how many, summed. 99 coins once and 1 coin 99 times both read 99. */
		public long quantity;

		/** how many separate kills produced it. this is the drop-rate numerator. */
		public int drops;

		/** total kills on this monster when it last dropped. */
		public int killCountAtLast;

		/** when it last dropped. */
		public long lastMillis;
	}

	public static final class DayTally
	{
		@SerializedName(value = "uncontested", alternate = {"exact"})
		public int uncontested;
		public int inferred;
		public int ambiguous;
		public long xp;

		// same fields as the row above, scoped to this day. without them a week or
		// month window can count kills but can't say how much of them was ours.
		public long myDamage;
		public long othersDamage;
		public int killsWithDamage;

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
		record(grade, whenMillis, 0, 0);
	}

	public void record(Confidence grade, long whenMillis, int myDamage, int othersDamage)
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

		// a kill we somehow have no damage for isn't evidence of a share, so it stays
		// out of the denominator entirely rather than dragging the average down.
		if (myDamage > 0 || othersDamage > 0)
		{
			myDamageTotal += myDamage;
			othersDamageTotal += othersDamage;
			killsWithDamage++;

			today.myDamage += myDamage;
			today.othersDamage += othersDamage;
			today.killsWithDamage++;
		}
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
