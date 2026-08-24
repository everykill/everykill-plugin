/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.detect;

import com.everykill.model.Confidence;
import com.everykill.model.KillRecord;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * A zero-damage hitsplat from another player.
 *
 * Ironman rule, wiki 2026-08-24: "No loot will drop whatsoever if another player has
 * attacked that monster. For some monsters, even zero points of damage dealt by another
 * player will prevent the Ironman from getting any loot."
 *
 * So a splash or a block from a passer-by is a real event with real consequences, and
 * the ironman drop-rate filter is being built on othersDamage > 0.
 */
public class ForeignSplashTest
{
	private static final int KEY = 1;
	private static final int BLOODVELD = 484;

	private KillStateMachine machine;
	private List<KillRecord> emitted;

	@Before
	public void setUp()
	{
		machine = new KillStateMachine();
		emitted = new ArrayList<>();
	}

	private void ourHit(int amount, int tick)
	{
		machine.damage(KEY, BLOODVELD, "Bloodveld", 76, 100, amount, true, tick);
	}

	private void theirHit(int amount, int tick)
	{
		machine.damage(KEY, BLOODVELD, "Bloodveld", 76, 100, amount, false, tick);
	}

	@Test
	public void realForeignDamageStillMarksTheKillContested()
	{
		// the control. if this ever fails the whole grade is broken, not just splashes.
		ourHit(20, 100);
		theirHit(5, 101);
		machine.despawn(KEY, true, 103, emitted::add);

		Assert.assertEquals(1, emitted.size());
		Assert.assertEquals(Confidence.AMBIGUOUS, emitted.get(0).grade);
		Assert.assertEquals(5, emitted.get(0).othersDamage);
	}

	@Test
	public void aForeignSplashIsNotInvisible()
	{
		// somebody splashes our bloodveld. amount is zero, so othersDamage += 0 does
		// nothing and the kill reads as though we were alone with it.
		//
		// for a main that is arguably fine - they contributed nothing. for an ironman
		// the game has already voided the drop, and a filter keyed on othersDamage > 0
		// would count this kill as an eligible dry kill. every published ironman rate
		// then reads too rare, and nothing anywhere errors.
		ourHit(20, 100);
		theirHit(0, 101);
		machine.despawn(KEY, true, 103, emitted::add);

		Assert.assertEquals(1, emitted.size());
		final KillRecord kill = emitted.get(0);

		Assert.assertEquals("a foreign attack landed, so this is not a clean solo kill",
			Confidence.AMBIGUOUS, kill.grade);
	}
}
