/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

// how we found out it died. the grade says how much to trust it, this says why.
public enum DeathSignal
{
	/** client said it died and we'd hit it */
	OBSERVED,

	/** left the scene already flagged dead. worked out, not witnessed */
	DESPAWN_WHILE_DEAD,

	/** vanished with no death flag right after we used an item on it - gargoyles etc */
	TRANSFORM_FINISH
}
