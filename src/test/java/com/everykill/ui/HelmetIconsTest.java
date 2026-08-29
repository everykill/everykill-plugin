/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.ui;

import com.google.gson.Gson;
import org.junit.Assert;
import org.junit.Test;

/**
 * The shipped helmet-to-item table.
 *
 * <p>Ids came from the wiki via {@code tools/fetch-helmet-items.py}, checked 53/53
 * against the live catalogue. Constant-name matching only reached 25/53 because
 * {@code gameval} uses game-internal names, and {@code ItemManager.search} is
 * tradeable-only so it misses Slayer helmet and the Barrows pieces.
 */
public class HelmetIconsTest
{
	private final HelmetIcons icons = HelmetIcons.load(new Gson());

	@Test
	public void theTableShips()
	{
		// a resource that doesn't make it into the jar fails silently to an empty map,
		// and the picker would just draw no sprites forever.
		Assert.assertTrue("helmet-items.json must be on the classpath",
			icons.itemFor("cowl") > 0);
	}

	@Test
	public void knownHelmetsResolve()
	{
		// spot-checked against the wiki by hand.
		Assert.assertEquals(1167, icons.itemFor("cowl"));
		Assert.assertEquals(11335, icons.itemFor("dragon"));
		Assert.assertEquals(11864, icons.itemFor("slayer"));
		Assert.assertEquals(26376, icons.itemFor("torva"));
	}

	@Test
	public void theOnesConstantMatchingMissedAreThere()
	{
		// these are exactly why the table exists rather than a name lookup.
		Assert.assertTrue(icons.itemFor("slayer-i") > 0);
		Assert.assertTrue(icons.itemFor("void") > 0);
		Assert.assertTrue(icons.itemFor("ahrim") > 0);
		Assert.assertTrue(icons.itemFor("black-mask") > 0);
	}

	@Test
	public void anUnknownHelmetDegradesInsteadOfThrowing()
	{
		// the site can add a helmet before a plugin update lands. -1 means the row
		// draws without a sprite, not that it doesn't draw.
		Assert.assertEquals(-1, icons.itemFor("helmet-from-the-future"));
		Assert.assertEquals(-1, icons.itemFor(null));
	}
}
