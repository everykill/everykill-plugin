/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.ui;

import com.everykill.model.Confidence;
import com.everykill.model.NpcStat;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * The panel's display rollup.
 *
 * Built from a real screenshot: four "Lesser demon (82)" rows at 41, 31, 30 and 17,
 * plus three separate "Giant rat" rows. The player killed 119 lesser demons and the
 * panel made them add it up.
 *
 * The ledger keeps raw npc_ids forever — {@code PROJECT.md} — so this is read-time only
 * and a wrong answer here costs a redraw, not data. Reached by reflection because it is
 * a private static detail of the panel, and making it public purely for a test would
 * invite something to depend on it.
 */
public class EverykillPanelRollupTest
{
	private static final long WHEN = 1_756_000_000_000L;

	@SuppressWarnings("unchecked")
	private static List<NpcStat> rollUp(List<NpcStat> in) throws Exception
	{
		final Method m = EverykillPanel.class.getDeclaredMethod("rollUp", List.class);
		m.setAccessible(true);
		return (List<NpcStat>) m.invoke(null, in);
	}

	private static NpcStat stat(int npcId, String name, int level, int kills)
	{
		final NpcStat s = new NpcStat(npcId, name);
		s.combatLevel = level;
		for (int i = 0; i < kills; i++)
		{
			s.record(Confidence.UNCONTESTED, WHEN);
		}
		return s;
	}

	@Test
	public void theFourLesserDemonRowsBecomeOne() throws Exception
	{
		final List<NpcStat> out = rollUp(Arrays.asList(
			stat(7247, "Lesser demon", 82, 41),
			stat(7248, "Lesser demon", 82, 31),
			stat(2005, "Lesser demon", 82, 30),
			stat(2006, "Lesser demon", 82, 17)));

		Assert.assertEquals(1, out.size());
		Assert.assertEquals(119, out.get(0).total());
	}

	@Test
	public void theMergedRowKeepsTheBiggestContributorsId() throws Exception
	{
		// expanding a merged row has to land on a monster the player actually killed.
		final List<NpcStat> out = rollUp(Arrays.asList(
			stat(2005, "Lesser demon", 82, 30),
			stat(7247, "Lesser demon", 82, 41)));

		Assert.assertEquals(7247, out.get(0).npcId);
	}

	@Test
	public void differentCombatLevelsStayApart() throws Exception
	{
		// a level 82 lesser demon and a level 87 one have different drop tables.
		// merging them would hide the distinction drop rates depend on.
		final List<NpcStat> out = rollUp(Arrays.asList(
			stat(7247, "Lesser demon", 82, 41),
			stat(7865, "Lesser demon", 87, 12)));

		Assert.assertEquals(2, out.size());
	}

	@Test
	public void differentMonstersStayApart() throws Exception
	{
		final List<NpcStat> out = rollUp(Arrays.asList(
			stat(7247, "Lesser demon", 82, 41),
			stat(2856, "Giant rat", 3, 11)));

		Assert.assertEquals(2, out.size());
	}

	@Test
	public void gradesXpAndDamageAreSummed() throws Exception
	{
		final NpcStat a = stat(2856, "Giant rat", 3, 5);
		a.xp = 100;
		a.record(Confidence.AMBIGUOUS, WHEN, 4, 1);

		final NpcStat b = stat(2864, "Giant rat", 3, 3);
		b.xp = 60;
		b.record(Confidence.INFERRED, WHEN, 5, 0);

		final NpcStat m = rollUp(Arrays.asList(a, b)).get(0);

		Assert.assertEquals(10, m.total());
		Assert.assertEquals(8, m.uncontested);
		Assert.assertEquals(1, m.ambiguous);
		Assert.assertEquals(1, m.inferred);
		Assert.assertEquals(160L, m.xp);
		Assert.assertEquals("damage share survives the merge", 9L, m.myDamageTotal);
		Assert.assertEquals(1L, m.othersDamageTotal);
		Assert.assertEquals(2, m.killsWithDamage);
	}

	@Test
	public void dayBucketsMergeSoWindowedViewsStillWork() throws Exception
	{
		// the Day/Wk/Mth tabs read totalSince(), which reads the day map. if the merge
		// dropped it those tabs would silently show fewer kills than All.
		//
		// recorded at NOW, not the fixed WHEN the other tests use: totalSince counts
		// backwards from today, so a fixed past timestamp falls outside every window
		// and this would pass for the wrong reason.
		final long now = System.currentTimeMillis();

		final NpcStat a = new NpcStat(2856, "Giant rat");
		a.combatLevel = 3;
		for (int i = 0; i < 5; i++)
		{
			a.record(Confidence.UNCONTESTED, now);
		}

		final NpcStat b = new NpcStat(2864, "Giant rat");
		b.combatLevel = 3;
		for (int i = 0; i < 3; i++)
		{
			b.record(Confidence.UNCONTESTED, now);
		}

		final NpcStat m = rollUp(Arrays.asList(a, b)).get(0);

		Assert.assertEquals(8, m.totalSince(1));
	}

	@Test
	public void theOriginalRowsAreNotMutated() throws Exception
	{
		// the ledger hands out its live objects. writing into them here would corrupt
		// stored counts on every repaint.
		final NpcStat a = stat(2856, "Giant rat", 3, 5);
		final NpcStat b = stat(2864, "Giant rat", 3, 3);

		rollUp(Arrays.asList(a, b));

		Assert.assertEquals("source row untouched", 5, a.total());
		Assert.assertEquals("source row untouched", 3, b.total());
	}

	@Test
	public void firstAndLastKillSpanTheWholeGroup() throws Exception
	{
		final NpcStat a = stat(2856, "Giant rat", 3, 1);
		a.firstKillMillis = 2000L;
		a.lastKillMillis = 3000L;

		final NpcStat b = stat(2864, "Giant rat", 3, 1);
		b.firstKillMillis = 1000L;
		b.lastKillMillis = 5000L;

		final NpcStat m = rollUp(Arrays.asList(a, b)).get(0);

		Assert.assertEquals(1000L, m.firstKillMillis);
		Assert.assertEquals(5000L, m.lastKillMillis);
	}

	@Test
	public void anEmptyLedgerIsNotAnError() throws Exception
	{
		Assert.assertTrue(rollUp(new ArrayList<>()).isEmpty());
	}
}
