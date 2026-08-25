/*
 * Copyright (c) 2026, Delkyy
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
 * Monster name to a stand-in item id.
 *
 * <p>There is no NPC image API — {@code NPCComposition.getModels()} returns 3D model
 * ids and nothing in core renders one to a panel. What works instead is drawing an
 * ITEM that reads as the monster: an ensouled head, a slayer drop, its unique. That is
 * what {@code ItemManager.getImage} is already doing for drops, so it costs nothing new.
 *
 * <p>Wiki PNGs are still out — CC BY-NC-SA against a BSD plugin, see LICENSING.md. This
 * table is hand-mapped and carried across from the Slayer Alternatives plugin, same
 * author, same licence.
 *
 * <p>Missing is normal and must stay cheap: 238 names against thousands of npc ids, so
 * most monsters have no icon and the panel simply doesn't draw one.
 */
final class NpcIcons
{
	private static final String RESOURCE = "/com/everykill/npc-icons.json";

	private final Map<String, Integer> byName;

	private NpcIcons(Map<String, Integer> byName)
	{
		this.byName = byName;
	}

	static NpcIcons load(Gson gson)
	{
		final Type type = new TypeToken<Map<String, Integer>>()
		{
		}.getType();

		try (InputStream in = NpcIcons.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				return new NpcIcons(Collections.emptyMap());
			}
			final Map<String, Integer> m = gson.fromJson(
				new InputStreamReader(in, StandardCharsets.UTF_8), type);
			return new NpcIcons(m == null ? Collections.emptyMap() : m);
		}
		catch (IOException e)
		{
			// icons are decoration. a missing file must not stop the panel working.
			return new NpcIcons(Collections.emptyMap());
		}
	}

	/**
	 * Item id to draw for this monster, or -1 when we don't have one.
	 *
	 * <p>Falls back to a singular form, so "Giant rats" finds "Giant rat" — the table
	 * carries both spellings for the common cases but not for everything.
	 */
	int forName(String name)
	{
		if (name == null)
		{
			return -1;
		}

		final Integer exact = byName.get(name);
		if (exact != null)
		{
			return exact;
		}

		if (name.endsWith("s"))
		{
			final Integer singular = byName.get(name.substring(0, name.length() - 1));
			if (singular != null)
			{
				return singular;
			}
		}

		return -1;
	}
}
