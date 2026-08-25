/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(EverykillConfig.GROUP)
public interface EverykillConfig extends Config
{
	String GROUP = "everykill";

	// ------------------------------------------------------------------
	@ConfigSection(
		name = "Panel & overlay",
		description = "What is drawn, and where",
		position = 0
	)
	String overlaySection = "overlaySection";

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show overlay",
		description = "Session counter on the game canvas. Off by default - the side panel is the primary surface.",
		position = 1,
		section = overlaySection
	)
	default boolean showOverlay()
	{
		// spec-plugin-ux.md: "Off by default. When on, one compact box." an overlay
		// nobody asked for is clutter on someone else's screen.
		return false;
	}

	@ConfigItem(
		keyName = "compactOverlay",
		name = "Compact overlay",
		description = "One line instead of three.",
		position = 2,
		section = overlaySection
	)
	default boolean compactOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showGradeSplit",
		name = "Show grade split",
		description = "Uncontested / inferred / ambiguous breakdown. Off by default - it is a diagnostic, not a session stat.",
		position = 3,
		section = overlaySection
	)
	default boolean showGradeSplit()
	{
		// off by default, and NOT in the spec's overlay list. seeing "ambiguous 1"
		// appear mid-fight tells you someone is on your target - which is exactly the
		// in-fight signal the hard limits exist to keep out. useful for verifying
		// grading, so it stays available; just not on by default.
		return false;
	}

	// ------------------------------------------------------------------
	@ConfigSection(
		name = "Notices",
		description = "How much it interrupts you",
		position = 10
	)
	String noticeSection = "noticeSection";

	@ConfigItem(
		keyName = "noticeLevel",
		name = "Notice level",
		description = "Everything is noisy. Silent still records everything.",
		position = 11,
		section = noticeSection
	)
	default NoticeLevel noticeLevel()
	{
		return NoticeLevel.NOTABLE_ONLY;
	}

	@ConfigItem(
		keyName = "chatNotices",
		name = "Chat messages",
		description = "Posts notices to your chatbox as a client message.",
		position = 12,
		section = noticeSection
	)
	default boolean chatNotices()
	{
		return true;
	}

	@ConfigItem(
		keyName = "milestones",
		name = "KC milestones",
		description = "100 / 250 / 500 / 1k / 2.5k / 5k / 10k. Counted at uncontested grade only.",
		position = 13,
		section = noticeSection
	)
	default boolean milestones()
	{
		return true;
	}

	// ------------------------------------------------------------------
	@ConfigSection(
		name = "Recording",
		description = "What the plugin records",
		position = 20
	)
	String recordingSection = "recordingSection";

	@ConfigItem(
		keyName = "recordKills",
		name = "Record kills",
		description = "The plugin does nothing at all with this off.",
		position = 21,
		section = recordingSection
	)
	default boolean recordKills()
	{
		return true;
	}

	// ------------------------------------------------------------------
	@ConfigSection(
		name = "Upload & privacy",
		description = "Nothing leaves your machine unless you turn this on",
		position = 30,
		closedByDefault = true
	)
	String uploadSection = "uploadSection";

	/**
	 * Off by default, behind RuneLite's own confirmation dialog.
	 *
	 * <p>That warning string is hub-mandated, word for word, for anything touching a
	 * third-party server. Do not reword it, do not tidy it, do not "improve" it.
	 *
	 * <p>Nothing reads this yet - upload is P3, after delete and export exist. It's
	 * here now purely so nobody can ship the feature and forget the disclosure.
	 */
	@ConfigItem(
		keyName = "uploadEnabled",
		name = "Upload to the site",
		description = "Sends your recorded kills so your profile and hiscore ranks update. Each kill sends: monster id and name, combat level, region, kill grade, your damage and other players' damage, hit counts, fight length, and the items dropped. Your account is identified by a salted hash - your RSN is never sent.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		position = 31,
		section = uploadSection
	)
	default boolean uploadEnabled()
	{
		return false;
	}

	/**
	 * Where kills go.
	 *
	 * <p>Editable because nothing is deployed yet — the reference server runs locally
	 * and this is how it gets pointed at. It carries the same third-party warning as
	 * the toggle: a user-supplied address is exactly the case the warning exists for.
	 */
	@ConfigItem(
		keyName = "uploadUrl",
		name = "Upload address",
		description = "Base address of the Everykill ingest server, e.g. http://127.0.0.1:8790",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		position = 32,
		section = uploadSection
	)
	default String uploadUrl()
	{
		return "";
	}

	enum NoticeLevel
	{
		EVERYTHING("Everything"),
		NOTABLE_ONLY("Notable only"),
		BIG_ONLY("Big only"),
		SILENT("Silent");

		private final String label;

		NoticeLevel(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}

		/**
		 * Smallest milestone allowed to say anything at this level. Everything below
		 * still gets recorded - shutting up isn't the same as not counting.
		 */
		public int milestoneFloor()
		{
			switch (this)
			{
				case EVERYTHING:
					return 0;
				case NOTABLE_ONLY:
					return 500;
				case BIG_ONLY:
					return 2500;
				default:
					return Integer.MAX_VALUE;
			}
		}
	}
}
