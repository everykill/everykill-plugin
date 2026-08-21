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
 * <h2>Compliance — read before adding a subscription</h2>
 *
 * Jagex's prohibited list covers features that aid boss fights (attack counters,
 * prayer switch indicators, next-attack prediction). Kill counts are not on it — the
 * game publishes them itself and core's Slayer plugin ships one by default. What
 * keeps us clear is <b>the trigger</b>, not the fact that the output is a number:
 *
 * <ul>
 *   <li>Nothing subscribes to an NPC's animation, projectile, graphic, or incoming
 *       hitsplat. Counters move after something has already died</li>
 *   <li>No per-boss branch. Multi-phase handling is generic {@code NpcChanged}
 *       carry-forward — identity bookkeeping, not mechanics advice</li>
 *   <li>Nothing is drawn on or near an NPC</li>
 * </ul>
 *
 * Jagex can add to that list, so design to the trigger rule, not the wording.
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
	 * <p>Not {@code npc.getIndex()}: the game recycles an index as soon as its slot
	 * frees, so a fresh NPC can inherit the previous occupant's suppression window and
	 * have its kill discarded — silently, which is the worst way to lose one. Verified
	 * 2026-08-20 against {@link KillStateMachine}: every reuse within
	 * {@code EMITTED_TICKS} dropped the second kill. Actor identity never recycles.
	 *
	 * <p>Keys are minted only for NPCs we damage, and dropped when they despawn.
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
			hitsplat.getAmount(), mine, tick);

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
	 * <p>{@code WIDGET_TARGET_ON_NPC} is the whole story: the inventory is itself a
	 * widget, so using an item on an NPC arrives as opcode 8. The older
	 * {@code ITEM_USE_ON_NPC} (opcode 7) is deprecated and core RuneLite references it
	 * nowhere outside the enum declaration, so matching it only invited a build break
	 * when it is finally removed.
	 *
	 * <p>Only NPCs we have already damaged are considered — a transform death needs
	 * our damage regardless, and minting a key for every shopkeeper we click would
	 * leak actors we never see die.
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
}
