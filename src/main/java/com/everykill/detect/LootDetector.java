/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.detect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.game.ItemStack;
import net.runelite.client.events.ServerNpcLoot;

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
 * <h2>The known weakness</h2>
 *
 * {@link ServerNpcLoot} carries an {@code NPCComposition}, not the NPC instance we
 * tracked, so all we get is an id. Two of the same monster dying on one tick are
 * indistinguishable here. That's a real hole and the spec already says what to do with
 * it — mark those {@code unknown} and keep them out of drop-rate denominators. It is
 * not this class's job to guess.
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
		public final List<ItemStack> items;
		public final int tick;

		ServerLoot(int npcId, String npcName, List<ItemStack> items, int tick)
		{
			this.npcId = npcId;
			this.npcName = npcName;
			this.items = Collections.unmodifiableList(items);
			this.tick = tick;
		}
	}

	public void onServerNpcLoot(ServerNpcLoot event)
	{
		if (event.getComposition() == null || event.getItems() == null)
		{
			return;
		}

		record(event.getComposition().getId(),
			event.getComposition().getName(),
			new ArrayList<>(event.getItems()),
			client.getTickCount());
	}

	/**
	 * The buffering itself, with no RuneLite types in the way.
	 *
	 * <p>Split out so it can be tested without a mocking framework — this project
	 * doesn't have one, and adding a dependency to reach a four-line method would be
	 * the tail wagging the dog.
	 */
	public void record(int npcId, String npcName, List<ItemStack> items, int tick)
	{
		pending.add(new ServerLoot(npcId, npcName, items, tick));
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
	 */
	public void expire(int tick)
	{
		pending.removeIf(loot ->
		{
			if (loot.tick < tick)
			{
				log.debug("unclaimed server loot: npc={} name={} items={} tick={}",
					loot.npcId, loot.npcName, loot.items.size(), loot.tick);
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
