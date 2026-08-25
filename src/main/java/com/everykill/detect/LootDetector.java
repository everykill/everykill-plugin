/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.detect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.game.ItemStack;
import net.runelite.api.NPCComposition;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPreFired;

/**
 * Buffers server-reported loot so a kill can claim it on the tick boundary.
 *
 * <h2>Why this doesn't do tile maths</h2>
 *
 * {@code spec-drop-attribution.md} describes tile coincidence — watch items appear,
 * remember the tile and tick, and on a death claim whatever is sitting on the monster's
 * footprint. That's what core's {@code LootManager} has always done and the spec
 * describes it accurately.
 *
 * The server also just tells us. {@code LOOTTRACKER_ADD_LOOT} fires with the npc id,
 * the item id and the quantity as arguments, and RuneLite hands it over as
 * {@link ServerNpcLoot}. That's not an inference about what appeared near a corpse,
 * it's a statement about what a monster dropped. Core moved to it — nothing in core
 * subscribes to the old tile-derived {@code NpcLootReceived} any more.
 *
 * Tile coincidence stays on the table as a fallback for monsters the server doesn't
 * report, but nobody has established which those are, so it isn't written yet.
 *
 * <h2>Accumulate, decide on the tick</h2>
 *
 * Same shape as {@link KillStateMachine} and {@code XpAttributor}, for the same reason:
 * events inside a tick arrive in an order you can't judge from the first one. So this
 * only buffers. Nothing here decides which kill owns what — {@code drainFor} hands the
 * tick's loot to whoever asks, and the joining lives with the kill records.
 *
 * <h2>The known weakness, and what the server gives us against it</h2>
 *
 * {@link ServerNpcLoot} carries an {@code NPCComposition}, not the NPC instance we
 * tracked, so all we get is an id. Two of the same monster dying on one tick would be
 * indistinguishable from that alone.
 *
 * The underlying script carries more than the event does. {@code LOOTTRACKER_ADD_LOOT}
 * is fired with {@code (npcId, eventId, itemId, qty)} and the <b>eventId increments per
 * kill</b> — measured 2026-08-24, four cyclops kills producing 77265, 77266, 77267,
 * 77268. RuneLite uses it to decide when to flush and then drops it; by the time
 * {@code ServerNpcLoot} is posted the discriminator is gone.
 *
 * So we read {@link net.runelite.api.events.ScriptPreFired} ourselves and keep the
 * eventId. That is strictly more information for the same subscription cost, and it is
 * the only thing that can separate two identical monsters dying together.
 *
 * <b>Not yet proven:</b> that two same-id kills on ONE tick get two different eventIds.
 * The four measured kills were sequential. Until that is measured, a tick holding more
 * than one loot event for the same npc id stays ambiguous — see {@code drainFor}.
 */
@Slf4j
@Singleton
public class LootDetector
{
	private final Client client;

	/** This tick's server-reported loot, oldest first. */
	private final List<ServerLoot> pending = new ArrayList<>();

	@Inject
	public LootDetector(Client client)
	{
		this.client = client;
	}

	/**
	 * One monster's drop, as the server reported it.
	 */
	public static final class ServerLoot
	{
		public final int npcId;
		public final String npcName;

		/**
		 * The server's own per-kill id. Two entries sharing one are the same kill's
		 * loot arriving in pieces; two different ids are two different kills.
		 */
		public final int eventId;

		/** Grows as the script fires each item; handed out read-only. */
		private final List<ItemStack> mutableItems;

		public final int tick;

		ServerLoot(int npcId, String npcName, int eventId, List<ItemStack> items, int tick)
		{
			this.npcId = npcId;
			this.npcName = npcName;
			this.eventId = eventId;
			this.mutableItems = items;
			this.tick = tick;
		}

		/** item id -> name, filled in on the client thread. */
		private final Map<Integer, String> names = new HashMap<>();
		private final Map<Integer, Integer> prices = new HashMap<>();

		/** What the server said this kill dropped. */
		public List<ItemStack> getItems()
		{
			return Collections.unmodifiableList(mutableItems);
		}

		/**
		 * Resolves item names while we're still on the client thread.
		 *
		 * <p>ItemManager reads through to the client, and the panel that displays these
		 * paints on Swing. Capturing the name here is the only place both are true.
		 */
		public void resolveNames(IntFunction<String> nameLookup, IntFunction<Integer> priceLookup)
		{
			for (ItemStack item : mutableItems)
			{
				names.computeIfAbsent(item.getId(), nameLookup::apply);
				prices.computeIfAbsent(item.getId(), priceLookup::apply);
			}
		}

		/** The resolved price per item, or 0 when it wasn't available. */
		public int priceOf(int itemId)
		{
			return prices.getOrDefault(itemId, 0);
		}

		/** The resolved name, or null when it wasn't available. */
		public String nameOf(int itemId)
		{
			return names.get(itemId);
		}
	}

	/**
	 * The loot script firing, one item at a time.
	 *
	 * <p>Items for one kill arrive as separate script fires sharing an eventId, so this
	 * merges them into the entry that id already opened rather than creating one per
	 * item.
	 */
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != ScriptID.LOOTTRACKER_ADD_LOOT)
		{
			return;
		}

		final Object[] args = event.getScriptEvent().getArguments();
		if (args == null || args.length < 5)
		{
			return;
		}

		final int npcId = (int) args[1];
		final int eventId = (int) args[2];
		final int itemId = (int) args[3];
		final int qty = (int) args[4];

		final NPCComposition comp = client.getNpcDefinition(npcId);
		final String name = comp == null ? null : comp.getName();

		record(npcId, name, eventId, itemId, qty, client.getTickCount());
	}

	/**
	 * The buffering itself, with no RuneLite types in the way.
	 *
	 * <p>Split out so it can be tested without a mocking framework — this project
	 * doesn't have one, and adding a dependency to reach a few lines would be the tail
	 * wagging the dog.
	 */
	public void record(int npcId, String npcName, int eventId, int itemId, int qty, int tick)
	{
		for (ServerLoot existing : pending)
		{
			if (existing.eventId == eventId && existing.tick == tick)
			{
				existing.mutableItems.add(new ItemStack(itemId, qty));
				return;
			}
		}

		final List<ItemStack> items = new ArrayList<>();
		items.add(new ItemStack(itemId, qty));
		pending.add(new ServerLoot(npcId, npcName, eventId, items, tick));
	}

	/**
	 * Everything the server reported for this npc id on this tick, and forget it.
	 *
	 * <p>Returns a list rather than one entry on purpose: two of the same monster can
	 * die together, and collapsing that into a single result would quietly invent an
	 * answer. A caller seeing more than one back has an ambiguous kill.
	 */
	public List<ServerLoot> drainFor(int npcId, int tick)
	{
		final List<ServerLoot> mine = new ArrayList<>();
		pending.removeIf(loot ->
		{
			if (loot.npcId == npcId && loot.tick == tick)
			{
				mine.add(loot);
				return true;
			}
			return false;
		});
		return mine;
	}

	/**
	 * Drop anything older than the current tick.
	 *
	 * <p>Loot nobody claimed is a real signal, not noise — it means the server reported
	 * a drop for a monster we never recorded a kill for, which is a hole in kill
	 * detection rather than in loot. Logged rather than silently binned.
	 *
	 * <p><b>Until attribution exists, everything looks unclaimed</b>, because nothing
	 * calls {@link #drainFor}. The log line says so rather than crying wolf on every
	 * drop in the game.
	 */
	public void expire(int tick)
	{
		pending.removeIf(loot ->
		{
			if (loot.tick < tick)
			{
				log.debug("server loot expired unclaimed (nothing claims yet): "
						+ "npc={} name={} items={} tick={}",
					loot.npcId, loot.npcName, loot.getItems().size(), loot.tick);
				return true;
			}
			return false;
		});
	}

	/** Buffered entries, for tests. */
	public int pendingCount()
	{
		return pending.size();
	}

	public void reset()
	{
		pending.clear();
	}
}
