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

	// Call from a game tick, never GameStateChanged. LOGGED_IN fires on every scene
	// load and beats the skill data, so you get zeros. Cost us 2.3m xp last time.
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
		// temp: no lines here but kills still logging = listener never wired
		log.debug("xp damage in: npc_id={} amount={} tick={}", npcId, amount, tick);
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

	/** Move accumulated experience into the ledger and clear the buffer. */
	public void drain(BiConsumer<Integer, Long> sink)
	{
		final Map<Integer, Long> drained = attributor.drain();
		for (Map.Entry<Integer, Long> e : drained.entrySet())
		{
			sink.accept(e.getKey(), e.getValue());
		}
	}

	public long getUnallocatedXp()
	{
		return attributor.getUnallocatedXp();
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
