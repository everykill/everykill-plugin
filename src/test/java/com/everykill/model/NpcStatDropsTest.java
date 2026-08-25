/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Per-monster drop tallies and the dry streak built on them.
 *
 * The numbers here become drop-rate numerators and "kills since" displays, so the
 * failure mode is a plausible wrong number rather than a crash.
 */
public class NpcStatDropsTest
{
	private static final int BIG_BONES = 532;
	private static final int SAPPHIRE = 1623;
	private static final long WHEN = 1_756_000_000_000L;

	private NpcStat stat;

	@Before
	public void setUp()
	{
		stat = new NpcStat(7271, "Cyclops");
	}

	private void kill()
	{
		stat.record(Confidence.UNCONTESTED, WHEN);
	}

	private void killWithDrops(Drop... drops)
	{
		kill();
		stat.recordDrops(Arrays.asList(drops), WHEN);
	}

	@Test
	public void quantitiesAndDropCountsAreTrackedSeparately()
	{
		// 99 coins in one kill is ONE drop of quantity 99. conflating the two would
		// make a stack look like 99 rolls.
		killWithDrops(new Drop(BIG_BONES, 1));
		killWithDrops(new Drop(BIG_BONES, 1));

		final NpcStat.DropTally bones = stat.drops.get(String.valueOf(BIG_BONES));
		Assert.assertEquals(2L, bones.quantity);
		Assert.assertEquals(2, bones.drops);
	}

	@Test
	public void oneKillDroppingAnItemTwiceIsStillOneDrop()
	{
		// a monster dropping bones twice on one death is one roll. counting it twice
		// inflates the numerator of every rate built on this.
		killWithDrops(new Drop(BIG_BONES, 1), new Drop(BIG_BONES, 1));

		final NpcStat.DropTally bones = stat.drops.get(String.valueOf(BIG_BONES));
		Assert.assertEquals("both items counted", 2L, bones.quantity);
		Assert.assertEquals("one roll", 1, bones.drops);
	}

	@Test
	public void aDropOnTheCurrentKillIsZeroKillsDry()
	{
		// the ordering trap. recordDrops runs AFTER record(), so total() already
		// includes the kill that produced the loot.
		killWithDrops(new Drop(SAPPHIRE, 1));

		Assert.assertEquals(0, stat.killsSince(SAPPHIRE));
	}

	@Test
	public void theDryStreakCountsKillsSinceTheLastDrop()
	{
		killWithDrops(new Drop(SAPPHIRE, 1));
		kill();
		kill();
		kill();

		Assert.assertEquals(3, stat.killsSince(SAPPHIRE));
	}

	@Test
	public void aFreshDropResetsTheStreak()
	{
		killWithDrops(new Drop(SAPPHIRE, 1));
		kill();
		kill();
		killWithDrops(new Drop(SAPPHIRE, 1));

		Assert.assertEquals(0, stat.killsSince(SAPPHIRE));
	}

	@Test
	public void anItemNeverSeenReportsMinusOneNotZero()
	{
		// zero would read as "it just dropped". -1 says we have no history, which is a
		// different statement and the panel has to show it differently.
		kill();

		Assert.assertEquals(-1, stat.killsSince(SAPPHIRE));
	}

	@Test
	public void aMonsterWithNoDropsHasNoMap()
	{
		// null until the first drop, so lootless monsters cost nothing in the json.
		kill();
		stat.recordDrops(Collections.emptyList(), WHEN);

		Assert.assertNull(stat.drops);
		Assert.assertEquals(-1, stat.killsSince(BIG_BONES));
	}

	@Test
	public void nullDropsAreNotAnError()
	{
		kill();
		stat.recordDrops(null, WHEN);

		Assert.assertNull(stat.drops);
	}

	@Test
	public void separateItemsKeepSeparateStreaks()
	{
		killWithDrops(new Drop(BIG_BONES, 1), new Drop(SAPPHIRE, 1));
		killWithDrops(new Drop(BIG_BONES, 1));
		killWithDrops(new Drop(BIG_BONES, 1));

		Assert.assertEquals("bones dropped on the last kill", 0, stat.killsSince(BIG_BONES));
		Assert.assertEquals("sapphire two kills ago", 2, stat.killsSince(SAPPHIRE));
	}

	@Test
	public void lastSeenTimeIsKept()
	{
		kill();
		stat.recordDrops(Collections.singletonList(new Drop(SAPPHIRE, 1)), WHEN + 5000L);

		Assert.assertEquals(WHEN + 5000L, stat.drops.get(String.valueOf(SAPPHIRE)).lastMillis);
	}
}
