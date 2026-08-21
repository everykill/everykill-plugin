/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.detect;

import com.everykill.model.Confidence;
import com.everykill.model.DeathSignal;
import com.everykill.model.KillRecord;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * All the kill detection rules, in plain java with no client anywhere near it.
 * {@link KillDetector} does the translating. That split is why these are testable.
 *
 * <ol>
 *   <li>Kill = a death signal AND our own damage. One without the other gets binned.
 *       Overcounting is the thing to be scared of - it inflates everything downstream
 *       and looks completely fine while doing it</li>
 *   <li>Someone else's damage drops it to {@link Confidence#AMBIGUOUS}</li>
 *   <li>Saw it die = {@link Confidence#EXACT}. Worked it out = {@link Confidence#INFERRED}</li>
 *   <li>Composition change carries the damage over and emits nothing</li>
 *   <li>Despawn with no death flag is binned, unless we used an item on it within
 *       {@link #FINISH_WINDOW_TICKS} - that's a transform death</li>
 *   <li>One key can't emit twice inside {@link #EMITTED_TICKS}</li>
 * </ol>
 *
 * <p>Keys come from {@link KillDetector}, stable per actor and never reissued. Don't
 * "simplify" them back to getIndex() - it eats kills every time a slot gets recycled.
 */
public class KillStateMachine
{
	/** Ticks of silence after which a damage record is abandoned. Provisional. */
	static final int STALE_TICKS = 100;

	/** Suppression window for double-fire (ActorDeath then NpcDespawned). */
	static final int EMITTED_TICKS = 10;

	/**
	 * How recently an item-use must have happened for an unflagged despawn to count
	 * as a transform death. Tight on purpose — widen it and the rule starts claiming
	 * NPCs that merely wandered off. Provisional; measure on a gargoyle task.
	 */
	static final int FINISH_WINDOW_TICKS = 3;

	private final Map<Integer, Record> tracked = new HashMap<>();
	private final Map<Integer, Integer> emitted = new HashMap<>();

	/** Actor key to the tick at which the player last used an item on it. */
	private final Map<Integer, Integer> finishingActions = new HashMap<>();

	public void reset()
	{
		tracked.clear();
		emitted.clear();
		finishingActions.clear();
	}

	public int trackedCount()
	{
		return tracked.size();
	}

	// ------------------------------------------------------------------

	/**
	 * @param regionId where we engaged, recorded once on the first hitsplat
	 * @param mine     true if we dealt it, false if another player did
	 */
	public void damage(int key, int npcId, String name, int combatLevel, int regionId,
		int amount, boolean mine, int tick)
	{
		final Record r = tracked.computeIfAbsent(key,
			i -> new Record(npcId, name, combatLevel, regionId, tick));

		if (mine)
		{
			r.myDamage += amount;

			// a zero splat is still an attempt. blocks and splashes are the whole point
			// of tracking accuracy, don't filter them for being "empty"
			r.attacksCount++;
			if (amount > 0)
			{
				r.hitsCount++;
				r.maxHit = Math.max(r.maxHit, amount);
			}
		}
		else
		{
			r.othersDamage += amount;
		}

		r.lastTick = tick;
	}

	/** A multi-phase boss changing form. Carries forward; emits nothing. */
	public void composition(int key, int npcId, String name, int combatLevel, int tick)
	{
		final Record r = tracked.get(key);
		if (r == null)
		{
			return;
		}
		r.npcId = npcId;
		r.name = name;
		r.combatLevel = combatLevel;
		r.lastTick = tick;
	}

	public void death(int key, int tick, Consumer<KillRecord> sink)
	{
		resolve(key, DeathSignal.OBSERVED, tick, sink);
	}

	/**
	 * The player used an item on an NPC. Recorded without interpretation; it only
	 * matters if that NPC then vanishes unflagged — see {@link #despawn}.
	 */
	public void finishingAction(int key, int tick)
	{
		finishingActions.put(key, tick);
	}

	public void despawn(int key, boolean flaggedDead, int tick, Consumer<KillRecord> sink)
	{
		if (flaggedDead)
		{
			resolve(key, DeathSignal.DESPAWN_WHILE_DEAD, tick, sink);
			finishingActions.remove(key);
			return;
		}

		// Unflagged, but the player used an item on this exact NPC a moment ago and
		// it is now gone: a transform death. No monster list and no item list — the
		// evidence is the targeted action, so the rule survives new content.
		final Integer finishedAt = finishingActions.remove(key);
		if (finishedAt != null && tick - finishedAt <= FINISH_WINDOW_TICKS)
		{
			resolve(key, DeathSignal.TRANSFORM_FINISH, tick, sink);
			return;
		}

		tracked.remove(key);
	}

	/** Bounded memory. Without this a busy area accumulates records forever. */
	public void tick(int now)
	{
		for (Iterator<Map.Entry<Integer, Record>> it = tracked.entrySet().iterator(); it.hasNext(); )
		{
			if (now - it.next().getValue().lastTick > STALE_TICKS)
			{
				it.remove();
			}
		}
		for (Iterator<Map.Entry<Integer, Integer>> it = emitted.entrySet().iterator(); it.hasNext(); )
		{
			if (now - it.next().getValue() > EMITTED_TICKS)
			{
				it.remove();
			}
		}
		for (Iterator<Map.Entry<Integer, Integer>> it = finishingActions.entrySet().iterator(); it.hasNext(); )
		{
			if (now - it.next().getValue() > FINISH_WINDOW_TICKS)
			{
				it.remove();
			}
		}
	}

	// ------------------------------------------------------------------

	private void resolve(int key, DeathSignal signal, int tick, Consumer<KillRecord> sink)
	{
		final Integer already = emitted.get(key);
		if (already != null && tick - already <= EMITTED_TICKS)
		{
			return;
		}

		final Record r = tracked.remove(key);
		if (r == null || r.myDamage <= 0)
		{
			// Somebody else's kill, or nothing we can evidence. Silence is correct.
			return;
		}

		final Confidence grade;
		if (r.othersDamage > 0)
		{
			grade = Confidence.AMBIGUOUS;
		}
		else if (signal == DeathSignal.OBSERVED)
		{
			grade = Confidence.EXACT;
		}
		else
		{
			// Despawn-while-dead and transform finish are both deductions, so they
			// grade the same; the signal keeps them apart in the log.
			grade = Confidence.INFERRED;
		}

		emitted.put(key, tick);

		sink.accept(new KillRecord(
			UUID.randomUUID().toString(),
			r.npcId,
			r.name == null ? "Unknown NPC " + r.npcId : r.name,
			r.combatLevel,
			r.regionId,
			grade,
			signal,
			r.myDamage,
			r.othersDamage,
			r.attacksCount,
			r.hitsCount,
			r.maxHit,
			System.currentTimeMillis()));
	}

	private static final class Record
	{
		private int npcId;
		private String name;
		private int combatLevel;
		private final int regionId;
		private int myDamage;
		private int othersDamage;
		private int attacksCount;
		private int hitsCount;
		private int maxHit;
		private int lastTick;

		private Record(int npcId, String name, int combatLevel, int regionId, int tick)
		{
			this.npcId = npcId;
			this.name = name;
			this.combatLevel = combatLevel;
			this.regionId = regionId;
			this.lastTick = tick;
		}
	}
}
