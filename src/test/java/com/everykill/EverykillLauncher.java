/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches RuneLite with Everykill already loaded — an ordinary client with one
 * extra plugin. Run it with {@code ./gradlew run}, or from IntelliJ's gutter.
 */
public class EverykillLauncher
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(EverykillPlugin.class);
		RuneLite.main(args);
	}
}
