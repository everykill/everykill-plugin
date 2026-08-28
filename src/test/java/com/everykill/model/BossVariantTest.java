/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

import org.junit.Assert;
import org.junit.Test;

/**
 * Awakened DT2 bosses, which reuse their npc id.
 *
 * <p>Combat levels verified against the wiki 2026-08-28, not taken from the handoff:
 * Duke 538/758/1099, Vardorvis 572/784/1136, Leviathan 593/798/1157,
 * Whisperer 587/791/1146.
 */
public class BossVariantTest
{
	@Test
	public void awakenedIsTagged()
	{
		Assert.assertEquals("awakened", BossVariant.of(12191, 1099));
		Assert.assertEquals("awakened", BossVariant.of(12223, 1136));
		Assert.assertEquals("awakened", BossVariant.of(12426, 1136));
		Assert.assertEquals("awakened", BossVariant.of(12214, 1157));
		Assert.assertEquals("awakened", BossVariant.of(12204, 1146));
		Assert.assertEquals("awakened", BossVariant.of(12205, 1146));
	}

	@Test
	public void questAndPostQuestStayTogether()
	{
		// delk's rule: same fight, one is a re-run. only awakened splits off.
		Assert.assertNull(BossVariant.of(12191, 538));
		Assert.assertNull(BossVariant.of(12191, 758));
		Assert.assertNull(BossVariant.of(12223, 572));
		Assert.assertNull(BossVariant.of(12223, 784));
		Assert.assertNull(BossVariant.of(12214, 593));
		Assert.assertNull(BossVariant.of(12214, 798));
		Assert.assertNull(BossVariant.of(12204, 587));
		Assert.assertNull(BossVariant.of(12204, 791));
	}

	@Test
	public void aRebalanceUpwardsKeepsWorking()
	{
		// threshold not equality. if jagex nudges a level, >= keeps tagging where ==
		// would silently stop and nobody would notice for months.
		Assert.assertEquals("awakened", BossVariant.of(12191, 1105));
		Assert.assertEquals("awakened", BossVariant.of(12214, 1200));
	}

	@Test
	public void everyOtherMonsterIsUntagged()
	{
		// gage audited all 1,279 monsters: these four are the only difficulty variants.
		// zombie (13 vs 24) is an ordinary level variant and stays merged.
		Assert.assertNull(BossVariant.of(7271, 56));
		Assert.assertNull(BossVariant.of(26, 13));
		Assert.assertNull(BossVariant.of(26, 24));
		Assert.assertNull(BossVariant.of(12191, 0));
	}

	@Test
	public void aZeroLevelNeverTags()
	{
		// getCombatLevel() returns 0 when the composition wasn't loaded. tagging on
		// that would invent an awakened kill from a read failure.
		Assert.assertNull(BossVariant.of(12223, 0));
		Assert.assertNull(BossVariant.of(12204, 0));
	}

	@Test
	public void theLabelReadsLikeABoard()
	{
		Assert.assertEquals("Vardorvis (Awakened)",
			BossVariant.label("Vardorvis", "awakened"));
		Assert.assertEquals("Vardorvis", BossVariant.label("Vardorvis", null));
	}
}
