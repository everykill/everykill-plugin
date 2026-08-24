/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill;

import com.everykill.detect.LootDetector;
import com.everykill.model.Confidence;
import com.everykill.model.DeathSignal;
import com.everykill.model.KillRecord;
import com.everykill.model.LootConfidence;
import java.util.Collections;

import net.runelite.client.game.ItemStack;
import org.junit.Assert;
import org.junit.Test;

/**
 * Joining server-reported loot to a resolved kill.
 *
 * The case that matters is two of the same monster dying on one tick: the items are
 * real but nothing says which kill earned which, and calling that a clean drop is how
 * a drop rate quietly goes wrong.
 */
public class AttachLootTest
{
	private static final int CYCLOPS = 7271;
	private static final int BIG_BONES = 532;
	private static final int COINS = 995;

	private static KillRecord kill(Confidence grade)
	{
		return new KillRecord("evt", CYCLOPS, "Cyclops", 56, 6556, grade,
			DeathSignal.OBSERVED, 75, 0, 6, 6, 18, 1_756_000_000_000L);
	}

	private static LootDetector.ServerLoot loot(int eventId, int... itemQtyPairs)
	{
		final LootDetector detector = new LootDetector(null);
		for (int i = 0; i < itemQtyPairs.length; i += 2)
		{
			detector.record(CYCLOPS, "Cyclops", eventId, itemQtyPairs[i], itemQtyPairs[i + 1], 100);
		}
		return detector.drainFor(CYCLOPS, 100).get(0);
	}

	@Test
	public void aCleanKillWithOneReportedDropIsConfirmed()
	{
		// the measured shape: big bones plus one rolled item, one event id.
		final KillRecord out = EverykillPlugin.attachLoot(kill(Confidence.UNCONTESTED),
			Collections.singletonList(loot(77265, BIG_BONES, 1, COINS, 99)));

		Assert.assertEquals(LootConfidence.CONFIRMED, out.lootConfidence);
		Assert.assertEquals(2, out.drops.size());
		Assert.assertEquals(BIG_BONES, out.drops.get(0).itemId);
		Assert.assertEquals("quantity is the server's own", 99, out.drops.get(1).quantity);
	}

	@Test
	public void aContestedKillsLootIsProbableNotConfirmed()
	{
		// the drop is real and ours. it just can't sit in a denominator, because the
		// kill it came from wasn't clean.
		final KillRecord out = EverykillPlugin.attachLoot(kill(Confidence.AMBIGUOUS),
			Collections.singletonList(loot(77265, BIG_BONES, 1)));

		Assert.assertEquals(LootConfidence.PROBABLE, out.lootConfidence);
		Assert.assertEquals(1, out.drops.size());
	}

	@Test
	public void aDeducedKillsLootIsProbable()
	{
		final KillRecord out = EverykillPlugin.attachLoot(kill(Confidence.INFERRED),
			Collections.singletonList(loot(77265, BIG_BONES, 1)));

		Assert.assertEquals(LootConfidence.PROBABLE, out.lootConfidence);
	}

	@Test
	public void twoDropsForOneNpcIdOnOneTickIsUnknown()
	{
		// the whole reason this method exists. two cyclopes died together, the server
		// reported both drops against id 7271, and nothing says which is which.
		// UNKNOWN keeps them out of denominators instead of inventing an owner.
		final KillRecord out = EverykillPlugin.attachLoot(kill(Confidence.UNCONTESTED),
			java.util.Arrays.asList(loot(77265, BIG_BONES, 1), loot(77266, BIG_BONES, 1)));

		Assert.assertEquals(LootConfidence.UNKNOWN, out.lootConfidence);
		Assert.assertEquals("the items are kept, just not attributed", 2, out.drops.size());
	}

	@Test
	public void noReportedLootIsNoneNotEmpty()
	{
		// ghosts really do drop nothing (measured), but so does an ironman's voided
		// kill and so does one we missed. NONE says "no answer", not "no drop".
		final KillRecord out = EverykillPlugin.attachLoot(kill(Confidence.UNCONTESTED),
			Collections.emptyList());

		Assert.assertEquals(LootConfidence.NONE, out.lootConfidence);
		Assert.assertTrue(out.drops.isEmpty());
	}

	@Test
	public void theOriginalKillIsNotMutated()
	{
		final KillRecord original = kill(Confidence.UNCONTESTED);
		final KillRecord out = EverykillPlugin.attachLoot(original,
			Collections.singletonList(loot(77265, BIG_BONES, 1)));

		Assert.assertTrue("the record handed in stays as it was", original.drops.isEmpty());
		Assert.assertEquals(LootConfidence.NONE, original.lootConfidence);
		Assert.assertNotSame(original, out);
		Assert.assertEquals("everything else survives the copy", original.myDamage, out.myDamage);
	}

	@Test
	public void dropsCannotBeMutatedThroughTheRecord()
	{
		final KillRecord out = EverykillPlugin.attachLoot(kill(Confidence.UNCONTESTED),
			Collections.singletonList(loot(77265, BIG_BONES, 1)));

		try
		{
			out.drops.add(null);
			Assert.fail("drops should be unmodifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// as intended
		}
	}

	/** Guards the assumption the ItemStack accessors are what we think they are. */
	@Test
	public void itemStackQuantityIsReadCorrectly()
	{
		final ItemStack stack = new ItemStack(COINS, 99);
		Assert.assertEquals(COINS, stack.getId());
		Assert.assertEquals(99, stack.getQuantity());
	}

}
