/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.ledger;

import com.everykill.EverykillConfig;
import com.everykill.model.Confidence;
import com.everykill.model.KillRecord;
import com.everykill.model.LootConfidence;
import com.everykill.model.NpcStat;
import com.everykill.xp.CombatSkill;
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

	// its own key rather than a field inside the ledger json - the ledger persists a
	// bare Map<String, NpcStat> and adding a sibling would change that shape for
	// every existing save.
	private static final String BEST_SESSION_KEY = "bestSessionKills";
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

	private int bestSessionKills;

	@Getter
	private long sessionStartMillis = System.currentTimeMillis();

	private final Map<Confidence, Integer> sessionGrades = new HashMap<>();

	/** Set by {@link #addXp}, cleared by {@link #save()}. See {@link #flush()}. */
	private boolean dirty;

	// xp for a monster we haven't killed yet. the fight's xp all arrives before the
	// kill that makes the row, so binning it meant every first kill read xp=0.
	private final Map<Integer, Map<String, Long>> xpBeforeFirstKill = new HashMap<>();

	// kills we read at load. saving fewer than this means we're about to eat history.
	private int loadedKills;

	// the stored ledger couldn't be parsed. don't write over the only copy.
	private boolean loadFailed;

	@Inject
	public LocalLedger(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	// ------------------------------------------------------------------

	public void load()
	{
		final Integer best = configManager.getRSProfileConfiguration(EverykillConfig.GROUP,
			BEST_SESSION_KEY, int.class);
		bestSessionKills = best == null ? 0 : best;

		loadFailed = false;

		try
		{
			final String raw = configManager.getRSProfileConfiguration(EverykillConfig.GROUP, LEDGER_KEY);
			if (raw == null || raw.isEmpty())
			{
				allTime = new HashMap<>();
				loadedKills = 0;
				return;
			}

			final Map<String, NpcStat> parsed = gson.fromJson(raw, LEDGER_TYPE);
			allTime = parsed == null ? new HashMap<>() : parsed;
			loadedKills = totalKills();
			log.debug("loaded ledger, {} npcs, {} kills", allTime.size(), loadedKills);
		}
		catch (Exception e)
		{
			// corrupt ledger shouldn't kill the plugin, but it must not get overwritten
			// either - it's the only copy. loadFailed blocks every save until restart.
			log.warn("could not read stored ledger, starting empty and refusing to save over it", e);
			allTime = new HashMap<>();
			loadedKills = 0;
			loadFailed = true;
		}
	}

	private void save()
	{
		// a ledger that reads 11 kills and writes back 1 has eaten someone's history.
		// that already happened once, from a load that silently returned nothing. kills
		// only ever go up within a profile, so a shrink is a bug every single time.
		if (loadFailed)
		{
			log.warn("refusing to save: the stored ledger could not be read, and overwriting it would destroy it");
			return;
		}

		final int kills = totalKills();
		if (kills < loadedKills)
		{
			log.error("refusing to save: ledger shrank from {} kills to {}. this is a bug - report it, do not clear it",
				loadedKills, kills);
			return;
		}

		try
		{
			configManager.setRSProfileConfiguration(EverykillConfig.GROUP, LEDGER_KEY, gson.toJson(allTime));
			loadedKills = kills;
			dirty = false;
		}
		catch (Exception e)
		{
			log.warn("could not persist ledger", e);
		}
	}

	private int totalKills()
	{
		int total = 0;
		for (NpcStat stat : allTime.values())
		{
			total += stat.total();
		}
		return total;
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

		// but never let a zero overwrite a level we already knew. getCombatLevel()
		// returns 0 when the composition wasn't loaded at the moment we asked, and
		// blindly assigning it means one unlucky kill blanks the level forever - which
		// is why Dagannoth and Ankou showed with no level while everything else had
		// one. the display rollup keys on (name, level), so a blanked level also
		// silently merges Dagannoth Rex, Prime and Supreme into one row.
		if (kill.combatLevel > 0)
		{
			stat.combatLevel = kill.combatLevel;
		}
		stat.record(kill.grade, kill.timestampMillis, kill.myDamage, kill.othersDamage);
		stat.recordFight(kill.fightTicks);

		// after record(), so total() already counts this kill - a drop on kill 500
		// should read "0 kills dry", not 1. only CONFIRMED and PROBABLE loot is filed:
		// UNKNOWN means we couldn't say which of two identical monsters earned it, and
		// filing it anyway would put a guess in the drop history permanently.
		final boolean fileDrops = kill.lootConfidence == LootConfidence.CONFIRMED
			|| kill.lootConfidence == LootConfidence.PROBABLE;

		if (fileDrops)
		{
			stat.recordDrops(kill.drops, kill.timestampMillis);
		}

		final NpcStat sessionStat = session.computeIfAbsent(kill.npcId,
			k -> new NpcStat(kill.npcId, kill.npcName));
		sessionStat.record(kill.grade, kill.timestampMillis, kill.myDamage, kill.othersDamage);
		sessionStat.recordFight(kill.fightTicks);

		// the session row needs its own copy. it isn't the same object as the all-time
		// row, so filing drops once only fed the All tab and the Now tab showed a
		// monster with no drops under it.
		if (fileDrops)
		{
			sessionStat.recordDrops(kill.drops, kill.timestampMillis);
		}

		sessionKills++;

		// high-water mark across every session, because only the CURRENT one is kept
		// in memory - without this a personal best dies when the client closes.
		if (sessionKills > bestSessionKills)
		{
			bestSessionKills = sessionKills;
			configManager.setRSProfileConfiguration(EverykillConfig.GROUP,
				BEST_SESSION_KEY, bestSessionKills);
		}
		sessionGrades.merge(kill.grade, 1, Integer::sum);

		// the fight's xp landed before this row existed. claim it now.
		final Map<String, Long> held = xpBeforeFirstKill.remove(kill.npcId);
		if (held != null)
		{
			for (Map.Entry<String, Long> e : held.entrySet())
			{
				applyNamed(stat, e.getKey(), e.getValue());
				applyNamed(sessionStat, e.getKey(), e.getValue());
			}
		}

		save();
		return stat;
	}

	// ------------------------------------------------------------------

	// no row, no xp - a monster we never killed getting xp means the allocation is
	// wrong, and a zero-kill row would hide that. but the whole fight's xp arrives
	// BEFORE the kill that creates the row, so hold it rather than bin it.
	public void addXp(int npcId, CombatSkill skill, long xp)
	{
		if (xp <= 0L)
		{
			return;
		}

		final NpcStat stat = allTime.get(String.valueOf(npcId));
		if (stat == null)
		{
			xpBeforeFirstKill.computeIfAbsent(npcId, k -> new HashMap<>())
				.merge(skill.name(), xp, Long::sum);
			return;
		}

		apply(stat, skill, xp);
		dirty = true;

		final NpcStat sessionStat = session.get(npcId);
		if (sessionStat != null)
		{
			apply(sessionStat, skill, xp);
		}
	}

	private static void apply(NpcStat stat, CombatSkill skill, long xp)
	{
		applyNamed(stat, skill.name(), xp);
	}

	private static void applyNamed(NpcStat stat, String skill, long xp)
	{
		stat.xp += xp;
		if (stat.xpBySkill == null)
		{
			stat.xpBySkill = new HashMap<>();
		}
		stat.xpBySkill.merge(skill, xp, Long::sum);

		// xp lands on today's bucket. it arrives a tick before the hitsplat, not a day
		// before, so "now" is right even for a kill that straddles midnight.
		stat.dayOf(System.currentTimeMillis()).xp += xp;
	}

	public NpcStat get(int npcId)
	{
		return allTime.get(String.valueOf(npcId));
	}

	/** All-time stats, most-killed first. */
	/** Most kills in any one session, ever. 0 until a session has ended a kill. */
	public int getBestSessionKills()
	{
		return bestSessionKills;
	}

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
	/**
	 * When this session started, for elapsed time and per-hour rates.
	 *
	 * <p>Reset by {@code startSession()}, so it means "since the counters were last
	 * zeroed" — not since login. Those differ after a manual reset and the panel
	 * should say the same thing the counters do.
	 */
	public long getSessionStartMillis()
	{
		return sessionStartMillis;
	}

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
