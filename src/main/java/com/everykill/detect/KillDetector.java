/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.detect;

import com.everykill.model.KillRecord;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;

/**
 * Adapter: maps RuneLite client events onto {@link KillStateMachine}, which holds
 * all the judgement.
 *
 * <h2>Compliance — read this before adding a subscription</h2>
 *
 * Jagex prohibits things that help you win a fight: attack counters, prayer switch
 * indicators, next-attack prediction. Kill counts aren't on that list — the game
 * publishes them itself and core's Slayer plugin ships one on by default. What keeps
 * us on the right side is <b>the trigger</b>, not that the output happens to be a
 * number:
 *
 * <ul>
 *   <li>Nothing subscribes to an NPC's animation, projectile, graphic or incoming
 *       hitsplat. Our counters only move once something is already dead</li>
 *   <li>No per-boss branch, anywhere. Phase handling is generic {@code NpcChanged}
 *       carry-forward — bookkeeping about identity, not advice about mechanics</li>
 *   <li>Nothing is drawn on or near an NPC</li>
 * </ul>
 *
 * They can extend that list whenever they like, so hold the trigger rule rather than
 * the current wording.
 */
@Slf4j
@Singleton
public class KillDetector
{
	/** Notified of our own damage, so experience can be allocated by damage share. */
	public interface DamageListener
	{
		void onOurDamage(int npcId, int amount, int tick);
	}

	private final Client client;
	private final KillStateMachine machine = new KillStateMachine();

	// actor identity -> state machine key. NOT getIndex(), the game recycles those
	// instantly and the next npc inherits the dead one's suppression window. the kill
	// just fucks off into the void, no error, count quietly reads low forever.
	// minted only for npcs we damage, dropped on despawn
	private final Map<NPC, Integer> actorKeys = new IdentityHashMap<>();
	private int nextActorKey;

	private DamageListener damageListener;

	@Inject
	public KillDetector(Client client)
	{
		this.client = client;
	}

	public void setDamageListener(DamageListener listener)
	{
		this.damageListener = listener;
	}

	public void reset()
	{
		machine.reset();
		actorKeys.clear();
	}

	public void onHitsplatApplied(HitsplatApplied event)
	{
		final Actor actor = event.getActor();
		if (!(actor instanceof NPC))
		{
			return;
		}

		final Hitsplat hitsplat = event.getHitsplat();
		final boolean mine = hitsplat.isMine();
		final boolean others = hitsplat.isOthers();

		// Neither ours nor another player's: heals, someone else's poison ticks.
		if (!mine && !others)
		{
			return;
		}

		final NPC npc = (NPC) actor;
		final int tick = client.getTickCount();
		final int key = keyFor(npc, mine);
		machine.damage(key, npc.getId(), npc.getName(), npc.getCombatLevel(),
			regionOf(npc), hitsplat.getAmount(), mine, tick);

		// temp: the server sends ratio/scale, never real hp. core solves
		// healthRatio = 1 + (healthScale - 1) * health / maxHealth  for health, using a
		// max hp it got from the wiki. we already know the damage, so we can turn it
		// round and solve for maxHealth instead - jagex's number, no wiki in the chain.
		//
		// key= not npc_id. six demons in the room share three ids, so an id-tagged log
		// interleaves them and the solve fits garbage - it spat out 85 hp for something
		// we've killed with 80 damage. the plugin keys on actor identity for exactly
		// this reason; the log has to as well or it throws that away.
		if (mine)
		{
			log.debug("Hit: key={} npc_id={} amount={} healthRatio={} healthScale={} tick={}",
				key, npc.getId(), hitsplat.getAmount(), npc.getHealthRatio(),
				npc.getHealthScale(), tick);
		}

		if (mine && damageListener != null)
		{
			damageListener.onOurDamage(npc.getId(), hitsplat.getAmount(), tick);
		}
	}

	public void onActorDeath(ActorDeath event, Consumer<KillRecord> sink)
	{
		final Actor actor = event.getActor();
		if (!(actor instanceof NPC))
		{
			return;
		}

		final Integer key = actorKeys.get(actor);

		// temp: every lesser demon graded INFERRED via DESPAWN_WHILE_DEAD, which can
		// only happen if we never held a death signal at all. so does ActorDeath even
		// fire for them? keyed=false just means it died and we never touched it, that's
		// normal. NO line for something we definitely killed is the smoking gun.
		log.debug("ActorDeath: npc_id={} name={} keyed={} tick={}",
			((NPC) actor).getId(), actor.getName(), key != null, client.getTickCount());

		if (key != null)
		{
			machine.death(key, client.getTickCount(), sink);
		}
	}

	public void onNpcDespawned(NpcDespawned event, Consumer<KillRecord> sink)
	{
		final NPC npc = event.getNpc();
		final Integer key = actorKeys.remove(npc);
		if (key != null)
		{
			machine.despawn(key, npc.isDead(), client.getTickCount(), sink);
		}
	}

	public void onNpcChanged(NpcChanged event)
	{
		final NPC npc = event.getNpc();
		final Integer key = actorKeys.get(npc);
		if (key != null)
		{
			machine.composition(key, npc.getId(), npc.getName(), npc.getCombatLevel(),
				client.getTickCount());
		}
	}

	// transform-death signal: player used an item on an npc. without this a whole
	// gargoyle task counts as zero. never look at which item - no list, so it still
	// works on whatever they add next.
	// WIDGET_TARGET_ON_NPC only. inventory is a widget, so item-on-npc is opcode 8.
	// ITEM_USE_ON_NPC is deprecated and core references it nowhere.
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getMenuAction() != MenuAction.WIDGET_TARGET_ON_NPC)
		{
			return;
		}

		final NPC npc = event.getMenuEntry().getNpc();
		if (npc == null)
		{
			return;
		}

		final Integer key = actorKeys.get(npc);
		if (key != null)
		{
			machine.finishingAction(key, client.getTickCount());
		}
	}

	public void onGameTick(GameTick event)
	{
		machine.tick(client.getTickCount());
	}

	private int keyFor(NPC npc, boolean mine)
	{
		final Integer existing = actorKeys.get(npc);
		if (existing != null)
		{
			return existing;
		}

		// temp: is the thing already hurt when we first touch it? if so somebody else
		// got there first and we are about to call that kill uncontested, which it isn't.
		// -1 means no health bar. server never sends real hp, only ratio/scale.
		//
		// by= matters. this fires on anyone's hitsplat, not just ours, so a line here
		// does NOT mean we hit it. six cockatrices someone else was killing read like
		// six kills we'd missed until that got straightened out.
		final int key = ++nextActorKey;
		actorKeys.put(npc, key);

		log.debug("First contact: key={} npc_id={} name={} by={} healthRatio={} healthScale={} tick={}",
			key, npc.getId(), npc.getName(), mine ? "us" : "other",
			npc.getHealthRatio(), npc.getHealthScale(), client.getTickCount());

		return key;
	}

	// npc's square, never the player's, never finer than a region. keep it that way,
	// anything more precise is a movement trace.
	private static int regionOf(NPC npc)
	{
		final WorldPoint location = npc.getWorldLocation();
		return location == null ? -1 : location.getRegionID();
	}
}
