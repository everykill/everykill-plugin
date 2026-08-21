/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

/**
 * All-time local totals for one NPC.
 *
 * Grades stay as separate counts rather than one total: collapsing them here would
 * make the website's confidence lens impossible.
 */
public class NpcStat
{
	public int npcId;
	public String name;

	public int exact;
	public int inferred;
	public int ambiguous;

	/** Measured from the client, allocated by damage share. See XpAttributor. */
	public long xp;

	public long firstKillMillis;
	public long lastKillMillis;

	public NpcStat()
	{
	}

	public NpcStat(int npcId, String name)
	{
		this.npcId = npcId;
		this.name = name;
	}

	public void record(Confidence grade, long whenMillis)
	{
		switch (grade)
		{
			case EXACT:
				exact++;
				break;
			case INFERRED:
				inferred++;
				break;
			case AMBIGUOUS:
				ambiguous++;
				break;
		}

		if (firstKillMillis == 0L)
		{
			firstKillMillis = whenMillis;
		}
		lastKillMillis = whenMillis;
	}

	public int total()
	{
		return exact + inferred + ambiguous;
	}
}
