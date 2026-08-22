/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.detect;

import com.everykill.model.Confidence;
import com.everykill.model.DeathSignal;
import com.everykill.model.KillRecord;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * The kill-detection rules, as executable statements.
 *
 * Every test here corresponds to a failure mode named in the project's design docs.
 * The ones that matter most are the negative cases — a tracker that over-counts is
 * worse than one that under-counts, because the error is invisible and it inflates
 * every downstream figure at once.
 */
public class KillStateMachineTest
{
	private KillStateMachine machine;
	private List<KillRecord> emitted;

	@Before
	public void setUp()
	{
		machine = new KillStateMachine();
		emitted = new ArrayList<>();
	}

	private void hit(int index, int amount, boolean mine, int tick)
	{
		machine.damage(index, 415, "Abyssal demon", 124, 12363, amount, mine, tick);
	}

	// ------------------------------------------------------------------
	// The happy path
	// ------------------------------------------------------------------

	@Test
	public void ourDamagePlusObservedDeathIsUncontested()
	{
		hit(1, 20, true, 0);
		hit(1, 18, true, 2);
		machine.death(1, 3, emitted::add);
		machine.despawn(1, true, 4, emitted::add);

		Assert.assertEquals(1, emitted.size());
		Assert.assertEquals(Confidence.UNCONTESTED, emitted.get(0).grade);
		Assert.assertEquals(38, emitted.get(0).myDamage);
	}

	@Test
	public void despawnWhileDeadIsInferred()
	{
		hit(1, 30, true, 0);
		machine.despawn(1, true, 2, emitted::add);

		Assert.assertEquals(1, emitted.size());
		Assert.assertEquals(Confidence.INFERRED, emitted.get(0).grade);
	}

	// ------------------------------------------------------------------
	// The negative cases — these are the ones that protect the dataset
	// ------------------------------------------------------------------

	@Test
	public void aStrangersKillIsNotOurs()
	{
		hit(1, 40, false, 0);
		machine.death(1, 1, emitted::add);

		Assert.assertTrue("a kill we did no damage to must never be recorded", emitted.isEmpty());
	}

	@Test
	public void deathWithNoDamageRecordIsIgnored()
	{
		machine.death(99, 1, emitted::add);
		Assert.assertTrue(emitted.isEmpty());
	}

	@Test
	public void contestedKillIsAmbiguous()
	{
		hit(1, 25, true, 0);
		hit(1, 12, false, 1);
		machine.death(1, 2, emitted::add);
		machine.despawn(1, true, 3, emitted::add);

		Assert.assertEquals(1, emitted.size());
		Assert.assertEquals(Confidence.AMBIGUOUS, emitted.get(0).grade);
		Assert.assertEquals(12, emitted.get(0).othersDamage);
	}

	@Test
	public void contestedDowngradesEvenWhenDeathWasObserved()
	{
		hit(1, 100, true, 0);
		hit(1, 1, false, 1);
		machine.death(1, 2, emitted::add);
		machine.despawn(1, true, 3, emitted::add);

		Assert.assertEquals("one splat from someone else is enough to contest it",
			Confidence.AMBIGUOUS, emitted.get(0).grade);
	}

	@Test
	public void deathThenDespawnScoresOnce()
	{
		hit(1, 30, true, 0);
		machine.death(1, 1, emitted::add);
		machine.despawn(1, true, 2, emitted::add);

		Assert.assertEquals("ActorDeath followed by NpcDespawned is one corpse, not two",
			1, emitted.size());
	}

	@Test
	public void phaseChangeCarriesForwardAndEmitsNothing()
	{
		hit(1, 40, true, 0);
		machine.composition(1, 416, "Abyssal demon (phase 2)", 124, 1);
		Assert.assertTrue("a phase transition is not a kill", emitted.isEmpty());

		hit(1, 35, true, 2);
		machine.composition(1, 417, "Abyssal demon (phase 3)", 124, 3);
		Assert.assertTrue(emitted.isEmpty());

		machine.death(1, 4, emitted::add);
		machine.despawn(1, true, 5, emitted::add);

		Assert.assertEquals("three phases, one kill", 1, emitted.size());
		Assert.assertEquals("damage across phases accumulates", 75, emitted.get(0).myDamage);
		Assert.assertEquals("the kill is attributed to the final form", 417, emitted.get(0).npcId);
	}

	@Test
	public void despawnWithoutDeathFlagIsDiscarded()
	{
		hit(1, 40, true, 0);
		machine.despawn(1, false, 1, emitted::add);

		Assert.assertTrue("an NPC that walked away is not a kill", emitted.isEmpty());
		Assert.assertEquals("and its record is dropped", 0, machine.trackedCount());
	}

	// ------------------------------------------------------------------
	// Transform deaths — gargoyles, rockslugs, lizards, zygomites
	// ------------------------------------------------------------------

	@Test
	public void aFinishingActionMakesATransformDeathCountable()
	{
		// A gargoyle: damaged by us, then finished with a rock hammer. It leaves the
		// scene without ever being flagged dead. Without this path an entire slayer
		// task counts as zero.
		hit(7, 55, true, 0);
		machine.finishingAction(7, 1);
		machine.despawn(7, false, 2, emitted::add);

		Assert.assertEquals(1, emitted.size());
		Assert.assertEquals(Confidence.INFERRED, emitted.get(0).grade);
		Assert.assertEquals(DeathSignal.TRANSFORM_FINISH, emitted.get(0).signal);
	}

	@Test
	public void aFinishingActionOnSomethingWeNeverHitIsNotAKill()
	{
		machine.finishingAction(7, 1);
		machine.despawn(7, false, 2, emitted::add);

		Assert.assertTrue("no damage of ours, no kill — the rule is unchanged", emitted.isEmpty());
	}

	@Test
	public void aStaleFinishingActionDoesNotClaimADespawn()
	{
		hit(7, 55, true, 0);
		machine.finishingAction(7, 1);
		machine.despawn(7, false, 1 + KillStateMachine.FINISH_WINDOW_TICKS + 1, emitted::add);

		Assert.assertTrue("an NPC that wandered off much later is not a kill", emitted.isEmpty());
	}

	@Test
	public void aFinishingActionOnADifferentNpcDoesNotTransfer()
	{
		hit(7, 55, true, 0);
		machine.finishingAction(8, 1);
		machine.despawn(7, false, 2, emitted::add);

		Assert.assertTrue("the evidence is per-NPC, not ambient", emitted.isEmpty());
	}

	@Test
	public void despawnWithoutAnyFinishingActionIsStillDiscarded()
	{
		hit(7, 55, true, 0);
		machine.despawn(7, false, 1, emitted::add);

		Assert.assertTrue(emitted.isEmpty());
	}

	@Test
	public void observedDeathIsStillGradedAboveATransformFinish()
	{
		hit(1, 30, true, 0);
		machine.death(1, 1, emitted::add);
		machine.despawn(1, true, 2, emitted::add);

		Assert.assertEquals(DeathSignal.OBSERVED, emitted.get(0).signal);
		Assert.assertEquals(Confidence.UNCONTESTED, emitted.get(0).grade);
	}

	@Test
	public void aSixTickDeathAnimationStillGradesObserved()
	{
		// Measured in a client 2026-08-21: lesser demon, ActorDeath to despawn is 6
		// ticks, five out of five. DEATH_CONFIRM_TICKS was 5, so every one of 31 clean
		// solo kills missed OBSERVED by a single tick and got written down INFERRED.
		//
		// No unit test could have caught that - the logic was fine, the constant just
		// didn't match the game. This is here so shrinking it below a real death
		// animation breaks the build instead of quietly costing someone their grade.
		hit(1, 30, true, 0);
		machine.death(1, 10, emitted::add);
		machine.despawn(1, true, 16, emitted::add);

		Assert.assertEquals(DeathSignal.OBSERVED, emitted.get(0).signal);
		Assert.assertEquals(Confidence.UNCONTESTED, emitted.get(0).grade);
	}

	@Test
	public void aDeathSignalThatNeverDespawnsIsNotAKill()
	{
		// A rockslug at 0 hp is still standing there waiting for salt. ActorDeath
		// fires anyway. Measured 2026-08-21: eight slugs, seven counted off this lie,
		// one of them twice.
		hit(1, 27, true, 0);
		machine.death(1, 1, emitted::add);

		Assert.assertTrue("a death signal on its own proves nothing", emitted.isEmpty());

		machine.tick(1 + KillStateMachine.DEATH_CONFIRM_TICKS + 1);

		Assert.assertTrue("and it stays nothing once the corpse fails to show",
			emitted.isEmpty());
	}

	@Test
	public void aLyingDeathSignalDoesNotDoubleCountTheRealOne()
	{
		// The kc=3 / kc=4 pair: emitted once on the lie, then again nine seconds later
		// on the real despawn, off a single point of damage. One emission point now.
		hit(1, 27, true, 0);
		machine.death(1, 1, emitted::add);
		machine.tick(1 + KillStateMachine.DEATH_CONFIRM_TICKS + 1);

		final int later = 20;
		hit(1, 1, true, later);
		machine.finishingAction(1, later + 1);
		machine.despawn(1, false, later + 2, emitted::add);

		Assert.assertEquals("one slug, one kill", 1, emitted.size());
		Assert.assertEquals(DeathSignal.TRANSFORM_FINISH, emitted.get(0).signal);
	}

	@Test
	public void anAbandonedMonsterAtZeroHpIsNotAKill()
	{
		// Run in play 2026-08-21: hit a rockslug to 0 hp, don't salt it, walk away. It
		// despawns when it leaves the scene, isDead() is true because the health ratio
		// is zero, and we counted a monster that is still standing there.
		hit(1, 27, true, 0);
		machine.death(1, 1, emitted::add);
		machine.tick(1 + KillStateMachine.DEATH_CONFIRM_TICKS + 1);

		// walked out of range some time later
		machine.despawn(1, true, 40, emitted::add);

		Assert.assertTrue("it lied about dying once; isDead() is the same lie",
			emitted.isEmpty());
	}

	@Test
	public void anHonestDespawnWhileDeadStillCounts()
	{
		// Nothing revoked here - no ActorDeath ever fired, it just left flagged dead.
		// The guard must not swallow this one.
		hit(1, 30, true, 0);
		machine.despawn(1, true, 2, emitted::add);

		Assert.assertEquals(1, emitted.size());
		Assert.assertEquals(DeathSignal.DESPAWN_WHILE_DEAD, emitted.get(0).signal);
	}

	@Test
	public void anItemFinishBeatsALyingDeathSignal()
	{
		// Salt after the health bar empties. ActorDeath already fired and lied; the
		// item is what actually finished it, so claiming OBSERVED would be claiming we
		// watched something we deduced.
		hit(1, 27, true, 0);
		machine.death(1, 1, emitted::add);
		machine.finishingAction(1, 2);
		machine.despawn(1, false, 3, emitted::add);

		Assert.assertEquals(1, emitted.size());
		Assert.assertEquals(DeathSignal.TRANSFORM_FINISH, emitted.get(0).signal);
		Assert.assertEquals(Confidence.INFERRED, emitted.get(0).grade);
	}

	// ------------------------------------------------------------------
	// Hygiene
	// ------------------------------------------------------------------

	@Test
	public void staleRecordsArePurged()
	{
		hit(1, 10, true, 0);
		Assert.assertEquals(1, machine.trackedCount());

		machine.tick(KillStateMachine.STALE_TICKS + 1);

		Assert.assertEquals("an NPC we stopped fighting must not leak", 0, machine.trackedCount());
	}

	@Test
	public void staleRecordCannotLaterScore()
	{
		hit(1, 10, true, 0);
		machine.tick(KillStateMachine.STALE_TICKS + 1);
		machine.death(1, KillStateMachine.STALE_TICKS + 2, emitted::add);

		Assert.assertTrue(emitted.isEmpty());
	}

	@Test
	public void twoNpcsAreCountedIndependently()
	{
		hit(1, 30, true, 0);
		hit(2, 30, true, 0);
		machine.death(1, 1, emitted::add);
		machine.despawn(1, true, 2, emitted::add);
		machine.death(2, 1, emitted::add);
		machine.despawn(2, true, 2, emitted::add);

		Assert.assertEquals(2, emitted.size());
	}

	@Test
	public void anIndexReusedAfterTheSuppressionWindowScoresAgain()
	{
		hit(1, 30, true, 0);
		machine.death(1, 1, emitted::add);
		machine.despawn(1, true, 2, emitted::add);

		final int later = KillStateMachine.EMITTED_TICKS + 5;
		hit(1, 30, true, later);
		machine.death(1, later + 1, emitted::add);
		machine.despawn(1, true, later + 2, emitted::add);

		Assert.assertEquals("NPC indexes are recycled; a later kill is a real one",
			2, emitted.size());
	}

	@Test
	public void resetClearsEverything()
	{
		hit(1, 10, true, 0);
		machine.reset();
		machine.death(1, 1, emitted::add);

		Assert.assertTrue(emitted.isEmpty());
		Assert.assertEquals(0, machine.trackedCount());
	}
}
