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
		description = "Session counter on the game canvas.",
		position = 1,
		section = overlaySection
	)
	default boolean showOverlay()
	{
		return true;
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
		description = "Exact / inferred / ambiguous breakdown on the overlay.",
		position = 3,
		section = overlaySection
	)
	default boolean showGradeSplit()
	{
		return true;
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
		description = "Sends your recorded kills so your profile and hiscore ranks update.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		position = 31,
		section = uploadSection
	)
	default boolean uploadEnabled()
	{
		return false;
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
