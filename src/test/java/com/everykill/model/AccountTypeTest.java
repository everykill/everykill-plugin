/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

import org.junit.Assert;
import org.junit.Test;

/**
 * Account modes, and the one that stops being itself.
 *
 * <p>The hardcore rules here follow the official hiscores rather than anything we
 * invented: a fallen HCIM's entry is <b>locked</b>, not deleted and not migrated —
 * <i>"their experience and total level on the Hardcore Ironman HiScores table will be
 * locked, with their name slashed across"</i>
 * (oldschool.runescape.wiki/w/Ironman_Mode, read 2026-08-26).
 */
public class AccountTypeTest
{
	@Test
	public void aFallenHardcoreStillHasIronmanLootRules()
	{
		// the account is a normal ironman now. every loot rule applies unchanged, and
		// getting this wrong would silently switch drop voiding off.
		Assert.assertTrue(AccountType.DEAD_HARDCORE_IRONMAN.outsideDamageVoidsLoot());
		Assert.assertTrue(AccountType.DEAD_HARDCORE_IRONMAN.isIronman());
	}

	@Test
	public void aFallenHardcoreNoLongerEarnsHardcoreRank()
	{
		// the board is frozen at the moment of death. new kills belong to the ironman
		// board - crediting them to hardcore would rank a dead account against living
		// ones.
		Assert.assertFalse(AccountType.DEAD_HARDCORE_IRONMAN.countsAsHardcore());
		Assert.assertTrue(AccountType.HARDCORE_IRONMAN.countsAsHardcore());
	}

	@Test
	public void onlyHardcoreCountsAsHardcore()
	{
		for (AccountType type : AccountType.values())
		{
			if (type != AccountType.HARDCORE_IRONMAN)
			{
				Assert.assertFalse(type + " must not rank as hardcore", type.countsAsHardcore());
			}
		}
	}

	@Test
	public void everyIronmanModeVoidsLootOnOutsideDamage()
	{
		Assert.assertTrue(AccountType.IRONMAN.outsideDamageVoidsLoot());
		Assert.assertTrue(AccountType.ULTIMATE_IRONMAN.outsideDamageVoidsLoot());
		Assert.assertTrue(AccountType.HARDCORE_IRONMAN.outsideDamageVoidsLoot());
		Assert.assertTrue(AccountType.GROUP_IRONMAN.outsideDamageVoidsLoot());
		Assert.assertTrue(AccountType.DEAD_HARDCORE_IRONMAN.outsideDamageVoidsLoot());
	}

	@Test
	public void aMainDoesNotVoidLoot()
	{
		Assert.assertFalse(AccountType.MAIN.outsideDamageVoidsLoot());
	}

	@Test
	public void anUnknownModeIsNotTreatedAsAMain()
	{
		// jagex adding a mode must not quietly switch ironman rules off. an
		// unrecognised value becomes GROUP_UNRESOLVED, never MAIN.
		Assert.assertEquals(AccountType.GROUP_UNRESOLVED, AccountType.fromVarbit(99));
		Assert.assertEquals(AccountType.GROUP_UNRESOLVED, AccountType.fromVarbit(-7));
	}

	@Test
	public void theKnownVarbitValuesMapAsCoreMapsThem()
	{
		Assert.assertEquals(AccountType.MAIN, AccountType.fromVarbit(0));
		Assert.assertEquals(AccountType.IRONMAN, AccountType.fromVarbit(1));
		Assert.assertEquals(AccountType.ULTIMATE_IRONMAN, AccountType.fromVarbit(2));
		Assert.assertEquals(AccountType.HARDCORE_IRONMAN, AccountType.fromVarbit(3));
	}

	@Test
	public void modesThatAreNotVarbitValuesCannotBeReturnedByFromVarbit()
	{
		// group and fallen-hardcore both need a second read - the clan channel and
		// IRONMAN_HARDCORE_DEAD. fromVarbit must never guess at either.
		for (int value = -10; value <= 20; value++)
		{
			final AccountType type = AccountType.fromVarbit(value);
			Assert.assertNotEquals(AccountType.GROUP_IRONMAN, type);
			Assert.assertNotEquals(AccountType.DEAD_HARDCORE_IRONMAN, type);
		}
	}
}
