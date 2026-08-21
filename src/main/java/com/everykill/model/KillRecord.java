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

	public final Confidence grade;

	/** How the death was learned about. Diagnostic; the grade is the judgement. */
	public final DeathSignal signal;

	/** Damage we dealt, summed from our own hitsplats. */
	public final int myDamage;

	/** Damage other players dealt. Non-zero forces {@link Confidence#AMBIGUOUS}. */
	public final int othersDamage;

	public final long timestampMillis;

	public KillRecord(String eventId, int npcId, String npcName, int combatLevel,
		Confidence grade, DeathSignal signal, int myDamage, int othersDamage, long timestampMillis)
	{
		this.eventId = eventId;
		this.npcId = npcId;
		this.npcName = npcName;
		this.combatLevel = combatLevel;
		this.grade = grade;
		this.signal = signal;
		this.myDamage = myDamage;
		this.othersDamage = othersDamage;
		this.timestampMillis = timestampMillis;
	}
}
