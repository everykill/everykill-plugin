/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

// all-time totals for one npc. grades stay as separate counts - collapse them into
// one number here and the site can never break them apart again.
public class NpcStat
{
	public int npcId;
	public String name;

	public int exact;
	public int inferred;
	public int ambiguous;

	/** measured from the client, split by damage share. see XpAttributor */
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
