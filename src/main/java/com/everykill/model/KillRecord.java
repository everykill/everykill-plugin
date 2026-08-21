/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

/**
 * One graded kill, as it leaves the detector.
 *
 * Field names mirror the server's shared event contract so the two cannot drift.
 * Nothing uploads yet; fixing the shape now makes P3 a transport change.
 */
public class KillRecord
{
	/** Client-generated, idempotent. The server dedupes on (player, eventId). */
	public final String eventId;

	public final int npcId;
	public final String npcName;
	public final int combatLevel;

	/** Where we engaged, from the first hitsplat. -1 when the location was unreadable. */
	public final int regionId;

	public final Confidence grade;

	/** How the death was learned about. Diagnostic; the grade is the judgement. */
	public final DeathSignal signal;

	/** Damage we dealt, summed from our own hitsplats. */
	public final int myDamage;

	/** Damage other players dealt. Non-zero forces {@link Confidence#AMBIGUOUS}. */
	public final int othersDamage;

	// our hitsplats, blocks and splashes included. attempts are the accuracy
	// denominator and can't be rebuilt later, so it's now or never.
	// ours only - counting the npc's attacks makes this an attack counter, which
	// jagex prohibits by name. see KillDetector's compliance note.
	public final int attacksCount;

	/** Our own hitsplats that dealt more than zero. See {@link #attacksCount}. */
	public final int hitsCount;

	/** Our largest single hitsplat this fight. See {@link #attacksCount}. */
	public final int maxHit;

	public final long timestampMillis;

	public KillRecord(String eventId, int npcId, String npcName, int combatLevel, int regionId,
		Confidence grade, DeathSignal signal, int myDamage, int othersDamage,
		int attacksCount, int hitsCount, int maxHit, long timestampMillis)
	{
		this.eventId = eventId;
		this.npcId = npcId;
		this.npcName = npcName;
		this.combatLevel = combatLevel;
		this.regionId = regionId;
		this.grade = grade;
		this.signal = signal;
		this.myDamage = myDamage;
		this.othersDamage = othersDamage;
		this.attacksCount = attacksCount;
		this.hitsCount = hitsCount;
		this.maxHit = maxHit;
		this.timestampMillis = timestampMillis;
	}

	// ours + everyone else's, but only since we engaged - the record opens on the
	// first hitsplat we see. don't compare this to max hp and call it 100%, all it
	// proves is nobody else hit it after we turned up.
	public int totalDamage()
	{
		return myDamage + othersDamage;
	}
}
