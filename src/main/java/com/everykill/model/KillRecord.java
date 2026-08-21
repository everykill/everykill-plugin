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

	/**
	 * Our own hitsplats, <b>zero-damage ones included</b> — blocks and splashes.
	 * Attempts are the denominator for accuracy and can't be reconstructed later, so
	 * they get recorded at the kill or not at all.
	 *
	 * <p><b>Ours only.</b> Count the NPC's attacks instead and it becomes an attack
	 * counter, which Jagex prohibits by name. Read {@code KillDetector}'s compliance
	 * note before touching this.
	 */
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

	/**
	 * Player damage this fight, ours and everyone else's.
	 *
	 * <p><b>Since we engaged. Not the NPC's lifetime.</b> The record opens on the first
	 * hitsplat we witness, so anything it took before we showed up simply isn't in
	 * here. Do not compare this to the NPC's max HP and conclude we did 100% of the
	 * damage — all it can prove is that nobody else hit it *after we arrived*.
	 */
	public int totalDamage()
	{
		return myDamage + othersDamage;
	}
}
