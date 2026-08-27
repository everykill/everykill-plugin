/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.detect;

import java.util.EnumSet;
import net.runelite.api.WorldType;
import org.junit.Assert;
import org.junit.Test;

/**
 * Which worlds' kills reach the leaderboard.
 *
 * <p>Found by Gage: nothing recorded the world, so a Deadman kill was indistinguishable
 * from a main-game one. The seasonal npc-id list does not help here — on a Deadman
 * world you kill ordinary monsters with ordinary ids, so only the world type can tell
 * them apart.
 */
public class WorldFilterTest
{
	@Test
	public void freeAndMembersAreRanked()
	{
		Assert.assertTrue(WorldFilter.isRanked(EnumSet.noneOf(WorldType.class)));
		Assert.assertTrue(WorldFilter.isRanked(EnumSet.of(WorldType.MEMBERS)));
	}

	@Test
	public void separateSavesAreNotRanked()
	{
		// each of these is a different save that gets wiped. a rank built on one is a
		// rank nobody can contest after the season ends.
		Assert.assertFalse(WorldFilter.isRanked(EnumSet.of(WorldType.DEADMAN)));
		Assert.assertFalse(WorldFilter.isRanked(EnumSet.of(WorldType.SEASONAL)));
		Assert.assertFalse(WorldFilter.isRanked(EnumSet.of(WorldType.TOURNAMENT_WORLD)));
		Assert.assertFalse(WorldFilter.isRanked(EnumSet.of(WorldType.BETA_WORLD)));
		Assert.assertFalse(WorldFilter.isRanked(EnumSet.of(WorldType.NOSAVE_MODE)));
		Assert.assertFalse(WorldFilter.isRanked(EnumSet.of(WorldType.QUEST_SPEEDRUNNING)));
		Assert.assertFalse(WorldFilter.isRanked(EnumSet.of(WorldType.FRESH_START_WORLD)));
	}

	@Test
	public void aMembersDeadmanWorldIsStillNotRanked()
	{
		// the flags combine, and MEMBERS being present must not rescue it.
		Assert.assertFalse(WorldFilter.isRanked(
			EnumSet.of(WorldType.MEMBERS, WorldType.DEADMAN)));
		Assert.assertFalse(WorldFilter.isRanked(
			EnumSet.of(WorldType.MEMBERS, WorldType.SEASONAL, WorldType.PVP)));
	}

	@Test
	public void pvpAndHighRiskAreRanked()
	{
		// these are the live game with different rules. same account, same save, real
		// kills. excluding them would throw away legitimate history.
		Assert.assertTrue(WorldFilter.isRanked(EnumSet.of(WorldType.PVP)));
		Assert.assertTrue(WorldFilter.isRanked(EnumSet.of(WorldType.HIGH_RISK)));
		Assert.assertTrue(WorldFilter.isRanked(
			EnumSet.of(WorldType.MEMBERS, WorldType.PVP, WorldType.BOUNTY)));
		Assert.assertTrue(WorldFilter.isRanked(EnumSet.of(WorldType.SKILL_TOTAL)));
		Assert.assertTrue(WorldFilter.isRanked(EnumSet.of(WorldType.LAST_MAN_STANDING)));
		Assert.assertTrue(WorldFilter.isRanked(EnumSet.of(WorldType.PVP_ARENA)));
	}

	@Test
	public void anUnreadableWorldIsNotRanked()
	{
		// the failure that matters. defaulting to ranked would let a deadman kill
		// through whenever the read fails, and a leaderboard cannot un-count a kill.
		Assert.assertFalse(WorldFilter.isRanked(null));
	}
}
