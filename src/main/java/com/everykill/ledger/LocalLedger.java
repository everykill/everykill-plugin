/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.ledger;

import com.everykill.EverykillConfig;
import com.everykill.model.Confidence;
import com.everykill.model.KillRecord;
import com.everykill.model.NpcStat;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * All-time per-NPC totals plus this session's counts.
 *
 * RS-profile scoped, so switching accounts on one machine gets its own ledger —
 * mixing two accounts into one bucket produces numbers that mean nothing. Grades are
 * stored separately and never collapsed; {@code total()} is derived on read.
 */
@Slf4j
@Singleton
public class LocalLedger
{
	private static final String LEDGER_KEY = "ledger";
	private static final Type LEDGER_TYPE = new TypeToken<HashMap<String, NpcStat>>()
	{
	}.getType();

	private final ConfigManager configManager;
	private final Gson gson;

	/** All-time, keyed by npc id as a string because Gson prefers string keys. */
	private Map<String, NpcStat> allTime = new HashMap<>();

	/** This session only, keyed by npc id, insertion-ordered for display. */
	@Getter
	private final Map<Integer, NpcStat> session = new LinkedHashMap<>();

	@Getter
	private int sessionKills;

	@Getter
	private long sessionStartMillis = System.currentTimeMillis();

	private final Map<Confidence, Integer> sessionGrades = new HashMap<>();

	/** Set by {@link #addXp}, cleared by {@link #save()}. See {@link #flush()}. */
	private boolean dirty;

	// xp for a monster we haven't killed yet. the fight's xp all arrives before the
	// kill that makes the row, so binning it meant every first kill read xp=0.
	private final Map<Integer, Long> xpBeforeFirstKill = new HashMap<>();

	@Inject
	public LocalLedger(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	// ------------------------------------------------------------------

	public void load()
	{
		try
		{
			final String raw = configManager.getRSProfileConfiguration(EverykillConfig.GROUP, LEDGER_KEY);
			if (raw == null || raw.isEmpty())
			{
				allTime = new HashMap<>();
				return;
			}

			final Map<String, NpcStat> parsed = gson.fromJson(raw, LEDGER_TYPE);
			allTime = parsed == null ? new HashMap<>() : parsed;
			log.debug("loaded ledger, {} npcs", allTime.size());
		}
		catch (Exception e)
		{
			// A corrupt ledger must not take the plugin down. Start empty and leave
			// the stored value untouched so it can be recovered by hand.
			log.warn("could not read stored ledger, starting empty", e);
			allTime = new HashMap<>();
		}
	}

	private void save()
	{
		try
		{
			configManager.setRSProfileConfiguration(EverykillConfig.GROUP, LEDGER_KEY, gson.toJson(allTime));
			dirty = false;
		}
		catch (Exception e)
		{
			log.warn("could not persist ledger", e);
		}
	}

	// kills save themselves, xp arrives every tick and can't, so it rides the next
	// kill's save. anything after the session's last kill had nothing to ride and was
	// getting binned. call on logout and shutdown.
	public void flush()
	{
		if (dirty)
		{
			save();
		}
	}

	public void startSession()
	{
		session.clear();
		sessionGrades.clear();
		sessionKills = 0;
		sessionStartMillis = System.currentTimeMillis();
	}

	// ------------------------------------------------------------------

	/** Record a kill; returns the NPC's all-time stat including it. */
	public NpcStat record(KillRecord kill)
	{
		final NpcStat stat = allTime.computeIfAbsent(String.valueOf(kill.npcId),
			k -> new NpcStat(kill.npcId, kill.npcName));

		// NPC names can change between game updates; keep the latest.
		stat.name = kill.npcName;
		stat.record(kill.grade, kill.timestampMillis);

		final NpcStat sessionStat = session.computeIfAbsent(kill.npcId,
			k -> new NpcStat(kill.npcId, kill.npcName));
		sessionStat.record(kill.grade, kill.timestampMillis);

		sessionKills++;
		sessionGrades.merge(kill.grade, 1, Integer::sum);

		// the fight's xp landed before this row existed. claim it now.
		final Long held = xpBeforeFirstKill.remove(kill.npcId);
		if (held != null)
		{
			stat.xp += held;
			sessionStat.xp += held;
		}

		save();
		return stat;
	}

	// ------------------------------------------------------------------

	// no row, no xp - a monster we never killed getting xp means the allocation is
	// wrong, and a zero-kill row would hide that. but the whole fight's xp arrives
	// BEFORE the kill that creates the row, so hold it rather than bin it.
	public void addXp(int npcId, long xp)
	{
		if (xp <= 0L)
		{
			return;
		}

		final NpcStat stat = allTime.get(String.valueOf(npcId));
		if (stat == null)
		{
			xpBeforeFirstKill.merge(npcId, xp, Long::sum);
			return;
		}

		stat.xp += xp;
		dirty = true;

		final NpcStat sessionStat = session.get(npcId);
		if (sessionStat != null)
		{
			sessionStat.xp += xp;
		}
	}

	public NpcStat get(int npcId)
	{
		return allTime.get(String.valueOf(npcId));
	}

	/** All-time stats, most-killed first. */
	public List<NpcStat> allTimeSorted()
	{
		final List<NpcStat> out = new ArrayList<>(allTime.values());
		out.sort(Comparator.comparingInt(NpcStat::total).reversed());
		return out;
	}

	public int sessionCount(Confidence grade)
	{
		return sessionGrades.getOrDefault(grade, 0);
	}

	/** The most-killed NPC this session. Drives the overlay's second line. */
	public NpcStat sessionFocus()
	{
		NpcStat best = null;
		for (NpcStat stat : session.values())
		{
			if (best == null || stat.total() > best.total())
			{
				best = stat;
			}
		}
		return best;
	}

	/** Experience measured this session, across every monster. */
	public long sessionXp()
	{
		long total = 0L;
		for (NpcStat stat : session.values())
		{
			total += stat.xp;
		}
		return total;
	}
}
