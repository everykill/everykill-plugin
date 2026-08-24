/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.detect;

import java.util.Arrays;
import java.util.List;
import net.runelite.client.game.ItemStack;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Buffering of server-reported loot.
 *
 * Step 6 capture only — nothing here joins loot to a kill yet. Tests drive
 * {@code record} rather than the RuneLite event, so no mocking framework is needed.
 */
public class LootDetectorTest
{
	private static final int GIANT_RAT = 2856;
	private static final int GOBLIN = 3033;
	private static final int BONES = 526;

	private LootDetector loot;

	@Before
	public void setUp()
	{
		loot = new LootDetector(null);
	}

	private static List<ItemStack> bones()
	{
		return Arrays.asList(new ItemStack(BONES, 1));
	}

	@Test
	public void lootIsBufferedAndClaimedByItsOwnNpcOnItsOwnTick()
	{
		loot.record(GIANT_RAT, "Giant rat", bones(), 100);

		final List<LootDetector.ServerLoot> mine = loot.drainFor(GIANT_RAT, 100);

		Assert.assertEquals(1, mine.size());
		Assert.assertEquals(GIANT_RAT, mine.get(0).npcId);
		Assert.assertEquals(BONES, mine.get(0).items.get(0).getId());
		Assert.assertEquals("draining consumes it", 0, loot.pendingCount());
	}

	@Test
	public void anotherMonstersLootIsNotClaimed()
	{
		loot.record(GIANT_RAT, "Giant rat", bones(), 100);

		Assert.assertTrue(loot.drainFor(GOBLIN, 100).isEmpty());
		Assert.assertEquals("still there for its real owner", 1, loot.pendingCount());
	}

	@Test
	public void lootFromAnotherTickIsNotClaimed()
	{
		// the whole point of keying on the tick. a kill resolving now must not pick up
		// a drop the server reported for the previous one.
		loot.record(GIANT_RAT, "Giant rat", bones(), 100);

		Assert.assertTrue(loot.drainFor(GIANT_RAT, 101).isEmpty());
	}

	@Test
	public void twoOfTheSameMonsterOnOneTickBothComeBack()
	{
		// the known hole. ServerNpcLoot carries a composition, not the npc instance, so
		// two identical monsters dying together can't be told apart. returning both is
		// how the caller finds out it's ambiguous - collapsing to one would invent an
		// answer, and spec-drop-attribution says those kills are `unknown`.
		loot.record(GIANT_RAT, "Giant rat", bones(), 100);
		loot.record(GIANT_RAT, "Giant rat", bones(), 100);

		Assert.assertEquals(2, loot.drainFor(GIANT_RAT, 100).size());
	}

	@Test
	public void expiringDropsOnlyOlderTicks()
	{
		loot.record(GIANT_RAT, "Giant rat", bones(), 100);
		loot.record(GOBLIN, "Goblin", bones(), 101);

		loot.expire(101);

		Assert.assertEquals("this tick's loot survives", 1, loot.pendingCount());
		Assert.assertEquals(1, loot.drainFor(GOBLIN, 101).size());
	}

	@Test
	public void itemsOnAClaimedDropCannotBeMutatedByTheCaller()
	{
		// a consumer editing what it was handed would corrupt the next reader's view.
		loot.record(GIANT_RAT, "Giant rat", bones(), 100);
		final LootDetector.ServerLoot claimed = loot.drainFor(GIANT_RAT, 100).get(0);

		try
		{
			claimed.items.add(new ItemStack(BONES, 99));
			Assert.fail("items should be unmodifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// as intended
		}
	}
}
