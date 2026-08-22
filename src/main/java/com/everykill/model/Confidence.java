/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

import java.awt.Color;

// every kill carries one of these. there's no way to say "probably a kill" here and
// there isn't going to be - grade it or don't record it. ranks read the top grade only,
// totals read the lot. colours match the site exactly, don't touch them.
//
// THE CLIENT'S CEILING IS UNCONTESTED, and that is deliberate. There is no EXACT here.
// A record opens on the first hitsplat WE witness, so damage dealt before we arrived is
// invisible - we can prove nobody else hit it after we turned up, and nothing more than
// that. Calling that "exact" was claiming something we cannot see.
//
// Proving we dealt ALL of it needs the monster's max hp: deal less than that and it
// dies anyway, and the difference came from someone else. That's server-side, so if a
// true EXACT ever exists it gets assigned there. See docs/spec-reference-data.md.
public enum Confidence
{
	/** we hit it, we saw it die, and nobody else touched it while we were watching */
	UNCONTESTED("uncontested", new Color(0x5f, 0x9e, 0x5f)),

	/** worked out rather than witnessed - despawned dead, or a transform finish */
	INFERRED("inferred", new Color(0xc9, 0x91, 0x3c)),

	/** someone else hit it too. counts in totals, never in a denominator */
	AMBIGUOUS("ambiguous", new Color(0xb4, 0x52, 0x52));

	private final String label;
	private final Color color;

	Confidence(String label, Color color)
	{
		this.label = label;
		this.color = color;
	}

	public String getLabel()
	{
		return label;
	}

	public Color getColor()
	{
		return color;
	}
}
