/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.detect;

import java.util.EnumSet;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.WorldType;

/**
 * Whether kills on this world belong on the main leaderboard.
 *
 * <h2>Why this exists</h2>
 *
 * Nothing recorded the world a kill happened on, so a Deadman or Leagues kill was
 * indistinguishable from a main-game one. Those saves are temporary and get wiped, so
 * a rank built on them is a rank nobody can ever contest — and it inflates the
 * denominator for everyone who plays the real game.
 *
 * <p>The seasonal npc-id list catches league <i>monsters</i>. This catches league
 * <i>worlds</i>, which is the bigger hole: on a Deadman world you kill ordinary
 * monsters with ordinary ids, and no id list can tell those apart.
 *
 * <h2>What counts as normal</h2>
 *
 * Free and members only. Core does the same thing in {@code ChatCommandsPlugin} —
 * a non-normal world type resolves to a different hiscore endpoint entirely, and the
 * same reasoning applies to a leaderboard.
 *
 * <p>PvP and high-risk worlds are <b>normal</b>. They are the live game with different
 * rules, the account is the same account, and the kills are real. Only worlds on a
 * separate or temporary save are excluded.
 */
@Singleton
public class WorldFilter
{
	/**
	 * World types whose kills do not belong on the main board.
	 *
	 * <p>Each one is a separate or throwaway save:
	 * <ul>
	 *   <li>{@code DEADMAN} — separate save, wiped each season</li>
	 *   <li>{@code SEASONAL} — Leagues; separate save, wiped</li>
	 *   <li>{@code TOURNAMENT_WORLD} — throwaway</li>
	 *   <li>{@code BETA_WORLD} — a copy of your account on unreleased content</li>
	 *   <li>{@code NOSAVE_MODE} — nothing persists at all</li>
	 *   <li>{@code QUEST_SPEEDRUNNING} — a prebuilt account, not yours</li>
	 *   <li>{@code FRESH_START_WORLD} — separate save with its own hiscores</li>
	 * </ul>
	 */
	private static final EnumSet<WorldType> EXCLUDED = EnumSet.of(
		WorldType.DEADMAN,
		WorldType.SEASONAL,
		WorldType.TOURNAMENT_WORLD,
		WorldType.BETA_WORLD,
		WorldType.NOSAVE_MODE,
		WorldType.QUEST_SPEEDRUNNING,
		WorldType.FRESH_START_WORLD);

	private final Client client;

	@Inject
	public WorldFilter(Client client)
	{
		this.client = client;
	}

	/**
	 * Whether the current world's kills count toward the main leaderboard.
	 *
	 * <p>Must be called on the client thread.
	 */
	public boolean isRanked()
	{
		return isRanked(client.getWorldType());
	}

	/** Testable form — no client needed. */
	static boolean isRanked(EnumSet<WorldType> types)
	{
		if (types == null)
		{
			// we could not read the world. treating that as ranked would let a
			// deadman kill through on a read failure, so it doesn't.
			return false;
		}

		for (WorldType type : EXCLUDED)
		{
			if (types.contains(type))
			{
				return false;
			}
		}
		return true;
	}

	/** A short label for the world type, or null on an ordinary world. */
	public String excludedReason()
	{
		final EnumSet<WorldType> types = client.getWorldType();
		if (types == null)
		{
			return "unknown world";
		}

		for (WorldType type : EXCLUDED)
		{
			if (types.contains(type))
			{
				return type.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
			}
		}
		return null;
	}
}
