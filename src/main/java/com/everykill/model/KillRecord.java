/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

import java.util.List;

import java.util.Collections;

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

	/**
	 * World types active when this kill happened, lowercase.
	 *
	 * <p>Empty means a plain free world; that is different from never having been
	 * told, which the server stores as null. Read at kill time rather than at login,
	 * because a world hop mid-session would otherwise tag new kills with the old
	 * world's types.
	 *
	 * <p>The server rejects kills from worlds on a separate or temporary save, so this
	 * is what stops a Deadman grind landing on a shared board.
	 */
	public final List<String> worldTypes;

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

	/**
	 * Ticks from our first damage to the kill resolving, or 0 when unknown.
	 *
	 * <p>Ticks rather than millis because the game runs on them - a 0.6s tick is the
	 * real resolution, and wall-clock adds jitter that isn't in the fight. 0 means we
	 * never damaged it, so there is no fight to time.
	 */
	public final int fightTicks;

	/**
	 * What the server said this kill dropped. Empty until loot resolves on the tick
	 * boundary, and empty forever for a kill that never got a loot event — see
	 * {@link #lootConfidence} before reading anything into that.
	 */
	public final List<Drop> drops;

	/** How much the {@link #drops} list can be trusted. Never null. */
	public final LootConfidence lootConfidence;

	public KillRecord(String eventId, int npcId, String npcName, int combatLevel, int regionId,
		Confidence grade, DeathSignal signal, int myDamage, int othersDamage,
		int attacksCount, int hitsCount, int maxHit, long timestampMillis)
	{
		this(eventId, npcId, npcName, combatLevel, regionId, grade, signal, myDamage,
			othersDamage, attacksCount, hitsCount, maxHit, timestampMillis,
			Collections.emptyList(), LootConfidence.NONE, 0, Collections.emptyList());
	}

	public KillRecord(String eventId, int npcId, String npcName, int combatLevel, int regionId,
		Confidence grade, DeathSignal signal, int myDamage, int othersDamage,
		int attacksCount, int hitsCount, int maxHit, long timestampMillis,
		List<Drop> drops, LootConfidence lootConfidence, int fightTicks,
		List<String> worldTypes)
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
		this.fightTicks = fightTicks;
		this.drops = Collections.unmodifiableList(drops);
		this.lootConfidence = lootConfidence;
		this.worldTypes = worldTypes == null
			? Collections.emptyList() : Collections.unmodifiableList(worldTypes);
	}

	/**
	 * The same kill, stamped with the world it happened on.
	 *
	 * <p>Applied in the adapter rather than built in, because {@code KillStateMachine}
	 * has no client access — that split is why the detection rules are testable.
	 */
	public KillRecord withWorldTypes(List<String> types)
	{
		return new KillRecord(eventId, npcId, npcName, combatLevel, regionId, grade, signal,
			myDamage, othersDamage, attacksCount, hitsCount, maxHit, timestampMillis,
			drops, lootConfidence, fightTicks, types);
	}

	/**
	 * The same kill with its loot attached.
	 *
	 * <p>A copy rather than a setter because everything else on this class is final and
	 * a record that can change after it's been handed out is how two readers end up
	 * disagreeing about the same kill.
	 */
	public KillRecord withLoot(List<Drop> drops, LootConfidence lootConfidence)
	{
		return new KillRecord(eventId, npcId, npcName, combatLevel, regionId, grade, signal,
			myDamage, othersDamage, attacksCount, hitsCount, maxHit, timestampMillis,
			drops, lootConfidence, fightTicks, worldTypes);
	}

	// ours + everyone else's, but only since we engaged - the record opens on the
	// first hitsplat we see. don't compare this to max hp and call it 100%, all it
	// proves is nobody else hit it after we turned up.
	public int totalDamage()
	{
		return myDamage + othersDamage;
	}
}
