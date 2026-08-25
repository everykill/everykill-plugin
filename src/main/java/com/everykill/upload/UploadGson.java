/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.upload;

import com.everykill.model.Confidence;
import com.everykill.model.DeathSignal;
import com.everykill.model.LootConfidence;
import com.google.gson.Gson;
import com.google.gson.JsonPrimitive;
import java.util.Locale;

/**
 * The Gson the wire uses.
 *
 * <p>Exists for one reason: Java enum constants are {@code UNCONTESTED} and the
 * contract reads {@code uncontested}. Default Gson emits the constant name, so every
 * record was rejected with {@code "grade 'UNCONTESTED' is not a known grade"} — caught
 * against the live server, not in review, because the contract and the serialiser were
 * never checked against each other.
 *
 * <p>{@code DeathSignal} stays upper case: the contract prints it as
 * {@code OBSERVED / DESPAWN_WHILE_DEAD / TRANSFORM_FINISH} and it is diagnostic only.
 * Lower-casing everything with one rule would have been the tidier-looking mistake.
 */
final class UploadGson
{
	private UploadGson()
	{
	}

	/**
	 * Derives from the INJECTED Gson via {@code newBuilder()}, per CONVENTIONS —
	 * never {@code new Gson()}.
	 */
	static Gson wire(Gson base)
	{
		return base.newBuilder()
			.registerTypeAdapter(Confidence.class,
				(com.google.gson.JsonSerializer<Confidence>) (src, type, ctx) ->
					new JsonPrimitive(src.name().toLowerCase(Locale.ROOT)))
			.registerTypeAdapter(LootConfidence.class,
				(com.google.gson.JsonSerializer<LootConfidence>) (src, type, ctx) ->
					new JsonPrimitive(src.name().toLowerCase(Locale.ROOT)))
			.registerTypeAdapter(DeathSignal.class,
				(com.google.gson.JsonSerializer<DeathSignal>) (src, type, ctx) ->
					new JsonPrimitive(src.name()))
			.create();
	}
}
