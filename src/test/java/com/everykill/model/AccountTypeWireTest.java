/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * Every mode must map to something the ingest server accepts.
 *
 * <p>The server's list, read from its source and confirmed live on 2026-08-27. A value
 * outside it is a {@code 400}, and because publishing happens on a background flush
 * the player never sees the error — their name simply never appears.
 *
 * <p>This is the {@code UNCONTESTED} bug in a second costume: a Java enum name is not
 * a wire format, and {@code .name()} is tempting because it compiles.
 */
public class AccountTypeWireTest
{
	/** From `ACCOUNT_TYPES` in the server's `server.js`. */
	private static final Set<String> SERVER_ACCEPTS = new HashSet<>(Arrays.asList(
		"main",
		"ironman",
		"ultimate_ironman",
		"hardcore_ironman",
		"group_ironman",
		"hardcore_group_ironman",
		"unknown"));

	@Test
	public void everyModeMapsToAValueTheServerKnows()
	{
		for (AccountType type : AccountType.values())
		{
			Assert.assertTrue(
				type + " sends '" + type.wireValue() + "', which the server rejects",
				SERVER_ACCEPTS.contains(type.wireValue()));
		}
	}

	@Test
	public void aFallenHardcorePublishesAsAnIronman()
	{
		// the hiscores freeze the hardcore entry; the account itself continues as a
		// normal iron. that is the honest current badge.
		Assert.assertEquals("ironman", AccountType.DEAD_HARDCORE_IRONMAN.wireValue());
	}

	@Test
	public void anUnreadableModePublishesAsUnknown()
	{
		// the server stores 'unknown' as withheld and draws no badge, which beats
		// guessing at a mode we could not read.
		Assert.assertEquals("unknown", AccountType.GROUP_UNRESOLVED.wireValue());
		Assert.assertEquals("unknown", AccountType.UNKNOWN.wireValue());
	}

	@Test
	public void theOrdinaryModesAreJustLowercased()
	{
		Assert.assertEquals("main", AccountType.MAIN.wireValue());
		Assert.assertEquals("ironman", AccountType.IRONMAN.wireValue());
		Assert.assertEquals("ultimate_ironman", AccountType.ULTIMATE_IRONMAN.wireValue());
		Assert.assertEquals("hardcore_ironman", AccountType.HARDCORE_IRONMAN.wireValue());
		Assert.assertEquals("group_ironman", AccountType.GROUP_IRONMAN.wireValue());
	}

	@Test
	public void noModeSendsItsJavaName()
	{
		// the failure mode this file exists to stop: shipping name() because it
		// compiles, then discovering the server disagrees only in production.
		for (AccountType type : AccountType.values())
		{
			Assert.assertNotEquals("a raw enum name reached the wire",
				type.name(), type.wireValue());
		}
	}
}
