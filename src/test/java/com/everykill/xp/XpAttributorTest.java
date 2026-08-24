/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.xp;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Experience allocation, as executable statements.
 *
 * The rule under test throughout: <b>XP is measured, damage only allocates it.</b>
 * The failure modes that matter are inventing experience, losing experience in a
 * split, and silently assigning experience that belongs to nothing we tracked.
 */
public class XpAttributorTest
{
	private static final int ABYSSAL_DEMON = 415;
	private static final int BLOODVELD = 484;

	private XpAttributor xp;

	@Before
	public void setUp()
	{
		xp = new XpAttributor();
		xp.prime(CombatSkill.ATTACK, 1_000_000);
		xp.prime(CombatSkill.HITPOINTS, 500_000);
	}

	@Test
	public void singleMonsterGetsAllTheExperience()
	{
		xp.damage(ABYSSAL_DEMON, 10, 100);
		xp.xpChanged(CombatSkill.ATTACK, 1_000_040, 100);

		Assert.assertEquals(40L, xp.xpFor(ABYSSAL_DEMON));
		Assert.assertEquals(0L, xp.getUnallocatedXp());
	}

	@Test
	public void experienceSplitsByDamageShare()
	{
		xp.damage(ABYSSAL_DEMON, 30, 100);
		xp.damage(BLOODVELD, 10, 100);
		xp.xpChanged(CombatSkill.ATTACK, 1_000_160, 100);

		Assert.assertEquals("three quarters of the damage, three quarters of the xp",
			120L, xp.xpFor(ABYSSAL_DEMON));
		Assert.assertEquals(40L, xp.xpFor(BLOODVELD));
	}

	@Test
	public void aSplitNeverInventsOrLosesExperience()
	{
		// 7 xp across 3 monsters does not divide evenly. The parts must still sum
		// to exactly 7 — a naive per-share round would leak or fabricate.
		xp.damage(1, 1, 100);
		xp.damage(2, 1, 100);
		xp.damage(3, 1, 100);
		xp.xpChanged(CombatSkill.ATTACK, 1_000_007, 100);

		final long sum = xp.xpFor(1) + xp.xpFor(2) + xp.xpFor(3);
		Assert.assertEquals("allocation must conserve the total", 7L, sum);
		Assert.assertEquals(0L, xp.getUnallocatedXp());
	}

	@Test
	public void experienceWithNoDamageIsNotForcedOntoAMonster()
	{
		// Cooking, thieving, an xp lamp, anything at all — if we have no damage on
		// record it must not be attributed to whatever was killed last.
		xp.xpChanged(CombatSkill.ATTACK, 1_050_000, 100);

		// held first, in case the hitsplat is still on its way. written off once the
		// window closes with nothing to explain it.
		xp.settle(100 + XpAttributor.SETTLE_TICKS + 1);

		Assert.assertEquals(0L, xp.xpFor(ABYSSAL_DEMON));
		Assert.assertEquals("it is reported, not buried", 50_000L, xp.getUnallocatedXp());
	}

	@Test
	public void experienceArrivingBeforeItsDamageStillLands()
	{
		// The real ordering, measured in the catacombs 2026-08-20: the client pays the
		// xp on one tick and the hitsplat turns up on the next. Reaching backwards for
		// a pool can never match this, which is why nothing allocated at all.
		xp.xpChanged(CombatSkill.ATTACK, 1_000_040, 100);
		Assert.assertEquals("nothing to match yet, so it waits", 0L, xp.xpFor(ABYSSAL_DEMON));

		xp.damage(ABYSSAL_DEMON, 10, 101);
		xp.settle(101);

		Assert.assertEquals(40L, xp.xpFor(ABYSSAL_DEMON));
		Assert.assertEquals(0L, xp.getUnallocatedXp());
	}

	@Test
	public void heldExperienceGoesToTheMonsterThatWasActuallyHit()
	{
		// Two monsters, one tick apart. The xp at 100 belongs to the demon hit at 101,
		// not the bloodveld hit at 99 - widening the window instead of looking forward
		// would pay the wrong one and never say so.
		xp.damage(BLOODVELD, 10, 99);
		xp.xpChanged(CombatSkill.ATTACK, 1_000_040, 99);

		xp.xpChanged(CombatSkill.ATTACK, 1_000_080, 100);
		xp.damage(ABYSSAL_DEMON, 10, 101);
		xp.settle(101);

		Assert.assertEquals(40L, xp.xpFor(BLOODVELD));
		Assert.assertEquals(40L, xp.xpFor(ABYSSAL_DEMON));
	}

	@Test
	public void experienceArrivingOneTickLateStillLands()
	{
		xp.damage(ABYSSAL_DEMON, 10, 100);
		xp.damage(BLOODVELD, 5, 101);
		xp.xpChanged(CombatSkill.ATTACK, 1_000_020, 101);

		Assert.assertEquals("current tick wins when it has damage", 20L, xp.xpFor(BLOODVELD));
	}

	@Test
	public void aBlockedHitDoesNotStrandThePreviousTicksExperience()
	{
		// RuneLite reports a block as ours with an amount of zero. If that were let
		// into the pool, tick 101 would have a total damage of zero and would swallow
		// the experience owed to tick 100's real hit.
		xp.damage(ABYSSAL_DEMON, 10, 100);
		xp.damage(ABYSSAL_DEMON, 0, 101);
		xp.xpChanged(CombatSkill.ATTACK, 1_000_040, 101);

		Assert.assertEquals(40L, xp.xpFor(ABYSSAL_DEMON));
		Assert.assertEquals(0L, xp.getUnallocatedXp());
	}

	@Test
	public void experienceFallsBackToThePreviousTickWhenNothingWasHitSince()
	{
		xp.damage(ABYSSAL_DEMON, 10, 100);
		xp.xpChanged(CombatSkill.ATTACK, 1_000_040, 101);

		Assert.assertEquals(40L, xp.xpFor(ABYSSAL_DEMON));
	}

	@Test
	public void staleDamageCannotClaimLaterExperience()
	{
		final int late = 100 + XpAttributor.SETTLE_TICKS + 5;

		xp.damage(ABYSSAL_DEMON, 10, 100);
		xp.xpChanged(CombatSkill.ATTACK, 1_000_040, late);
		xp.settle(late + XpAttributor.SETTLE_TICKS + 1);

		Assert.assertEquals(0L, xp.xpFor(ABYSSAL_DEMON));
		Assert.assertEquals(40L, xp.getUnallocatedXp());
	}

	@Test
	public void anUnprimedSkillAttributesNothing()
	{
		// Magic was never primed. Its first update is a total, not a gain — treating
		// it as a delta would credit one monster with the player's whole history.
		xp.damage(ABYSSAL_DEMON, 10, 100);
		final long attributed = xp.xpChanged(CombatSkill.MAGIC, 8_000_000, 100);

		Assert.assertEquals(0L, attributed);
		Assert.assertEquals(0L, xp.xpFor(ABYSSAL_DEMON));
	}

	@Test
	public void theSecondUpdateOfAPreviouslyUnprimedSkillWorks()
	{
		xp.damage(ABYSSAL_DEMON, 10, 100);
		xp.xpChanged(CombatSkill.MAGIC, 8_000_000, 100);

		xp.damage(ABYSSAL_DEMON, 10, 101);
		xp.xpChanged(CombatSkill.MAGIC, 8_000_020, 101);

		Assert.assertEquals(20L, xp.xpFor(ABYSSAL_DEMON));
	}

	@Test
	public void multipleSkillsAccumulateOntoTheSameMonster()
	{
		xp.damage(ABYSSAL_DEMON, 10, 100);
		xp.xpChanged(CombatSkill.ATTACK, 1_000_040, 100);
		xp.xpChanged(CombatSkill.HITPOINTS, 500_013, 100);

		Assert.assertEquals("attack plus hitpoints", 53L, xp.xpFor(ABYSSAL_DEMON));
	}

	@Test
	public void aDecreaseIsIgnored()
	{
		// Dying, or a stat drain, must never subtract from a monster's total.
		xp.damage(ABYSSAL_DEMON, 10, 100);
		xp.xpChanged(CombatSkill.ATTACK, 999_000, 100);

		Assert.assertEquals(0L, xp.xpFor(ABYSSAL_DEMON));
	}

	// ------------------------------------------------------------------
	// Zulrah, 2026-08-22. A snakeling banked 102 xp on a 1 hp kill.
	// ------------------------------------------------------------------

	private static final int ZULRAH = 2042;
	private static final int SNAKELING = 2045;

	@Test
	public void aChipHitOnAnAddDoesNotStealTheBossesExperience()
	{
		xp.prime(CombatSkill.RANGED, 1_000_000);

		// The shape that did it, straight off the log. Recoil pings a snakeling for 1
		// on tick 100. We hit Zulrah for 19 - but xp lands a tick BEFORE its hitsplat,
		// so the 76 arrives at 100 while Zulrah's damage doesn't show up until 101.
		//
		// allocateAt() checks its own tick first. Tick 100's pool isn't empty, it has
		// the snakeling's 1 damage in it, so the split succeeds there and never looks
		// forward to the damage that actually earned the xp.
		xp.damage(SNAKELING, 1, 100);
		xp.xpChanged(CombatSkill.RANGED, 1_000_076, 100);
		xp.damage(ZULRAH, 19, 101);
		xp.settle(101);

		Assert.assertEquals("19 damage earned it, 19 damage keeps it", 76L, xp.xpFor(ZULRAH));
		Assert.assertEquals("a 1 hp add cannot bank 76 xp", 0L, xp.xpFor(SNAKELING));
	}

	@Test
	public void anAddKeepsTheExperienceItActuallyEarned()
	{
		xp.prime(CombatSkill.RANGED, 1_000_000);

		// The other side of it, so a fix can't just deny adds their xp. Snakeling dies
		// on 100 and its own xp arrives with no boss damage anywhere near. It keeps it.
		xp.damage(SNAKELING, 1, 100);
		xp.xpChanged(CombatSkill.RANGED, 1_000_004, 100);
		xp.settle(102);

		Assert.assertEquals(4L, xp.xpFor(SNAKELING));
		Assert.assertEquals(0L, xp.getStrandedXp());
	}

	// ------------------------------------------------------------------
	// Waterbirth, 2026-08-24. 392 allocations across two venues and not one
	// of them had two monsters in the pool, cannon or not.
	// ------------------------------------------------------------------

	private static final int DAGANNOTH_A = 970;
	private static final int DAGANNOTH_B = 971;

	@Test
	public void twoMonstersHitOnOneTickSplitTheExperienceBetweenThem()
	{
		xp.prime(CombatSkill.RANGED, 1_000_000);

		// both hitsplats land before the xp does. this is the case split() was written
		// for - largest remainder, shares summing to the whole - and it works.
		xp.damage(DAGANNOTH_A, 10, 101);
		xp.damage(DAGANNOTH_B, 6, 101);
		xp.xpChanged(CombatSkill.RANGED, 1_000_064, 100);
		xp.settle(101);

		Assert.assertEquals(40L, xp.xpFor(DAGANNOTH_A));
		Assert.assertEquals(24L, xp.xpFor(DAGANNOTH_B));
	}

	@Test
	public void aPendingDropIsNotSwallowedByWhicheverHitsplatArrivesFirst()
	{
		xp.prime(CombatSkill.RANGED, 1_000_000);

		// same tick, same two monsters, same 64 xp. the only difference is that the xp
		// was already waiting - which it always is, it arrives a tick early - so
		// damage()'s settle() fires on the FIRST hitsplat against a pool holding only
		// that one monster. the second hitsplat finds the xp already spent.
		//
		// this is why a multi-npc pool has never been observed in play, and it's the
		// general form of the snakeling theft: first hitsplat processed takes the lot.
		xp.xpChanged(CombatSkill.RANGED, 1_000_064, 100);
		xp.damage(DAGANNOTH_A, 10, 101);
		xp.settle(101);
		xp.damage(DAGANNOTH_B, 6, 101);
		xp.settle(101);

		Assert.assertEquals("first hitsplat must not take the whole drop", 40L, xp.xpFor(DAGANNOTH_A));
		Assert.assertEquals("the other monster earned its share", 24L, xp.xpFor(DAGANNOTH_B));
	}

	@Test
	public void drainHandsOverAndClears()
	{
		xp.damage(ABYSSAL_DEMON, 10, 100);
		xp.xpChanged(CombatSkill.ATTACK, 1_000_040, 100);

		Assert.assertEquals(40L, (long) xp.drain().get(ABYSSAL_DEMON).get(CombatSkill.ATTACK));
		Assert.assertEquals("the buffer is empty after a drain", 0L, xp.xpFor(ABYSSAL_DEMON));
		Assert.assertTrue(xp.drain().isEmpty());
	}

	@Test
	public void resetClearsBaselinesToo()
	{
		xp.damage(ABYSSAL_DEMON, 10, 100);
		xp.reset();
		xp.damage(ABYSSAL_DEMON, 10, 100);
		final long attributed = xp.xpChanged(CombatSkill.ATTACK, 1_000_040, 100);

		Assert.assertEquals("after a reset the first update is a baseline again", 0L, attributed);
		Assert.assertTrue(!xp.isPrimed());
	}
}
