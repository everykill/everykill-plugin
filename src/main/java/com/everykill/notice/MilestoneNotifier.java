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
 * Notices we can work out on our own - kc milestones, first kills. No network, no
 * other players, no drop rates. Works offline with uploads off, which is the whole
 * reason anyone would install this before we ever ask them to turn upload on.
 *
 * <p>Ranks and first-ever-on-the-site notices are P3, rendered server side.
 *
 * <p>Milestones count UNCONTESTED only. Congratulate someone on 500 and then show them 480
 * on the site because half were contested and you've lost them for good.
 *
 * <p>Suppressing a notice changes nothing about what got recorded - the kill and the
 * milestone are both in the ledger regardless. We surface the suppressed count so a
 * quiet notice level doesn't look like a broken plugin.
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
	private final DinkNotifier dink;

	@Getter
	private int suppressedThisSession;

	private long lastShownMillis;
	private int shownThisSession;

	@Inject
	public MilestoneNotifier(ChatMessageManager chatMessageManager, EverykillConfig config,
		DinkNotifier dink)
	{
		this.chatMessageManager = chatMessageManager;
		this.config = config;
		this.dink = dink;
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
		final int uncontested = stat.uncontested;
		for (int rung : LADDER)
		{
			if (uncontested == rung)
			{
				raise(new Notice(Notice.Kind.MILESTONE,
					format(rung) + " " + kill.npcName + " kills", rung));
				dink.milestone(kill, rung);
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
