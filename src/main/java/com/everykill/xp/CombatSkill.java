/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.xp;

// our own enum, not net.runelite.api.Skill, so the xp classes stay client-free.
// slayer is missing on purpose: it pays per kill, not per damage, so shoving it
// through a damage-share allocator would be wrong and wouldn't look wrong.
public enum CombatSkill
{
	ATTACK,
	STRENGTH,
	DEFENCE,
	RANGED,
	MAGIC,
	HITPOINTS
}
