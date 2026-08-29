/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;

/**
 * The unlocks snapshot the picker panel reads.
 *
 * <p>Shapes verified against production 2026-08-28 with a fresh account, including the
 * empty state — which is what everyone sees on day one and the easiest thing to get
 * wrong.
 */
public class UnlocksTest
{
	private static Unlocks.Item item(String id, String name, int rarity)
	{
		return new Unlocks.Item(id, name, name.replace(' ', '_') + ".png",
			"Starter", "Upload a kill", rarity);
	}

	@Test
	public void aBrandNewAccountHasEarnedNothing()
	{
		// the real day-one response: empty lists, nothing worn, but 'next' populated
		// so the panel can say "100 kills" under a locked slot instead of a blank.
		final Unlocks u = new Unlocks(false,
			Collections.emptyList(), Collections.emptyList(), null, null,
			Arrays.asList(item("cowl", "Leather cowl", 0),
				item("bronze-med", "Bronze med helm", 0)));

		Assert.assertTrue(u.isEmpty());
		Assert.assertFalse(u.published);
		Assert.assertNull(u.wearingHelmet);
		Assert.assertEquals(2, u.next.size());
		Assert.assertEquals("Upload a kill", u.next.get(0).how);
	}

	@Test
	public void nullListsBecomeEmptyNotNull()
	{
		// a panel looping over a null list is a crash on the account tab. the server
		// omits keys it has nothing for, so this is a real response shape.
		final Unlocks u = new Unlocks(true, null, null, null, null, null);

		Assert.assertTrue(u.helmets.isEmpty());
		Assert.assertTrue(u.titles.isEmpty());
		Assert.assertTrue(u.next.isEmpty());
		Assert.assertTrue(u.isEmpty());
	}

	@Test
	public void earnedItemsAreNotEmpty()
	{
		final Unlocks u = new Unlocks(true,
			Collections.singletonList(item("cowl", "Leather cowl", 0)),
			Collections.emptyList(), "cowl", null, Collections.emptyList());

		Assert.assertFalse(u.isEmpty());
		Assert.assertEquals("cowl", u.wearingHelmet);
	}

	@Test
	public void titlesCarryTheirRarity()
	{
		// 1-7 drives colour on the site. surfacing the number is how someone knows a
		// tier 6 is worth switching to.
		final Unlocks u = new Unlocks(true, Collections.emptyList(),
			Collections.singletonList(item("the-new", "First Blood", 1)),
			null, "the-new", Collections.emptyList());

		Assert.assertEquals(1, u.titles.get(0).rarity);
	}

	@Test
	public void theListsCannotBeModifiedByTheCaller()
	{
		// the snapshot is shared with the EDT. a caller mutating it would be a race
		// nobody would ever reproduce.
		final Unlocks u = new Unlocks(true,
			Collections.singletonList(item("cowl", "Leather cowl", 0)),
			Collections.emptyList(), null, null, Collections.emptyList());

		try
		{
			u.helmets.add(item("x", "X", 0));
			Assert.fail("the snapshot must be immutable");
		}
		catch (UnsupportedOperationException expected)
		{
			// what we want
		}
	}
}
