/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;

/**
 * The systemic-rejection rule, which inverts the normal one.
 *
 * <p>Ordinarily every verdict — accepted, duplicate, rejected — means drop the record.
 * When the server reports that the WHOLE batch failed for one identical reason, that is
 * a client fault, and dropping those records drains the entire history into nothing
 * inside a 200. This is the exact shape my {@code UNCONTESTED} enum bug would have
 * produced.
 */
public class SystemicRejectTest
{
	private final Gson gson = new Gson();

	private JsonObject parse(String json)
	{
		return gson.fromJson(json, JsonObject.class);
	}

	/** Mirrors UploadClient.systemicReason, which is private. */
	private String reasonOf(JsonObject json)
	{
		if (json == null || !json.has("systemic") || json.get("systemic").isJsonNull())
		{
			return null;
		}
		final JsonObject systemic = json.getAsJsonObject("systemic");
		return systemic.has("reason") ? systemic.get("reason").getAsString() : "client fault";
	}

	@Test
	public void aWholeBatchRejectedForOneReasonIsSystemic()
	{
		// pasted from the server's own response in from-gage-ingest-handbook.md
		final String body = "{\"stored\":true,\"accepted\":0,\"duplicate\":0,\"rejected\":2,"
			+ "\"results\":[{\"eventId\":\"s1\",\"status\":\"rejected\","
			+ "\"reason\":\"grade 'UNCONTESTED' is not a known grade\"},"
			+ "{\"eventId\":\"s2\",\"status\":\"rejected\","
			+ "\"reason\":\"grade 'UNCONTESTED' is not a known grade\"}],"
			+ "\"systemic\":{\"reason\":\"grade 'UNCONTESTED' is not a known grade\",\"count\":2,"
			+ "\"detail\":\"Every record in this batch was rejected for the same reason.\"}}";

		Assert.assertEquals("grade 'UNCONTESTED' is not a known grade", reasonOf(parse(body)));
	}

	@Test
	public void anOrdinaryBatchIsNotSystemic()
	{
		final String body = "{\"stored\":true,\"accepted\":47,\"duplicate\":2,\"rejected\":1,"
			+ "\"results\":[]}";

		Assert.assertNull("a normal batch must drain as usual", reasonOf(parse(body)));
	}

	@Test
	public void aBatchWithOneRejectionIsNotSystemic()
	{
		// one bad record among good ones is bad DATA, not a broken client. dropping
		// it is correct - holding it would park it at the head of the queue forever.
		final String body = "{\"stored\":true,\"accepted\":49,\"duplicate\":0,\"rejected\":1,"
			+ "\"results\":[{\"eventId\":\"x\",\"status\":\"rejected\",\"reason\":\"npcId missing\"}]}";

		Assert.assertNull(reasonOf(parse(body)));
	}

	@Test
	public void aNullSystemicFieldIsNotSystemic()
	{
		// json null is not the same as absent, and gson hands back JsonNull rather
		// than dropping the key.
		Assert.assertNull(reasonOf(parse("{\"stored\":true,\"systemic\":null}")));
	}

	@Test
	public void aSystemicBlockWithoutAReasonStillHalts()
	{
		// halting without knowing why beats uploading into a fault we can't name.
		Assert.assertEquals("client fault",
			reasonOf(parse("{\"stored\":true,\"systemic\":{\"count\":3}}")));
	}

	@Test
	public void aResultCarryingASystemicReasonKeepsNothingAccepted()
	{
		final UploadClient.Result result = UploadClient.Result.systemic("bad grade");

		Assert.assertEquals("bad grade", result.systemicReason);
		Assert.assertEquals(0, result.accepted);
		Assert.assertEquals(0, result.duplicate);
		Assert.assertFalse(result.unauthorised);
	}

	@Test
	public void anOrdinaryResultCarriesNoSystemicReason()
	{
		final UploadClient.Result result = UploadClient.Result.of(47, 2, 1);

		Assert.assertNull(result.systemicReason);
		Assert.assertEquals(47, result.accepted);
	}
}
