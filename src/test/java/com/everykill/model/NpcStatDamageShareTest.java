/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

import org.junit.Assert;
import org.junit.Test;

/**
 * Damage share on a ledger row.
 *
 * The grades say how we knew about a kill. These say how much of it was ours, and the
 * two are different questions - AMBIGUOUS covers both "a mate and i split it" and "i hit
 * a 20-man boss once", and only damage tells them apart.
 */
public class NpcStatDamageShareTest
{
	private static final long WHEN = 1_756_000_000_000L;

	@Test
	public void damageAccumulatesAcrossKills()
	{
		final NpcStat stat = new NpcStat(2042, "Zulrah");
		stat.record(Confidence.UNCONTESTED, WHEN, 500, 0);
		stat.record(Confidence.UNCONTESTED, WHEN, 480, 0);

		Assert.assertEquals(980L, stat.myDamageTotal);
		Assert.assertEquals(0L, stat.othersDamageTotal);
		Assert.assertEquals(2, stat.killsWithDamage);
	}

	@Test
	public void aSoloKillAndATaggedKillAreToldApartDespiteBothBeingRecorded()
	{
		// the whole point. two kills, and the grade cannot separate them - one is a
		// fair split, one is a single hit on someone else's boss.
		final NpcStat fair = new NpcStat(11278, "Nex");
		fair.record(Confidence.AMBIGUOUS, WHEN, 1700, 1700);

		final NpcStat tagged = new NpcStat(11278, "Nex");
		tagged.record(Confidence.AMBIGUOUS, WHEN, 40, 3360);

		Assert.assertEquals(Confidence.AMBIGUOUS.getLabel(), 1, fair.ambiguous);
		Assert.assertEquals(Confidence.AMBIGUOUS.getLabel(), 1, tagged.ambiguous);

		Assert.assertEquals("half the fight", 0.5,
			fair.myDamageTotal / (double) (fair.myDamageTotal + fair.othersDamageTotal), 0.001);
		Assert.assertEquals("barely touched it", 0.0117,
			tagged.myDamageTotal / (double) (tagged.myDamageTotal + tagged.othersDamageTotal), 0.001);
	}

	@Test
	public void aKillWithNoDamageStaysOutOfTheDenominator()
	{
		// a row that never recorded damage must not read as "dealt none" - that would
		// drag the share down and invent a contested kill out of missing data.
		final NpcStat stat = new NpcStat(3033, "Goblin");
		stat.record(Confidence.INFERRED, WHEN, 0, 0);

		Assert.assertEquals(1, stat.inferred);
		Assert.assertEquals("no damage recorded, no denominator", 0, stat.killsWithDamage);
		Assert.assertEquals(0L, stat.myDamageTotal);
	}

	@Test
	public void theOldTwoArgumentRecordStillWorksAndRecordsNoDamage()
	{
		final NpcStat stat = new NpcStat(3033, "Goblin");
		stat.record(Confidence.UNCONTESTED, WHEN);

		Assert.assertEquals(1, stat.uncontested);
		Assert.assertEquals(0, stat.killsWithDamage);
	}

	@Test
	public void theDayTallyCarriesItsOwnShare()
	{
		// a week or month window has to answer the same question the all-time row does,
		// so the per-day tally needs the numbers too.
		final NpcStat stat = new NpcStat(970, "Dagannoth");
		stat.record(Confidence.UNCONTESTED, WHEN, 73, 0);
		stat.record(Confidence.AMBIGUOUS, WHEN, 30, 41);

		final NpcStat.DayTally day = stat.dayOf(WHEN);
		Assert.assertEquals(103L, day.myDamage);
		Assert.assertEquals(41L, day.othersDamage);
		Assert.assertEquals(2, day.killsWithDamage);
		Assert.assertEquals(2, day.total());
	}

	@Test
	public void othersDamageAloneStillCountsAsAKillWeSaw()
	{
		// we opened a record, so we witnessed it; our share is simply zero. that is a
		// real data point about a contested kill, not missing data.
		final NpcStat stat = new NpcStat(2205, "Corporeal Beast");
		stat.record(Confidence.AMBIGUOUS, WHEN, 0, 2000);

		Assert.assertEquals(1, stat.killsWithDamage);
		Assert.assertEquals(0L, stat.myDamageTotal);
		Assert.assertEquals(2000L, stat.othersDamageTotal);
	}
}
