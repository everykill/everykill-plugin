/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.detect;

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
	private static final int COINS = 995;
	private static final int EVENT = 77265;

	private LootDetector loot;

	@Before
	public void setUp()
	{
		loot = new LootDetector(null);
	}

	@Test
	public void lootIsBufferedAndClaimedByItsOwnNpcOnItsOwnTick()
	{
		loot.record(GIANT_RAT, "Giant rat", EVENT, BONES, 1, 100);

		final List<LootDetector.ServerLoot> mine = loot.drainFor(GIANT_RAT, 100);

		Assert.assertEquals(1, mine.size());
		Assert.assertEquals(GIANT_RAT, mine.get(0).npcId);
		Assert.assertEquals(EVENT, mine.get(0).eventId);
		Assert.assertEquals(BONES, mine.get(0).getItems().get(0).getId());
		Assert.assertEquals("draining consumes it", 0, loot.pendingCount());
	}

	@Test
	public void itemsSharingAnEventIdAreOneKillsDrop()
	{
		// the script fires once per item. a cyclops dropping big bones and 99 coins is
		// two fires with one event id, measured 2026-08-24 - not two kills.
		loot.record(GIANT_RAT, "Giant rat", EVENT, BONES, 1, 100);
		loot.record(GIANT_RAT, "Giant rat", EVENT, COINS, 99, 100);

		final List<LootDetector.ServerLoot> mine = loot.drainFor(GIANT_RAT, 100);

		Assert.assertEquals("one kill", 1, mine.size());
		Assert.assertEquals("both items", 2, mine.get(0).getItems().size());
	}

	@Test
	public void differentEventIdsAreDifferentKills()
	{
		// the whole reason we read the script instead of ServerNpcLoot. same monster,
		// same tick, two kills - and only the event id can tell them apart, because
		// the composition is identical.
		loot.record(GIANT_RAT, "Giant rat", EVENT, BONES, 1, 100);
		loot.record(GIANT_RAT, "Giant rat", EVENT + 1, BONES, 1, 100);

		final List<LootDetector.ServerLoot> mine = loot.drainFor(GIANT_RAT, 100);

		Assert.assertEquals("two separate kills", 2, mine.size());
		Assert.assertNotEquals(mine.get(0).eventId, mine.get(1).eventId);
	}

	@Test
	public void anotherMonstersLootIsNotClaimed()
	{
		loot.record(GIANT_RAT, "Giant rat", EVENT, BONES, 1, 100);

		Assert.assertTrue(loot.drainFor(GOBLIN, 100).isEmpty());
		Assert.assertEquals("still there for its real owner", 1, loot.pendingCount());
	}

	@Test
	public void lootFromAnotherTickIsNotClaimed()
	{
		// the point of keying on the tick. a kill resolving now must not pick up a drop
		// the server reported for the previous one.
		loot.record(GIANT_RAT, "Giant rat", EVENT, BONES, 1, 100);

		Assert.assertTrue(loot.drainFor(GIANT_RAT, 101).isEmpty());
	}

	@Test
	public void expiringDropsOnlyOlderTicks()
	{
		loot.record(GIANT_RAT, "Giant rat", EVENT, BONES, 1, 100);
		loot.record(GOBLIN, "Goblin", EVENT + 1, BONES, 1, 101);

		loot.expire(101);

		Assert.assertEquals("this tick's loot survives", 1, loot.pendingCount());
		Assert.assertEquals(1, loot.drainFor(GOBLIN, 101).size());
	}

	@Test
	public void itemsOnAClaimedDropCannotBeMutatedByTheCaller()
	{
		// a consumer editing what it was handed would corrupt the next reader's view.
		loot.record(GIANT_RAT, "Giant rat", EVENT, BONES, 1, 100);
		final LootDetector.ServerLoot claimed = loot.drainFor(GIANT_RAT, 100).get(0);

		try
		{
			claimed.getItems().add(new ItemStack(BONES, 99));
			Assert.fail("items should be unmodifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// as intended
		}
	}
}
