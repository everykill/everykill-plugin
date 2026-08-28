/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

/**
 * Which version of a boss a kill was on, when the npc id can't say.
 *
 * <h2>Why this exists</h2>
 *
 * The DT2 bosses reuse their npc id across quest, post-quest and Awakened — Jagex swaps
 * the stats and keeps the id. Verified on the wiki 2026-08-28:
 *
 * <pre>
 *                  quest   post-quest   awakened
 * Duke Sucellus      538       758        1099
 * Vardorvis          572       784        1136
 * The Leviathan      593       798        1157
 * The Whisperer      587       791        1146
 * </pre>
 *
 * So there is nothing in {@code npcId} to read, and the combat level is the only live
 * signal. The client has it; the server doesn't, because its leaderboard rollup is
 * keyed {@code (account_id, npc_id)} and collapses the variants before any query runs.
 *
 * <h2>Why it isn't a general mechanism</h2>
 *
 * Gage audited all 1,279 monsters for one id carrying two combat levels. These four are
 * the only difficulty variants. The single other hit was Zombie (13 vs 24), an ordinary
 * level variant rather than a difficulty you opt into — it stays merged.
 *
 * <p>Quest and post-quest stay together: same fight, one is a re-run. Awakened gets its
 * own board.
 */
public final class BossVariant
{
	/** Sent on the wire; null for everything else. */
	public static final String AWAKENED = "awakened";

	// npc ids and their awakened combat level. thresholds, not equality: if Jagex
	// rebalances by a few levels, >= keeps working where == silently stops tagging.
	private static final int DUKE_SUCELLUS = 12191;
	private static final int VARDORVIS = 12223;
	private static final int VARDORVIS_ALT = 12426;
	private static final int LEVIATHAN = 12214;
	private static final int WHISPERER = 12204;
	private static final int WHISPERER_ALT = 12205;

	private BossVariant()
	{
	}

	/**
	 * The variant for a kill, or null when there isn't one.
	 *
	 * @param npcId       the raw game id
	 * @param combatLevel the level read live off the NPC
	 */
	public static String of(int npcId, int combatLevel)
	{
		switch (npcId)
		{
			case DUKE_SUCELLUS:
				return combatLevel >= 1099 ? AWAKENED : null;
			case VARDORVIS:
			case VARDORVIS_ALT:
				return combatLevel >= 1136 ? AWAKENED : null;
			case LEVIATHAN:
				return combatLevel >= 1157 ? AWAKENED : null;
			case WHISPERER:
			case WHISPERER_ALT:
				return combatLevel >= 1146 ? AWAKENED : null;
			default:
				return null;
		}
	}

	/** Display suffix for a variant, e.g. {@code "Vardorvis (Awakened)"}. */
	public static String label(String name, String variant)
	{
		if (variant == null || name == null)
		{
			return name;
		}
		return name + " (" + Character.toUpperCase(variant.charAt(0))
			+ variant.substring(1) + ")";
	}
}
