/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.ui;

import com.everykill.ledger.LocalLedger;
import com.everykill.model.Confidence;
import com.everykill.model.NpcStat;
import com.everykill.notice.MilestoneNotifier;
import com.everykill.xp.XpService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.util.List;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The side panel, at RuneLite's fixed 225px.
 *
 * Native {@link ColorScheme} chrome, not the website's palette — what crosses between
 * plugin and site is meaning, not skin, and the three grade colours are the whole
 * shared vocabulary.
 *
 * <p>The panel counts and stops. Rates, ranks and dry streaks need a denominator or
 * another player's data, so they become links rather than numbers.
 */
public class EverykillPanel extends PluginPanel
{
	private static final Color SUBTLE = new Color(0x8e, 0x8e, 0x8e);

	private final LocalLedger ledger;
	private final MilestoneNotifier notifier;
	private final XpService xpService;

	private final JLabel sessionKills = new JLabel("0");
	private final JLabel sessionSub = new JLabel("kills");
	private final JLabel sessionGrades = new JLabel(" ");
	private final JLabel unallocated = new JLabel(" ");
	private final GradeBar sessionBar = new GradeBar();
	private final JPanel monsterList = new JPanel();
	private final JLabel monsterHeader = new JLabel("ALL TIME");
	private final JLabel noticeLabel = new JLabel(" ");

	@Inject
	EverykillPanel(LocalLedger ledger, MilestoneNotifier notifier, XpService xpService)
	{
		super(false);
		this.ledger = ledger;
		this.notifier = notifier;
		this.xpService = xpService;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		body.add(buildSessionBox());
		body.add(javax.swing.Box.createVerticalStrut(6));
		body.add(buildMonsterBox());

		add(body, BorderLayout.NORTH);
	}

	private JPanel buildSessionBox()
	{
		final JPanel box = box();

		final JLabel head = caption("THIS SESSION");

		// RuneLite's three RuneScape faces are all fixed-size — "small" is a different
		// TTF, not a smaller point size — so bold is the only emphasis available.
		sessionKills.setFont(FontManager.getRunescapeBoldFont());
		sessionKills.setForeground(Color.WHITE);

		sessionSub.setFont(FontManager.getRunescapeSmallFont());
		sessionSub.setForeground(SUBTLE);

		sessionGrades.setFont(FontManager.getRunescapeSmallFont());
		sessionGrades.setForeground(SUBTLE);

		// Experience that arrived with no damage on record. Shown rather than folded
		// into the nearest monster — a rising number here means the allocator is
		// wrong, and burying it would hide exactly the bug worth catching.
		unallocated.setFont(FontManager.getRunescapeSmallFont());
		unallocated.setForeground(Confidence.INFERRED.getColor());

		sessionBar.setPreferredSize(new Dimension(200, 3));
		sessionBar.setMaximumSize(new Dimension(Short.MAX_VALUE, 3));

		box.add(head);
		box.add(sessionKills);
		box.add(sessionSub);
		box.add(javax.swing.Box.createVerticalStrut(4));
		box.add(sessionBar);
		box.add(javax.swing.Box.createVerticalStrut(4));
		box.add(sessionGrades);
		box.add(unallocated);

		return box;
	}

	private JPanel buildMonsterBox()
	{
		final JPanel box = box();

		monsterHeader.setFont(FontManager.getRunescapeSmallFont());
		monsterHeader.setForeground(SUBTLE);

		monsterList.setLayout(new BoxLayout(monsterList, BoxLayout.Y_AXIS));
		monsterList.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		noticeLabel.setFont(FontManager.getRunescapeSmallFont());
		noticeLabel.setForeground(SUBTLE);

		box.add(monsterHeader);
		box.add(javax.swing.Box.createVerticalStrut(4));
		box.add(monsterList);
		box.add(javax.swing.Box.createVerticalStrut(6));
		box.add(noticeLabel);

		return box;
	}

	private static JPanel box()
	{
		final JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 8));
		return p;
	}

	private static JLabel caption(String text)
	{
		final JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(SUBTLE);
		return l;
	}

	/** Safe to call from the client thread; hops to Swing itself. */
	public void refresh()
	{
		SwingUtilities.invokeLater(this::rebuild);
	}

	private void rebuild()
	{
		final int kills = ledger.getSessionKills();
		final int exact = ledger.sessionCount(Confidence.EXACT);
		final int inferred = ledger.sessionCount(Confidence.INFERRED);
		final int ambiguous = ledger.sessionCount(Confidence.AMBIGUOUS);

		sessionKills.setText(String.valueOf(kills));

		final long xp = ledger.sessionXp();
		sessionSub.setText(kills == 1
			? "kill" + (xp > 0 ? " · " + shortXp(xp) + " xp" : "")
			: "kills" + (xp > 0 ? " · " + shortXp(xp) + " xp" : ""));
		sessionBar.set(exact, inferred, ambiguous);

		if (kills == 0)
		{
			sessionGrades.setText("nothing yet");
		}
		else
		{
			final StringBuilder sb = new StringBuilder("<html>");
			sb.append(span(Confidence.EXACT, exact + " exact"));
			if (inferred > 0)
			{
				sb.append(" · ").append(span(Confidence.INFERRED, inferred + " inferred"));
			}
			if (ambiguous > 0)
			{
				sb.append(" · ").append(span(Confidence.AMBIGUOUS, ambiguous + " ambiguous"));
			}
			sessionGrades.setText(sb.append("</html>").toString());
		}

		final long stray = xpService.getUnallocatedXp();
		unallocated.setText(stray == 0L ? " " : shortXp(stray) + " xp unattributed");

		monsterList.removeAll();
		final List<NpcStat> all = ledger.allTimeSorted();
		monsterHeader.setText("ALL TIME · " + all.size());

		int shown = 0;
		for (NpcStat stat : all)
		{
			if (shown++ >= 12)
			{
				break;
			}
			monsterList.add(row(stat));
		}

		if (all.isEmpty())
		{
			monsterList.add(caption("No kills recorded yet."));
		}

		final int suppressed = notifier.getSuppressedThisSession();
		noticeLabel.setText(suppressed == 0
			? " "
			: "<html>" + suppressed + " notice" + (suppressed == 1 ? "" : "s")
				+ " suppressed by your notice level. Nothing is lost.</html>");

		revalidate();
		repaint();
	}

	private JPanel row(NpcStat stat)
	{
		final JPanel p = new JPanel(new BorderLayout());
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

		final JLabel name = new JLabel(stat.name == null ? "Unknown" : stat.name);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.TEXT_COLOR);

		final JLabel count = new JLabel(String.valueOf(stat.total()));
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(Color.WHITE);

		p.add(name, BorderLayout.WEST);
		p.add(count, BorderLayout.EAST);

		final JPanel wrap = new JPanel(new GridLayout(2, 1, 0, 1));
		wrap.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrap.add(p);

		final GradeBar bar = new GradeBar();
		bar.set(stat.exact, stat.inferred, stat.ambiguous);
		bar.setPreferredSize(new Dimension(200, 3));
		wrap.add(bar);

		return wrap;
	}

	private static String shortXp(long xp)
	{
		if (xp >= 1_000_000L)
		{
			return String.format("%.1fm", xp / 1_000_000.0);
		}
		if (xp >= 1_000L)
		{
			return String.format("%.1fk", xp / 1_000.0);
		}
		return String.valueOf(xp);
	}

	private static String span(Confidence grade, String text)
	{
		final Color c = grade.getColor();
		return String.format("<font color='#%02x%02x%02x'>%s</font>",
			c.getRed(), c.getGreen(), c.getBlue(), text);
	}

	/** The 3px grade split — foundation, not storefront. The count is the headline. */
	private static final class GradeBar extends JPanel
	{
		private int exact;
		private int inferred;
		private int ambiguous;

		private GradeBar()
		{
			setBackground(new Color(0x14, 0x14, 0x14));
		}

		private void set(int exact, int inferred, int ambiguous)
		{
			this.exact = exact;
			this.inferred = inferred;
			this.ambiguous = ambiguous;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);

			final int total = exact + inferred + ambiguous;
			if (total <= 0)
			{
				return;
			}

			final int w = getWidth();
			int x = 0;

			final int we = Math.round(w * (exact / (float) total));
			g.setColor(Confidence.EXACT.getColor());
			g.fillRect(x, 0, we, getHeight());
			x += we;

			final int wi = Math.round(w * (inferred / (float) total));
			g.setColor(Confidence.INFERRED.getColor());
			g.fillRect(x, 0, wi, getHeight());
			x += wi;

			g.setColor(Confidence.AMBIGUOUS.getColor());
			g.fillRect(x, 0, w - x, getHeight());
		}
	}
}
