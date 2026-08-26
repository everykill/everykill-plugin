/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.notice;

import com.everykill.EverykillConfig;
import com.everykill.model.KillRecord;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;

/**
 * Hands milestones to Dink, which owns Discord webhooks.
 *
 * <h2>Why this and not our own webhook</h2>
 *
 * Dink already does Discord properly — embeds, screenshots, retries with backoff,
 * forum threads, rate limits. Building a second worse one would duplicate a solved
 * feature the Hub explicitly discourages, and would add a config field that POSTs to
 * any host on the internet, on a plugin whose upload path is already the highest-risk
 * thing reviewers look at.
 *
 * <p>So we send the event and Dink sends the message. The user configures Discord in
 * one place, the place they already have it configured.
 *
 * <h2>The contract</h2>
 *
 * {@code PluginMessage("dink", "notify", data)} — core's own inter-plugin event, so
 * this adds no dependency and works whether Dink is installed or not. An event nobody
 * listens for is simply dropped.
 *
 * <p>Payload keys read from Dink's own source (2026-08-26):
 * <ul>
 *   <li>{@code sourcePlugin} — required; Dink logs and skips without it</li>
 *   <li>{@code text} — required; the message body</li>
 *   <li>{@code title} — optional embed title</li>
 *   <li>{@code fields} — optional list of {@code {name, value, inline}}</li>
 *   <li>{@code imageRequested} — optional; asks Dink for a screenshot</li>
 *   <li>{@code urls} — optional override; <b>we never set it</b>, so Dink uses the
 *       webhook the user already configured. A URL we chose would be a URL the user
 *       did not.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class DinkNotifier
{
	private static final String NAMESPACE = "dink";
	private static final String EVENT = "notify";
	private static final String SOURCE = "Everykill";

	private final EventBus eventBus;
	private final EverykillConfig config;

	@Inject
	public DinkNotifier(EventBus eventBus, EverykillConfig config)
	{
		this.eventBus = eventBus;
		this.config = config;
	}

	/**
	 * Announces a kill-count milestone.
	 *
	 * <p>Only milestones, never every kill. A webhook that fires on all 4,000 gargoyles
	 * is a webhook the user mutes, and then it never fires for the one that mattered.
	 */
	public void milestone(KillRecord kill, int killCount)
	{
		if (!config.dinkNotifications())
		{
			return;
		}

		send(killCount + " " + kill.npcName,
			"**" + killCount + "×** " + kill.npcName,
			Map.of("Kill count", String.valueOf(killCount)));
	}

	/**
	 * Announces a personal-best fight, in ticks.
	 *
	 * <p>Ticks rather than seconds because that is what we measured — the game runs on
	 * 0.6s ticks and converting adds precision we do not have.
	 */
	public void personalBest(KillRecord kill, int ticks)
	{
		if (!config.dinkNotifications())
		{
			return;
		}

		send("Fastest " + kill.npcName,
			"New fastest **" + kill.npcName + "**",
			Map.of("Fight length", ticks + " ticks"));
	}

	private void send(String title, String text, Map<String, String> fields)
	{
		try
		{
			final Map<String, Object> data = new HashMap<>();
			data.put("sourcePlugin", SOURCE);
			data.put("text", text);
			data.put("title", title);

			final java.util.List<Map<String, Object>> embedFields = new java.util.ArrayList<>();
			fields.forEach((name, value) ->
			{
				final Map<String, Object> field = new HashMap<>();
				field.put("name", name);
				field.put("value", value);
				field.put("inline", true);
				embedFields.add(field);
			});
			data.put("fields", embedFields);

			eventBus.post(new PluginMessage(NAMESPACE, EVENT, data));
		}
		catch (RuntimeException e)
		{
			// a notification failing must never interrupt recording a kill.
			log.debug("everykill: could not post to dink", e);
		}
	}
}
