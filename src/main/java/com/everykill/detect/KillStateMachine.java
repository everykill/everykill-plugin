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
import lombok.extern.slf4j.Slf4j;

/**
 * All the kill detection rules, in plain java with no client anywhere near it.
 * {@link KillDetector} does the translating. That split is why these are testable.
 *
 * <ol>
 *   <li>Kill = a death signal AND our own damage. One without the other gets binned.
 *       Overcounting is the thing to be scared of - it inflates everything downstream
 *       and looks completely fine while doing it</li>
 *   <li>Someone else's damage drops it to {@link Confidence#AMBIGUOUS}</li>
 *   <li>Saw it die = {@link Confidence#UNCONTESTED}. Worked it out = {@link Confidence#INFERRED}</li>
 *   <li>Composition change carries the damage over and emits nothing</li>
 *   <li>Despawn with no death flag is binned, unless we used an item on it within
 *       {@link #FINISH_WINDOW_TICKS} - that's a transform death</li>
 *   <li>One key can't emit twice inside {@link #EMITTED_TICKS}</li>
 * </ol>
 *
 * <p>Keys come from {@link KillDetector}, stable per actor and never reissued. Don't
 * "simplify" them back to getIndex() - it eats kills every time a slot gets recycled.
 */
@Slf4j
public class KillStateMachine
{
	/** Ticks of silence after which a damage record is abandoned. Provisional. */
	static final int STALE_TICKS = 100;

	/**
	 * Belt and braces now that only despawn emits. Was load-bearing when ActorDeath
	 * emitted too, and it wasn't up to the job - a rockslug got counted twice nine
	 * seconds apart, which is well outside this.
	 */
	static final int EMITTED_TICKS = 10;

	/**
	 * How long a death signal is believed before the corpse has to show for it.
	 * Provisional. Too short and a slow despawn drops UNCONTESTED to INFERRED, which is
	 * a grade we can live with. Too long and a lie gets believed, which we can't.
	 */
	static final int DEATH_CONFIRM_TICKS = 5;

	/**
	 * How recently an item-use must have happened for an unflagged despawn to count as
	 * a transform death.
	 *
	 * <p>Was 3, "tight on purpose" so the rule couldn't claim things that merely
	 * wandered off. That reasoning held while isDead() was still catching these as a
	 * fallback. It isn't any more - we stopped believing it, correctly - so this window
	 * is now the only route to counting a transform kill, and at 3 it dropped a real
	 * one on the floor. Salt early and the health bar never empties, so there is no
	 * death signal and no dead flag either.
	 *
	 * <p>Provisional at 5. See the discard log below for the real gap.
	 */
	static final int FINISH_WINDOW_TICKS = 5;

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

	/**
	 * The client says it died. <b>We don't believe it yet.</b>
	 *
	 * <p>ActorDeath fires when the health ratio hits zero, which is not the same thing
	 * as dying. A rockslug at 0 hp is still standing there waiting for salt. A boss
	 * mid-phase is about to get back up. Emitting here counted eight rockslugs as
	 * seven kills, one of them off a single point of damage, because the same slug got
	 * counted twice - once on the lie, once on the real despawn.
	 *
	 * <p>So record it and wait. A real kill despawns a tick or two later and
	 * {@link #despawn} emits it. A lie leaves the npc standing and {@link #tick} throws
	 * the flag away. No monster list needed - the corpse either leaves or it doesn't.
	 */
	public void death(int key, int tick, Consumer<KillRecord> sink)
	{
		final Record r = tracked.get(key);
		if (r != null)
		{
			r.deathSignalledAt = tick;
		}
	}

	/**
	 * The player used an item on an NPC. Recorded without interpretation; it only
	 * matters if that NPC then vanishes unflagged — see {@link #despawn}.
	 */
	public void finishingAction(int key, int tick)
	{
		finishingActions.put(key, tick);
	}

	/** The actor left. This is where kills actually get emitted. */
	public void despawn(int key, boolean flaggedDead, int tick, Consumer<KillRecord> sink)
	{
		final Record r = tracked.get(key);
		final Integer finishedAt = finishingActions.remove(key);

		// item first, even if ActorDeath also fired. on a transform monster ActorDeath
		// is the lie and the item is what actually finished it, so calling that
		// OBSERVED would be claiming we watched something we deduced.
		if (finishedAt != null && tick - finishedAt <= FINISH_WINDOW_TICKS)
		{
			resolve(key, DeathSignal.TRANSFORM_FINISH, tick, sink);
			return;
		}

		// death signal we've been holding, now confirmed by the corpse leaving
		if (r != null && r.deathSignalledAt >= 0 && tick - r.deathSignalledAt <= DEATH_CONFIRM_TICKS)
		{
			resolve(key, DeathSignal.OBSERVED, tick, sink);
			return;
		}

		// isDead() is the same zero health ratio ActorDeath reads, so if this thing
		// already lied about dying we don't get to believe it a second time. measured
		// 2026-08-21: a rockslug left at 0 hp and abandoned despawned when the player
		// walked off, flaggedDead was true, and we counted a monster still standing.
		if (flaggedDead && r != null && !r.deathSignalRevoked)
		{
			resolve(key, DeathSignal.DESPAWN_WHILE_DEAD, tick, sink);
			return;
		}

		// anything we hit and then binned. usually right - it wandered off, or it was
		// already at 0 hp and lying about it - but a real kill going missing looks
		// exactly the same from outside, and without this line the two are
		// indistinguishable. a stranger's monster never reaches here at all, because
		// it has none of our damage on it.
		if (r != null && r.myDamage > 0)
		{
			log.debug("Discarded a despawn we had damage on: npc_id={} name={} myDamage={} flaggedDead={} revoked={} deathAt={} itemAt={} itemGap={} tick={}",
				r.npcId, r.name, r.myDamage, flaggedDead, r.deathSignalRevoked,
				r.deathSignalledAt, finishedAt,
				finishedAt == null ? -1 : tick - finishedAt, tick);
		}

		tracked.remove(key);
	}

	/** Bounded memory. Without this a busy area accumulates records forever. */
	public void tick(int now)
	{
		for (Iterator<Map.Entry<Integer, Record>> it = tracked.entrySet().iterator(); it.hasNext(); )
		{
			final Record r = it.next().getValue();

			// it said it died and it's still standing there. it was lying - drop the
			// flag and carry on, because this thing is going to die again properly and
			// we are not counting it twice.
			if (r.deathSignalledAt >= 0 && now - r.deathSignalledAt > DEATH_CONFIRM_TICKS)
			{
				r.deathSignalledAt = -1;
				r.deathSignalRevoked = true;
			}

			if (now - r.lastTick > STALE_TICKS)
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
		// keep these well past FINISH_WINDOW_TICKS. despawn() enforces the window itself,
		// and purging on the window means a late despawn arrives with the item-use
		// already forgotten - so we can't tell "wandered off" from "we missed it by two
		// ticks", which is the one thing worth knowing here.
		for (Iterator<Map.Entry<Integer, Integer>> it = finishingActions.entrySet().iterator(); it.hasNext(); )
		{
			if (now - it.next().getValue() > STALE_TICKS)
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
			grade = Confidence.UNCONTESTED;
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

		/** tick ActorDeath fired, -1 if it hasn't. believed until it isn't. */
		private int deathSignalledAt = -1;

		// it claimed to be dead and then didn't leave. we've caught it lying once, so
		// isDead() on the despawn doesn't get the benefit of the doubt either - it's
		// the same zero health ratio telling the same fib.
		private boolean deathSignalRevoked;

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
