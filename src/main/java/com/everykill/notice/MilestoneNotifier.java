/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.notice;

import com.everykill.EverykillConfig;
import com.everykill.model.KillRecord;
import com.everykill.model.NpcStat;
import java.awt.Color;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

/**
 * Tier-1 notices: the ones the plugin can work out by itself — a KC milestone, a
 * first kill. No network, no cross-player data, no drop rates, so they work offline
 * and with uploads off. That is what makes the plugin worth having <i>before</i> it
 * asks for the upload toggle.
 *
 * Ranks and first-ever notices are tiers 2 and 3; they arrive as server-rendered
 * strings in the upload response, in P3.
 *
 * <p><b>Announce at the strict grade</b> — milestones count {@code EXACT} only, so the
 * website's number is never smaller than the one the player was congratulated for.
 *
 * <p><b>Suppression is a display choice, never a data choice.</b> Holding a notice
 * back changes nothing about what was recorded: the kill and the milestone both live
 * in the ledger either way. Only the count of what was held back is surfaced, so a
 * quiet notice level never looks like a broken plugin.
 */
@Singleton
public class MilestoneNotifier
{
	private static final int[] LADDER = {100, 250, 500, 1000, 2500, 5000, 10000};

	/** Provisional. Both of these are desk-chosen and want measuring in P3. */
	private static final long COOLDOWN_MILLIS = 45_000L;
	private static final int SESSION_CAP = 12;

	private static final Color GOLD = new Color(0xff, 0xb8, 0x3d);

	private final ChatMessageManager chatMessageManager;
	private final EverykillConfig config;

	@Getter
	private int suppressedThisSession;

	private long lastShownMillis;
	private int shownThisSession;

	@Inject
	public MilestoneNotifier(ChatMessageManager chatMessageManager, EverykillConfig config)
	{
		this.chatMessageManager = chatMessageManager;
		this.config = config;
	}

	public void startSession()
	{
		shownThisSession = 0;
		suppressedThisSession = 0;
		lastShownMillis = 0L;
	}

	/** @param stat the NPC's all-time stat <i>including</i> this kill */
	public void onKillRecorded(KillRecord kill, NpcStat stat, boolean firstEverForThisNpc)
	{
		if (firstEverForThisNpc)
		{
			raise(new Notice(Notice.Kind.FIRST_KILL,
				"first " + kill.npcName + " kill recorded", 1));
			return;
		}

		if (!config.milestones())
		{
			return;
		}

		// Strict grade only. See the class comment.
		final int exact = stat.exact;
		for (int rung : LADDER)
		{
			if (exact == rung)
			{
				raise(new Notice(Notice.Kind.MILESTONE,
					format(rung) + " " + kill.npcName + " kills", rung));
				return;
			}
		}
	}

	private void raise(Notice notice)
	{
		if (!shouldShow(notice))
		{
			suppressedThisSession++;
			return;
		}

		shownThisSession++;
		lastShownMillis = System.currentTimeMillis();

		if (config.chatNotices())
		{
			post(notice);
		}
	}

	private boolean shouldShow(Notice notice)
	{
		final EverykillConfig.NoticeLevel level = config.noticeLevel();
		if (level == EverykillConfig.NoticeLevel.SILENT)
		{
			return false;
		}

		// A first kill cannot repeat, so it never becomes noise.
		if (notice.kind != Notice.Kind.FIRST_KILL && notice.weight < level.milestoneFloor())
		{
			return false;
		}

		if (shownThisSession >= SESSION_CAP)
		{
			return false;
		}

		return System.currentTimeMillis() - lastShownMillis >= COOLDOWN_MILLIS;
	}

	private void post(Notice notice)
	{
		// CONSOLE, not GAMEMESSAGE — that is Jagex's own channel.
		final String message = new ChatMessageBuilder()
			.append(GOLD, "Everykill: ")
			.append(Color.BLACK, notice.text)
			.build();

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	private static String format(int n)
	{
		if (n >= 1000 && n % 1000 == 0)
		{
			return (n / 1000) + "k";
		}
		if (n >= 1000)
		{
			return String.format("%.1fk", n / 1000.0);
		}
		return String.valueOf(n);
	}

	/** One notice, on its way to being shown or suppressed. */
	private static final class Notice
	{
		public enum Kind
		{
			MILESTONE,
			FIRST_KILL
		}

		private final Kind kind;
		private final String text;

		/** Which rung of the ladder, so the notice level can filter by size. */
		private final int weight;

		Notice(Kind kind, String text, int weight)
		{
			this.kind = kind;
			this.text = text;
			this.weight = weight;
		}
	}
}
