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

	/**
	 * Actor identity to the key the state machine tracks it by.
	 *
	 * <p><b>Not {@code npc.getIndex()}.</b> The game recycles indices the second a slot
	 * frees up, so the next NPC inherits the dead one's suppression window and its kill
	 * just disappears. No exception, no log line, nothing — just a number that reads
	 * low forever and looks perfectly reasonable. Measured 2026-08-20: every single
	 * reuse inside {@code EMITTED_TICKS} ate the second kill. Object identity doesn't
	 * recycle. Use it.
	 *
	 * <p>Keys minted only for NPCs we damage, dropped when they despawn.
	 */
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
		machine.damage(keyFor(npc), npc.getId(), npc.getName(), npc.getCombatLevel(),
			regionOf(npc), hitsplat.getAmount(), mine, tick);

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

	/**
	 * The transform-death signal: the player used an item on an NPC. Without this an
	 * entire gargoyle task counts as zero. The item is never inspected — no list of
	 * finishing items, so the rule survives new content.
	 *
	 * <p>{@code WIDGET_TARGET_ON_NPC} only. The inventory is a widget, so item-on-NPC
	 * comes through as opcode 8. {@code ITEM_USE_ON_NPC} is deprecated and core
	 * RuneLite mentions it nowhere but the enum itself — matching it just queues up a
	 * build break for whenever they finally delete it.
	 *
	 * <p>Only NPCs we've already damaged. Transform deaths need our damage anyway, and
	 * minting a key every time someone clicks a shopkeeper leaks actors we never see
	 * die.
	 */
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

	/** Mints a key on first sight. Called only where we know the NPC is ours to track. */
	private int keyFor(NPC npc)
	{
		return actorKeys.computeIfAbsent(npc, n -> ++nextActorKey);
	}

	/**
	 * Region we engaged in. The NPC's square, never the player's, never finer than a
	 * region. Keep it that way — it's a "where was this fought" tag, and anything more
	 * precise turns it into a movement trace.
	 */
	private static int regionOf(NPC npc)
	{
		final WorldPoint location = npc.getWorldLocation();
		return location == null ? -1 : location.getRegionID();
	}
}
