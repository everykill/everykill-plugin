/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

/**
 * How much the loot on a kill can be trusted. Separate from {@link Confidence}, which
 * grades the kill itself.
 *
 * <p>A kill can be perfectly graded and its loot still be unusable — two identical
 * monsters dying together are one kill each and one pile between them.
 *
 * <p>Only {@link #CONFIRMED} may be used as a drop-rate denominator. That rule is
 * inherited from {@code spec-drop-attribution.md} and it exists because counting an
 * uncertain kill as a dry one is the easiest way to make every published rate wrong
 * while nothing appears broken.
 */
public enum LootConfidence
{
	/** One server loot event, one kill, kill graded {@link Confidence#UNCONTESTED}. */
	CONFIRMED("Confirmed"),

	/** Loot is ours but the kill was deduced rather than witnessed. Totals only. */
	PROBABLE("Probable"),

	/**
	 * We cannot say which kill this loot belongs to. Two of the same monster died on
	 * one tick and the server reported more than one drop for that id.
	 *
	 * <p><b>Excluded from denominators entirely</b> — never counted as a dry kill.
	 */
	UNKNOWN("Unknown"),

	/**
	 * The server reported no loot for this kill.
	 *
	 * <p>Deliberately not a judgement. It means one of three things and this enum
	 * cannot tell them apart on its own:
	 *
	 * <ul>
	 *   <li>the monster genuinely drops nothing — ghosts, measured 2026-08-24</li>
	 *   <li>the drop was voided — an ironman whose kill someone else touched</li>
	 *   <li>we missed it</li>
	 * </ul>
	 *
	 * {@code always_drops.tsv} settles the first case: a monster with a guaranteed
	 * drop and no loot event was <b>not</b> dry. That cross-check isn't wired up yet,
	 * so until it is, treat this as unresolved rather than empty.
	 */
	NONE("No loot reported");

	private final String label;

	LootConfidence(String label)
	{
		this.label = label;
	}

	public String getLabel()
	{
		return label;
	}
}
