/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What an account has earned, and what it is wearing.
 *
 * <p>One snapshot per panel open — never per repaint. {@code /v1/helmet} and
 * {@code /v1/title} share the upload limiter at 1 request per 60s, and a Swing panel
 * rebuilds far more often than anyone expects.
 */
public class Unlocks
{
	/** An earned or upcoming cosmetic. */
	public static class Item
	{
		public final String id;
		public final String name;
		/** Wiki sprite filename, or null for a title. */
		public final String file;
		public final String tier;
		/** How it's unlocked — shown under a locked slot. */
		public final String how;
		/** 1-7 for titles, 0 for helmets. Drives colour on the site. */
		public final int rarity;

		public Item(String id, String name, String file, String tier, String how, int rarity)
		{
			this.id = id;
			this.name = name;
			this.file = file;
			this.tier = tier;
			this.how = how;
			this.rarity = rarity;
		}
	}

	/**
	 * Whether the account has a published name.
	 *
	 * <p>Picks are stored on the published-name row, so an unpublished account has
	 * nowhere to put one. Verified live: the server checks {@code not_earned} first, so
	 * an unpublished account with nothing earned sees 422 rather than 409 — the panel
	 * must not promise which error it'll get.
	 */
	public final boolean published;

	public final List<Item> helmets;
	public final List<Item> titles;
	public final String wearingHelmet;
	public final String wearingTitle;

	/** The next few rungs above where they are, so a locked slot can say "100 kills". */
	public final List<Item> next;

	public Unlocks(boolean published, List<Item> helmets, List<Item> titles,
		String wearingHelmet, String wearingTitle, List<Item> next)
	{
		this.published = published;
		this.helmets = Collections.unmodifiableList(helmets == null
			? new ArrayList<>() : helmets);
		this.titles = Collections.unmodifiableList(titles == null
			? new ArrayList<>() : titles);
		this.wearingHelmet = wearingHelmet;
		this.wearingTitle = wearingTitle;
		this.next = Collections.unmodifiableList(next == null ? new ArrayList<>() : next);
	}

	/** True when nothing has been earned yet — the day-one state. */
	public boolean isEmpty()
	{
		return helmets.isEmpty() && titles.isEmpty();
	}
}
