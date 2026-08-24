/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

import org.junit.Assert;
import org.junit.Test;

/**
 * Account type mapping.
 *
 * The rules measured on 2026-08-24 — a contested kill voiding an ironman's drop
 * entirely, even at 90% of the damage — are wrong for a main, who wins that same drop.
 * So the mapping has to be right and the unknown cases have to stay visible.
 */
public class AccountTypeTest
{
	@Test
	public void theVarbitMappingIsCoresNotOurs()
	{
		// HiscorePlugin.java:277-286, corroborated by DailyTasksPlugin's "!= 2 /* UIM */"
		Assert.assertEquals(AccountType.MAIN, AccountType.fromVarbit(0));
		Assert.assertEquals(AccountType.IRONMAN, AccountType.fromVarbit(1));
		Assert.assertEquals(AccountType.ULTIMATE_IRONMAN, AccountType.fromVarbit(2));
		Assert.assertEquals(AccountType.HARDCORE_IRONMAN, AccountType.fromVarbit(3));
	}

	@Test
	public void anUnknownValueIsNotQuietlyTreatedAsAMain()
	{
		// jagex adding a mode must not switch the ironman rules off for accounts that
		// need them. failing loud beats failing convenient.
		Assert.assertEquals(AccountType.GROUP_UNRESOLVED, AccountType.fromVarbit(4));
		Assert.assertEquals(AccountType.GROUP_UNRESOLVED, AccountType.fromVarbit(99));
		Assert.assertEquals(AccountType.GROUP_UNRESOLVED, AccountType.fromVarbit(-1));
	}

	@Test
	public void everyIronmanModeVoidsLootOnOutsideDamage()
	{
		Assert.assertTrue(AccountType.IRONMAN.outsideDamageVoidsLoot());
		Assert.assertTrue(AccountType.ULTIMATE_IRONMAN.outsideDamageVoidsLoot());
		Assert.assertTrue(AccountType.HARDCORE_IRONMAN.outsideDamageVoidsLoot());
	}

	@Test
	public void aMainKeepsItsContestedDrops()
	{
		// measured the other way round: a main who deals the most damage receives the
		// drop. treating a main like an iron would discard real kills.
		Assert.assertFalse(AccountType.MAIN.outsideDamageVoidsLoot());
	}

	@Test
	public void groupIronmanIsNotReachableFromTheVarbit()
	{
		// core's switch has no case for it - it falls through to normal. group status
		// comes from the clan channel, so no varbit value may ever map here.
		for (int v = -5; v <= 20; v++)
		{
			Assert.assertNotEquals("varbit " + v, AccountType.GROUP_IRONMAN,
				AccountType.fromVarbit(v));
		}
	}

	@Test
	public void aGroupIronmanStillLosesLootToOutsiders()
	{
		// they're an ironman. the nuance is that a GROUPMATE isn't an outsider, and we
		// can't tell those apart yet, so this is conservative on purpose.
		Assert.assertTrue(AccountType.GROUP_IRONMAN.outsideDamageVoidsLoot());
		Assert.assertTrue(AccountType.GROUP_IRONMAN.isIronman());
	}

	@Test
	public void anUnresolvedAccountDoesNotVoidLoot()
	{
		// deliberate. wrong here leaves a groupmate's fair kill in the denominator.
		// wrong the other way voids every legitimate group kill a GIM ever makes.
		Assert.assertFalse(AccountType.GROUP_UNRESOLVED.outsideDamageVoidsLoot());
		Assert.assertFalse(AccountType.UNKNOWN.outsideDamageVoidsLoot());
	}
}
