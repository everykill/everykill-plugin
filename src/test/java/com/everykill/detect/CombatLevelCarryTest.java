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
 * Combat level must survive a kill that didn't know it.
 *
 * Seen live 2026-08-24: the panel showed "Dagannoth" and "Ankou" with no level while
 * every other row had one. {@code NPC.getCombatLevel()} returns 0 when the composition
 * isn't loaded at the moment we ask, and both the state machine and the ledger were
 * assigning that straight over a good value.
 *
 * It matters beyond looking untidy — the display rollup keys on (name, combat level),
 * so a blanked level silently merges Dagannoth Rex, Prime and Supreme into one row.
 */
public class CombatLevelCarryTest
{
	private KillStateMachine machine;
	private List<KillRecord> emitted;

	@Before
	public void setUp()
	{
		machine = new KillStateMachine();
		emitted = new ArrayList<>();
	}

	private void hit(int amount, int level, int tick)
	{
		machine.damage(1, 2881, "Dagannoth Supreme", level, 11589, amount, true, tick);
	}

	private KillRecord killIt(int tick)
	{
		machine.death(1, tick, true, emitted::add);
		machine.despawn(1, true, tick + 1, emitted::add);
		machine.tick(tick + 2, emitted::add);
		return emitted.get(emitted.size() - 1);
	}

	@Test
	public void aTransformReportingZeroDoesNotBlankTheLevel()
	{
		// the case that produced the bug. composition() fires on every phase change and
		// the new form's composition often isn't loaded yet.
		hit(30, 125, 100);
		machine.composition(1, 2882, "Dagannoth Supreme", 0, 101);

		Assert.assertEquals(125, killIt(102).combatLevel);
	}

	@Test
	public void aTransformWithARealLevelStillUpdatesIt()
	{
		// the guard is against 0, not against change. nazastarool's forms genuinely
		// differ and the kill belongs to the last one.
		hit(30, 85, 100);
		machine.composition(1, 6399, "Nazastarool", 96, 101);

		Assert.assertEquals(96, killIt(102).combatLevel);
	}

	@Test
	public void aLevelIsStillZeroWhenItWasNeverKnown()
	{
		// honest rather than invented. if nothing ever reported a level, the record
		// says so and the panel omits it - which is what "Dagannoth" with no level was
		// telling us all along.
		hit(30, 0, 100);

		Assert.assertEquals(0, killIt(101).combatLevel);
	}

	@Test
	public void theFirstHitsLevelSurvivesLaterZeroHits()
	{
		hit(10, 125, 100);
		hit(10, 0, 101);
		hit(10, 0, 102);

		Assert.assertEquals(125, killIt(103).combatLevel);
	}
}
