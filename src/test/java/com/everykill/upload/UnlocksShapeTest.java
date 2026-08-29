/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * {@code /v1/unlocks} uses two shapes in one response.
 *
 * <p>Earned lists are bare id strings; {@code next} is full objects. Parsing all three
 * as objects returned empty lists, and the panel told an account with two unlocks that
 * it had earned nothing.
 *
 * <p>A fresh account could not catch this — empty lists parse identically either way.
 * That is the lesson worth keeping: an empty response proves nothing about shape.
 */
public class UnlocksShapeTest
{
	private static List<Unlocks.Item> items(JsonObject o, String key) throws Exception
	{
		final Method m = UploadClient.class.getDeclaredMethod(
			"items", JsonObject.class, String.class);
		m.setAccessible(true);
		@SuppressWarnings("unchecked")
		final List<Unlocks.Item> out = (List<Unlocks.Item>) m.invoke(null, o, key);
		return out;
	}

	/** The real response from a live account, verbatim. */
	private static final String LIVE = "{"
		+ "\"published\":true,"
		+ "\"helmets\":[\"cowl\"],"
		+ "\"titles\":[\"the-new\"],"
		+ "\"wearing\":{\"helmet\":null,\"title\":null},"
		+ "\"next\":[{\"id\":\"bronze-med\",\"name\":\"Bronze med helm\","
		+ "\"file\":\"Bronze_med_helm.png\",\"tier\":\"Starter\",\"how\":\"100 kills\"}]"
		+ "}";

	@Test
	public void earnedIdsAreReadAsStrings() throws Exception
	{
		final JsonObject o = new Gson().fromJson(LIVE, JsonObject.class);

		final List<Unlocks.Item> helmets = items(o, "helmets");
		Assert.assertEquals("an earned helmet must not vanish", 1, helmets.size());
		Assert.assertEquals("cowl", helmets.get(0).id);

		final List<Unlocks.Item> titles = items(o, "titles");
		Assert.assertEquals(1, titles.size());
		Assert.assertEquals("the-new", titles.get(0).id);
	}

	@Test
	public void nextIsReadAsObjects() throws Exception
	{
		final JsonObject o = new Gson().fromJson(LIVE, JsonObject.class);
		final List<Unlocks.Item> next = items(o, "next");

		Assert.assertEquals(1, next.size());
		Assert.assertEquals("Bronze med helm", next.get(0).name);
		Assert.assertEquals("100 kills", next.get(0).how);
	}

	@Test
	public void bothShapesInOneList() throws Exception
	{
		// defensive: if the server ever mixes them, take both rather than dropping half.
		final JsonObject o = new Gson().fromJson(
			"{\"x\":[\"plain-id\",{\"id\":\"obj-id\",\"name\":\"Object\"}]}",
			JsonObject.class);

		final List<Unlocks.Item> out = items(o, "x");
		Assert.assertEquals(2, out.size());
		Assert.assertEquals("plain-id", out.get(0).id);
		Assert.assertEquals("Object", out.get(1).name);
	}

	@Test
	public void aMissingKeyIsEmptyNotNull() throws Exception
	{
		final JsonObject o = new Gson().fromJson("{}", JsonObject.class);
		Assert.assertTrue(items(o, "helmets").isEmpty());
	}
}
