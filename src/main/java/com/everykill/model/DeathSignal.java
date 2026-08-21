/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

/**
 * How a death was learned about. The grade says how much to trust a kill; the
 * signal says why, which is what makes the P1 divergence log readable.
 */
public enum DeathSignal
{
	/** The client reported the actor died, and we had damage on it. */
	OBSERVED,

	/** It left the scene while flagged dead. Deduced, not watched. */
	DESPAWN_WHILE_DEAD,

	/**
	 * Vanished without a death flag, right after the player used an item on it —
	 * rock hammer on a gargoyle, salt on a rockslug. Evidence, not assumption:
	 * a targeted action on that NPC, then it left. Never a hardcoded monster list.
	 */
	TRANSFORM_FINISH
}
