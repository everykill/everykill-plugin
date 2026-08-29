/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.ui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * Helmet id from the site to the game item that draws it.
 *
 * <h2>Why a table instead of fetching the sprite</h2>
 *
 * The site hands back a wiki filename, and the obvious move is to fetch that PNG. That
 * would be a second outbound HTTP path on a plugin whose upload feature already carries
 * a Hub warning line — new network surface for a reviewer to read, for decoration.
 *
 * <p>Every unlockable helmet is a real game item, so {@code ItemManager} already has the
 * sprite cached. This table is the only missing piece, and it ships in the jar.
 *
 * <h2>Where the ids came from</h2>
 *
 * The wiki, via {@code tools/fetch-helmet-items.py}. Not from the {@code gameval}
 * constant names — those are game-internal ({@code SLAYER_HELM},
 * {@code SLAYER_FACEMASK}) and don't match the site's names, and
 * {@code ItemManager.search} is tradeable-only so it misses Slayer helmet, Void melee
 * helm and the Barrows pieces. 53/53 resolved, checked against the live catalogue.
 */
final class HelmetIcons
{
	private static final String RESOURCE = "/com/everykill/helmet-items.json";

	private final Map<String, Integer> byId;

	private HelmetIcons(Map<String, Integer> byId)
	{
		this.byId = byId;
	}

	static HelmetIcons load(Gson gson)
	{
		final Type type = new TypeToken<Map<String, Integer>>()
		{
		}.getType();

		try (InputStream in = HelmetIcons.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				return new HelmetIcons(Collections.emptyMap());
			}
			final Map<String, Integer> m = gson.fromJson(
				new InputStreamReader(in, StandardCharsets.UTF_8), type);
			return new HelmetIcons(m == null ? Collections.emptyMap() : m);
		}
		catch (IOException e)
		{
			// sprites are decoration. a missing table must not stop someone picking a
			// helmet — the name still reads fine on its own.
			return new HelmetIcons(Collections.emptyMap());
		}
	}

	/**
	 * Item id for a helmet id, or -1 when we don't have one.
	 *
	 * <p>A new helmet on the site before a plugin update lands here, and returning -1
	 * means the row draws without a sprite rather than not drawing at all.
	 */
	int itemFor(String helmetId)
	{
		if (helmetId == null)
		{
			return -1;
		}
		final Integer id = byId.get(helmetId);
		return id == null ? -1 : id;
	}
}
