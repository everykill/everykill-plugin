/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.xp;

import java.util.Map;
import java.util.function.BiConsumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;

/**
 * Translates RuneLite's {@link Skill} and {@link StatChanged} for
 * {@link XpAttributor}, which stays free of client types so it can be tested plainly.
 */
@Slf4j
@Singleton
public class XpService
{
	private final Client client;

	private final XpAttributor attributor = new XpAttributor();

	@Inject
	public XpService(Client client)
	{
		this.client = client;
	}

	public boolean isPrimed()
	{
		return attributor.isPrimed();
	}

	// call from a game tick, never GameStateChanged. LOGGED_IN does not mean logged in,
	// it fires on every fucking scene load and beats the skill data, so you get zeros.
	// zero baseline = the player's whole combat history reads as one gain. 2.3m of it.
	public void prime()
	{
		for (CombatSkill skill : CombatSkill.values())
		{
			final Skill mapped = toRuneLite(skill);
			if (mapped != null)
			{
				attributor.prime(skill, client.getSkillExperience(mapped));
			}
		}
		log.debug("primed xp baselines");
	}

	public void reset()
	{
		attributor.reset();
	}

	/** Our own damage on an NPC. Fed from the kill detector's hitsplat handling. */
	public void damage(int npcId, int amount, int tick)
	{
		attributor.damage(npcId, amount, tick);
	}

	public void onStatChanged(StatChanged event)
	{
		final CombatSkill skill = fromRuneLite(event.getSkill());
		if (skill == null)
		{
			return;
		}
		attributor.xpChanged(skill, event.getXp(), client.getTickCount());
	}

	/** npcId, skill, amount. */
	public interface XpSink
	{
		void accept(int npcId, CombatSkill skill, long xp);
	}

	/** Move accumulated experience into the ledger and clear the buffer. */
	public void drain(XpSink sink)
	{
		// settle the tick that has FINISHED, not the one we're standing in. GameTick
		// arrives before that tick's hitsplats do, so settling at getTickCount() looks
		// for a damage pool that hasn't been filled yet, gives up on it, and strands
		// xp that had a perfectly good pool a moment later. seen live 2026-08-24:
		// "xp=34 arrivedAt=114 now=117 pools=[113, 114, 115, 117]" - written off with
		// its own tick sitting right there in the map.
		//
		// core waits the same way. SpecialCounterPlugin parks a future hitsplatTick
		// and only acts once onGameTick reaches it.
		attributor.settle(client.getTickCount() - 1);

		for (Map.Entry<Integer, Map<CombatSkill, Long>> npc : attributor.drain().entrySet())
		{
			for (Map.Entry<CombatSkill, Long> skill : npc.getValue().entrySet())
			{
				sink.accept(npc.getKey(), skill.getKey(), skill.getValue());
			}
		}
	}

	public long getUnallocatedXp()
	{
		return attributor.getUnallocatedXp();
	}

	/** Only what went missing mid-fight. Teleports and alching don't count. */
	public long getStrandedXp()
	{
		return attributor.getStrandedXp();
	}

	// ------------------------------------------------------------------

	private static CombatSkill fromRuneLite(Skill skill)
	{
		switch (skill)
		{
			case ATTACK:
				return CombatSkill.ATTACK;
			case STRENGTH:
				return CombatSkill.STRENGTH;
			case DEFENCE:
				return CombatSkill.DEFENCE;
			case RANGED:
				return CombatSkill.RANGED;
			case MAGIC:
				return CombatSkill.MAGIC;
			case HITPOINTS:
				return CombatSkill.HITPOINTS;
			default:
				// Slayer is excluded on purpose: paid per kill, not per damage.
				return null;
		}
	}

	private static Skill toRuneLite(CombatSkill skill)
	{
		switch (skill)
		{
			case ATTACK:
				return Skill.ATTACK;
			case STRENGTH:
				return Skill.STRENGTH;
			case DEFENCE:
				return Skill.DEFENCE;
			case RANGED:
				return Skill.RANGED;
			case MAGIC:
				return Skill.MAGIC;
			case HITPOINTS:
				return Skill.HITPOINTS;
			default:
				return null;
		}
	}
}
