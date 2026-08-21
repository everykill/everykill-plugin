/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.xp;

/**
 * The skills combat damage pays into. Our own enum rather than
 * {@code net.runelite.api.Skill} so the XP classes stay client-free and testable.
 *
 * <p>Slayer is absent on purpose: it is paid per kill, equal to the monster's
 * hitpoints, so a damage-proportional allocator would be silently wrong.
 */
public enum CombatSkill
{
	ATTACK,
	STRENGTH,
	DEFENCE,
	RANGED,
	MAGIC,
	HITPOINTS
}
