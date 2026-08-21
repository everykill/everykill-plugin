/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

import java.awt.Color;

// every kill carries one of these. there's no way to say "probably a kill" here and
// there isn't going to be - grade it or don't record it. ranks read EXACT only,
// totals read the lot. colours match the site exactly, don't touch them.
public enum Confidence
{
	/** saw it die, we hit it, nobody else did */
	EXACT("exact", new Color(0x5f, 0x9e, 0x5f)),

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
