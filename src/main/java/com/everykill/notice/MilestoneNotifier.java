/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.notice;

import com.everykill.EverykillConfig;
import com.everykill.model.KillRecord;
import com.everykill.model.NpcStat;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Deque;
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
 * <p>Two rules here. <b>Announce at the strict grade</b> — milestones count
 * {@code EXACT} only, so the website's number is never smaller than the one the
 * player was congratulated for. <b>Suppression is a display choice</b> — a held-back
 * notice still lands in {@link #getHistory()}.
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
	private final Deque<Notice> history = new ArrayDeque<>();

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
			raise(new Notice(Notice.Kind.FIRST_KILL, kill.npcName,
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
				raise(new Notice(Notice.Kind.MILESTONE, kill.npcName,
					format(rung) + " " + kill.npcName + " kills", rung));
				return;
			}
		}
	}

	private void raise(Notice notice)
	{
		history.addFirst(notice);
		while (history.size() > 50)
		{
			history.removeLast();
		}

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

	/** One notice, shown or suppressed. The panel renders these either way. */
	public static final class Notice
	{
		public enum Kind
		{
			MILESTONE,
			FIRST_KILL
		}

		public final Kind kind;
		public final String npcName;
		public final String text;
		public final int weight;
		public final long whenMillis;

		Notice(Kind kind, String npcName, String text, int weight)
		{
			this.kind = kind;
			this.npcName = npcName;
			this.text = text;
			this.weight = weight;
			this.whenMillis = System.currentTimeMillis();
		}
	}
}
