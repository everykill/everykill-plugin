/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import com.everykill.model.Confidence;
import com.everykill.model.DeathSignal;
import com.everykill.model.Drop;
import com.everykill.model.KillRecord;
import com.everykill.model.LootConfidence;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;

/**
 * The wire format, pinned.
 *
 * <p>Every one of these was verified against the running reference server before being
 * written down — a serialiser test that only agrees with itself proves nothing about
 * what the server accepts.
 */
public class UploadGsonTest
{
	private final Gson gson = UploadGson.wire(new Gson());

	private static KillRecord kill(Confidence grade, LootConfidence loot)
	{
		return new KillRecord("e1", 7271, "Cyclops", 56, 6556, grade,
			DeathSignal.OBSERVED, 75, 0, 9, 7, 17, 1_756_100_000_000L,
			Collections.singletonList(new Drop(532, 1, "Big bones", 0)), loot, 12,
			Collections.emptyList());
	}

	private JsonObject serialise(KillRecord k)
	{
		return gson.toJsonTree(k).getAsJsonObject();
	}

	@Test
	public void gradeGoesOutLowercase()
	{
		// the bug this class exists for. java says UNCONTESTED, the contract says
		// uncontested, and the live server answered:
		//   "grade 'UNCONTESTED' is not a known grade"
		// every kill would have been rejected forever.
		Assert.assertEquals("uncontested",
			serialise(kill(Confidence.UNCONTESTED, LootConfidence.CONFIRMED))
				.get("grade").getAsString());

		Assert.assertEquals("ambiguous",
			serialise(kill(Confidence.AMBIGUOUS, LootConfidence.CONFIRMED))
				.get("grade").getAsString());
	}

	@Test
	public void lootConfidenceGoesOutLowercase()
	{
		Assert.assertEquals("confirmed",
			serialise(kill(Confidence.UNCONTESTED, LootConfidence.CONFIRMED))
				.get("lootConfidence").getAsString());

		Assert.assertEquals("none",
			serialise(kill(Confidence.UNCONTESTED, LootConfidence.NONE))
				.get("lootConfidence").getAsString());
	}

	@Test
	public void signalStaysUppercase()
	{
		// NOT lowercased. the contract prints signal as OBSERVED /
		// DESPAWN_WHILE_DEAD / TRANSFORM_FINISH. one blanket lowercase rule would
		// have looked tidier and broken this.
		Assert.assertEquals("OBSERVED",
			serialise(kill(Confidence.UNCONTESTED, LootConfidence.CONFIRMED))
				.get("signal").getAsString());
	}

	@Test
	public void everyFieldTheContractNamesIsPresent()
	{
		final JsonObject json = serialise(kill(Confidence.UNCONTESTED, LootConfidence.CONFIRMED));

		for (String field : new String[]{
			"eventId", "npcId", "npcName", "combatLevel", "regionId", "grade", "signal",
			"myDamage", "othersDamage", "attacksCount", "hitsCount", "maxHit",
			"timestampMillis", "fightTicks", "drops", "lootConfidence"})
		{
			Assert.assertTrue("contract field missing from the wire: " + field,
				json.has(field));
		}
	}

	@Test
	public void worldTypesReachTheWire()
	{
		// the field only does anything if it survives serialisation. the last wire
		// bug compiled fine and failed only against the live server.
		final KillRecord k = new KillRecord("e1", 7271, "Cyclops", 56, 6556,
			Confidence.UNCONTESTED, DeathSignal.OBSERVED, 75, 0, 9, 7, 17,
			1_756_100_000_000L, Collections.emptyList(), LootConfidence.NONE, 12,
			java.util.Arrays.asList("members", "skill_total"));

		final JsonObject json = serialise(k);
		Assert.assertTrue("worldTypes must be on the wire", json.has("worldTypes"));

		final com.google.gson.JsonArray types = json.getAsJsonArray("worldTypes");
		Assert.assertEquals(2, types.size());
		Assert.assertEquals("members", types.get(0).getAsString());
		Assert.assertEquals("skill_total", types.get(1).getAsString());
	}

	@Test
	public void aPlainFreeWorldSendsAnEmptyArrayNotNull()
	{
		// gage distinguishes [] - "a plain free world" - from null, which means "a
		// client that never told us". sending null for a free world would look like
		// an out-of-date plugin.
		final JsonObject json = serialise(kill(Confidence.UNCONTESTED, LootConfidence.NONE));

		Assert.assertTrue(json.has("worldTypes"));
		Assert.assertTrue(json.get("worldTypes").isJsonArray());
		Assert.assertEquals(0, json.getAsJsonArray("worldTypes").size());
	}

	@Test
	public void thereIsNoPlayerFieldOnTheWire()
	{
		// identity comes from the bearer token, never the row. an rsn appearing here
		// is the failure the whole client-id design exists to prevent.
		final JsonObject json = serialise(kill(Confidence.UNCONTESTED, LootConfidence.CONFIRMED));

		for (String leak : new String[]{"player", "rsn", "username", "accountHash", "account"})
		{
			Assert.assertFalse("the wire must not carry " + leak, json.has(leak));
		}
	}

	@Test
	public void dropsCarryTheFourFieldsIngestReads()
	{
		final JsonArray drops = serialise(kill(Confidence.UNCONTESTED, LootConfidence.CONFIRMED))
			.getAsJsonArray("drops");

		Assert.assertEquals(1, drops.size());

		final JsonObject drop = drops.get(0).getAsJsonObject();
		Assert.assertEquals(532, drop.get("itemId").getAsInt());
		Assert.assertEquals(1, drop.get("quantity").getAsInt());
		Assert.assertEquals("Big bones", drop.get("name").getAsString());
		Assert.assertTrue(drop.has("price"));
	}

	@Test
	public void aBatchSerialisesAsABareArray()
	{
		// "JSON array as the whole body, no envelope."
		final String json = gson.toJson(Collections.singletonList(
			kill(Confidence.UNCONTESTED, LootConfidence.CONFIRMED)));

		Assert.assertTrue("body must start with [", json.startsWith("["));
		Assert.assertTrue("body must end with ]", json.endsWith("]"));
	}

	@Test
	public void noKillFieldCouldEverHoldAName()
	{
		// stronger than checking a few key names: NOTHING on the wire may be a string
		// that isn't one of the three the contract names. a future field called
		// "owner" or "who" would fail this without anyone remembering to add it.
		final JsonObject json = serialise(kill(Confidence.UNCONTESTED, LootConfidence.CONFIRMED));

		final java.util.Set<String> allowedStrings =
			new java.util.HashSet<>(java.util.Arrays.asList(
				"eventId", "npcName", "grade", "signal", "lootConfidence"));

		for (String key : json.keySet())
		{
			if (json.get(key).isJsonPrimitive() && json.get(key).getAsJsonPrimitive().isString())
			{
				Assert.assertTrue(
					"a new string field '" + key + "' is on the wire. if it can hold a "
						+ "player name, the publish opt-in is no longer the only path.",
					allowedStrings.contains(key));
			}
		}
	}
}
