/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

import java.awt.Color;

/**
 * Kill confidence, on every recorded kill.
 *
 * Classify, never fabricate: there is no way to express "probably a kill" other than
 * a grade. Ranks and rates read {@link #EXACT} only; totals read everything.
 * Colours are shared verbatim with the website.
 */
public enum Confidence
{
	/** We saw it die, we damaged it, nobody else did. */
	EXACT("exact", new Color(0x5f, 0x9e, 0x5f)),

	/** Death deduced, not observed — despawn while dead, or a transform finish. */
	INFERRED("inferred", new Color(0xc9, 0x91, 0x3c)),

	/** Another player damaged it too. Counted in totals, out of denominators. */
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
