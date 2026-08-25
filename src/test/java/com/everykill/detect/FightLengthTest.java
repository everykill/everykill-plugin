/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.detect;

import com.everykill.model.KillRecord;
import com.everykill.model.NpcStat;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Fight length: measured from OUR first damage, not the spawn.
 */
public class FightLengthTest
{
	private KillStateMachine machine;
	private List<KillRecord> out;

	@Before
	public void setUp()
	{
		machine = new KillStateMachine();
		out = new ArrayList<>();
	}

	private void flush(int tick)
	{
		machine.tick(tick, out::add);
	}

	@Test
	public void aFightIsTimedFromTheFirstHitNotTheFirstSighting()
	{
		// seen on tick 100, not hit until 110, dead on 120. the fight was 10 ticks,
		// not 20 - the first ten were spent walking over.
		machine.composition(1, 7271, "Cyclops", 56, 100);
		machine.damage(1, 7271, "Cyclops", 56, 0, 5, true, 110);
		machine.damage(1, 7271, "Cyclops", 56, 0, 9, true, 120);
		machine.despawn(1, true, 120, k -> { });
		flush(121);

		Assert.assertEquals(1, out.size());
		Assert.assertEquals(10, out.get(0).fightTicks);
	}

	@Test
	public void aKillWeNeverDamagedIsNotOurKillAtAll()
	{
		// this is stronger than "no fight length": resolve() drops anything with
		// myDamage <= 0, so a monster someone else killed never reaches the ledger.
		// the unmeasured-fight case can only happen to a kill that IS ours, which is
		// why fastestTicks still has to ignore zeroes.
		machine.composition(1, 7271, "Cyclops", 56, 100);
		machine.damage(1, 7271, "Cyclops", 56, 0, 7, false, 101);
		machine.despawn(1, true, 105, k -> { });
		flush(106);

		Assert.assertTrue("someone else's kill is not ours", out.isEmpty());
	}

	@Test
	public void aOneTickKillIsOneTickNotZero()
	{
		// hit and died on the same tick. that's a real fight, so it must not collapse
		// into the "unmeasured" zero.
		machine.composition(1, 2856, "Giant rat", 3, 50);
		machine.damage(1, 2856, "Giant rat", 3, 0, 12, true, 50);
		machine.despawn(1, true, 50, k -> { });
		flush(51);

		Assert.assertEquals(1, out.size());
		Assert.assertEquals(1, out.get(0).fightTicks);
	}

	@Test
	public void aSplashStartsTheFight()
	{
		// a zero splat is still a swing - attacksCount counts it, so the clock starts
		// there too. otherwise a fight that opens with two splashes reads as shorter
		// than it was.
		machine.composition(1, 7271, "Cyclops", 56, 200);
		machine.damage(1, 7271, "Cyclops", 56, 0, 0, true, 202);
		machine.damage(1, 7271, "Cyclops", 56, 0, 14, true, 208);
		machine.despawn(1, true, 208, k -> { });
		flush(209);

		Assert.assertEquals(6, out.get(0).fightTicks);
	}

	@Test
	public void theStatKeepsTheFastestAndIgnoresUnmeasuredKills()
	{
		final NpcStat stat = new NpcStat(7271, "Cyclops");
		stat.recordFight(14);
		stat.recordFight(9);
		stat.recordFight(22);
		Assert.assertEquals(9, stat.fastestTicks);

		// a 0 must not win the minimum - it means we never fought it.
		stat.recordFight(0);
		Assert.assertEquals(9, stat.fastestTicks);
	}

	@Test
	public void aStatWithNoMeasuredFightsStaysZero()
	{
		final NpcStat stat = new NpcStat(2856, "Giant rat");
		stat.recordFight(0);
		Assert.assertEquals(0, stat.fastestTicks);
	}
}
