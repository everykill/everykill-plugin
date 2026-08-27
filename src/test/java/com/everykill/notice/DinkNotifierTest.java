/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.notice;

import com.everykill.EverykillConfig;
import com.everykill.model.Confidence;
import com.everykill.model.DeathSignal;
import com.everykill.model.KillRecord;
import com.everykill.model.LootConfidence;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * The Dink handoff.
 *
 * <p>The payload shape is checked against Dink's own required keys, read from its
 * source: it logs and skips a request missing {@code sourcePlugin} or {@code text}, so
 * getting either wrong means silence rather than an error.
 */
public class DinkNotifierTest
{
	private List<PluginMessage> posted;
	private EventBus bus;

	@Before
	public void setUp()
	{
		posted = new ArrayList<>();
		bus = new EventBus()
		{
			@Override
			public void post(Object event)
			{
				if (event instanceof PluginMessage)
				{
					posted.add((PluginMessage) event);
				}
			}
		};
	}

	private EverykillConfig config(boolean enabled)
	{
		return (EverykillConfig) java.lang.reflect.Proxy.newProxyInstance(
			EverykillConfig.class.getClassLoader(),
			new Class<?>[]{EverykillConfig.class},
			(proxy, method, args) ->
			{
				if ("dinkNotifications".equals(method.getName()))
				{
					return enabled;
				}
				if (method.isDefault())
				{
					return java.lang.invoke.MethodHandles.lookup()
						.findSpecial(EverykillConfig.class, method.getName(),
							java.lang.invoke.MethodType.methodType(method.getReturnType(),
								method.getParameterTypes()),
							EverykillConfig.class)
						.bindTo(proxy).invokeWithArguments(args);
				}
				return null;
			});
	}

	private KillRecord kill()
	{
		return new KillRecord("evt-1", 7271, "Cyclops", 56, 6556,
			Confidence.UNCONTESTED, DeathSignal.OBSERVED,
			75, 0, 9, 7, 17, 1_700_000_000_000L,
			Collections.emptyList(), LootConfidence.CONFIRMED, 12, Collections.emptyList());
	}

	@Test
	public void aMilestoneCarriesDinksRequiredKeys()
	{
		new DinkNotifier(bus, config(true)).milestone(kill(), 100);

		Assert.assertEquals(1, posted.size());
		final PluginMessage m = posted.get(0);

		// exact strings from dink's own tests - a typo here is silence, not an error
		Assert.assertEquals("dink", m.getNamespace());
		Assert.assertEquals("notify", m.getName());

		final Map<String, Object> data = m.getData();
		Assert.assertEquals("Everykill", data.get("sourcePlugin"));
		Assert.assertNotNull("dink skips a request with no text", data.get("text"));
		Assert.assertTrue(data.get("text").toString().contains("Cyclops"));
		Assert.assertTrue(data.get("text").toString().contains("100"));
	}

	@Test
	public void weNeverSetTheWebhookUrl()
	{
		// omitting 'urls' makes dink use the webhook the USER configured. setting one
		// would mean the plugin choosing where a player's data goes.
		new DinkNotifier(bus, config(true)).milestone(kill(), 250);

		Assert.assertFalse("the url is the user's business",
			posted.get(0).getData().containsKey("urls"));
	}

	@Test
	public void nothingIsPostedWhenTheToggleIsOff()
	{
		new DinkNotifier(bus, config(false)).milestone(kill(), 500);

		Assert.assertTrue("off means off", posted.isEmpty());
	}

	@Test
	public void aPersonalBestReportsTicksNotSeconds()
	{
		new DinkNotifier(bus, config(true)).personalBest(kill(), 47);

		@SuppressWarnings("unchecked")
		final List<Map<String, Object>> fields =
			(List<Map<String, Object>>) posted.get(0).getData().get("fields");

		Assert.assertEquals("47 ticks", fields.get(0).get("value"));
	}

	@Test
	public void noScreenshotIsRequested()
	{
		// asking for a screenshot of someone's client is not ours to ask for by
		// default. dink can be configured to add one; we don't request it.
		new DinkNotifier(bus, config(true)).milestone(kill(), 1000);

		Assert.assertFalse(posted.get(0).getData().containsKey("imageRequested"));
	}
}
