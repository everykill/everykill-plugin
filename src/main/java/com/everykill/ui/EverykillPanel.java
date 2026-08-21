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
import java.awt.Cursor;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel. RuneLite gives us 225px and that's that.
 *
 * <p>Native {@link ColorScheme} chrome, not the site's palette. What we share with the
 * site is meaning, not styling - the three grade colours are the entire shared
 * vocabulary and that's deliberate.
 *
 * <p>It counts and then stops. Rates, ranks, dry streaks all need a denominator or
 * someone else's data, so they're links, not numbers.
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
	private final MaterialTabGroup tabs = new MaterialTabGroup();

	// npc ids whose skill breakdown is open. panel state, never persisted.
	private final Set<Integer> expanded = new HashSet<>();

	private Window window = Window.ALL;

	/** How far back the list looks. SESSION is the live one; the rest read day buckets. */
	private enum Window
	{
		SESSION("Now", "This session", 0),
		TODAY("Day", "Today", 1),
		WEEK("Wk", "This week", 7),
		MONTH("Mth", "This month", 30),
		ALL("All", "All time", 0);

		// short on the tab because five labels share 225px; the real name goes in the
		// tooltip and the header below, so nothing is guessed from three letters.
		private final String label;
		private final String tooltip;
		private final int days;

		Window(String label, String tooltip, int days)
		{
			this.label = label;
			this.tooltip = tooltip;
			this.days = days;
		}
	}

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

		// xp that went missing WHILE we were fighting something. teleports and alching
		// are deliberately excluded - they're magic xp with no monster attached and
		// they're normal, so counting them here would bury the real signal under noise.
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

		// MaterialTabGroup rather than a JComboBox. A stock combo box renders with a
		// white popup and black text in the middle of a dark panel and looks exactly as
		// bad as that sounds. This is what core's own panels use.
		tabs.setLayout(new java.awt.GridLayout(1, Window.values().length, 2, 0));
		tabs.setMaximumSize(new Dimension(Short.MAX_VALUE, 22));

		for (Window w : Window.values())
		{
			final MaterialTab tab = new MaterialTab(w.label, tabs, null);
			tab.setFont(FontManager.getRunescapeSmallFont());
			tab.setToolTipText(w.tooltip);
			tab.setOnSelectEvent(() ->
			{
				window = w;
				rebuild();
				return true;
			});
			tabs.addTab(tab);

			if (w == window)
			{
				tabs.select(tab);
			}
		}

		box.add(tabs);
		box.add(javax.swing.Box.createVerticalStrut(6));
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

		final long stranded = xpService.getStrandedXp();
		unallocated.setText(stranded == 0L ? " " : shortXp(stranded) + " xp unattributed");

		monsterList.removeAll();
		final List<NpcStat> rows = statsForWindow();
		monsterHeader.setText(window.tooltip.toUpperCase() + " · " + rows.size());

		int shown = 0;
		for (NpcStat stat : rows)
		{
			if (shown++ >= 12)
			{
				break;
			}
			monsterList.add(row(stat));
		}

		if (rows.isEmpty())
		{
			monsterList.add(caption(window == Window.ALL
				? "No kills recorded yet."
				: "Nothing killed in this period."));
		}

		final int suppressed = notifier.getSuppressedThisSession();
		noticeLabel.setText(suppressed == 0
			? " "
			: "<html>" + suppressed + " notice" + (suppressed == 1 ? "" : "s")
				+ " suppressed by your notice level. Nothing is lost.</html>");

		revalidate();
		repaint();
	}

	/** Rows for whatever window is picked, biggest first, empties dropped. */
	private List<NpcStat> statsForWindow()
	{
		if (window == Window.SESSION)
		{
			final List<NpcStat> out = new ArrayList<>(ledger.getSession().values());
			out.sort(Comparator.comparingInt(NpcStat::total).reversed());
			return out;
		}

		if (window == Window.ALL)
		{
			return ledger.allTimeSorted();
		}

		final List<NpcStat> out = new ArrayList<>();
		for (NpcStat stat : ledger.allTimeSorted())
		{
			if (stat.totalSince(window.days) > 0)
			{
				out.add(stat);
			}
		}
		out.sort(Comparator.comparingInt((NpcStat s) -> s.totalSince(window.days)).reversed());
		return out;
	}

	private int countFor(NpcStat stat)
	{
		return window == Window.SESSION || window == Window.ALL
			? stat.total()
			: stat.totalSince(window.days);
	}

	private JPanel row(NpcStat stat)
	{
		// the per-skill split is all-time and session only. day buckets keep a total,
		// not a breakdown, so a windowed row has nothing honest to expand into.
		final boolean windowed = window != Window.ALL && window != Window.SESSION;
		final boolean hasSkills = !windowed && stat.xpBySkill != null && !stat.xpBySkill.isEmpty();
		final boolean open = expanded.contains(stat.npcId);

		final JPanel p = new JPanel(new BorderLayout());
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

		final JLabel name = new JLabel((hasSkills ? (open ? "▾ " : "▸ ") : "") + label(stat));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(ColorScheme.TEXT_COLOR);

		final JLabel count = new JLabel(String.valueOf(countFor(stat)));
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(Color.WHITE);

		p.add(name, BorderLayout.WEST);
		p.add(count, BorderLayout.EAST);

		final JPanel wrap = new JPanel();
		wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
		wrap.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrap.setToolTipText(tooltip(stat));
		wrap.add(p);

		// only draw the bar when the grades actually differ. a solid green line under
		// every row is decoration; drawing it only for mixed rows makes the ones worth
		// looking at jump out instead of hiding in a wall of identical bars.
		if (isMixed(stat))
		{
			final GradeBar bar = new GradeBar();
			bar.set(stat.exact, stat.inferred, stat.ambiguous);
			bar.setPreferredSize(new Dimension(200, 3));
			bar.setMaximumSize(new Dimension(Short.MAX_VALUE, 3));
			wrap.add(bar);
		}

		if (hasSkills)
		{
			p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			p.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (!expanded.remove(stat.npcId))
					{
						expanded.add(stat.npcId);
					}
					rebuild();
				}
			});

			if (open)
			{
				// biggest first. nobody scans an alphabetical list looking for where
				// their xp went.
				stat.xpBySkill.entrySet().stream()
					.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
					.forEach(e -> wrap.add(skillLine(e.getKey(), e.getValue())));
			}
		}

		return wrap;
	}

	private static JLabel skillLine(String skill, long xp)
	{
		final JLabel l = new JLabel(pretty(skill) + "  " + shortXp(xp));
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(SUBTLE);
		l.setBorder(BorderFactory.createEmptyBorder(0, 10, 1, 0));
		return l;
	}

	/** HITPOINTS -> Hitpoints. The enum shouts; the panel shouldn't. */
	private static String pretty(String skill)
	{
		return skill.charAt(0) + skill.substring(1).toLowerCase();
	}

	/** "Dagannoth (74)" — two ids, one name, and no way to tell them apart without this. */
	private static String label(NpcStat stat)
	{
		final String name = stat.name == null ? "Unknown" : stat.name;
		return stat.combatLevel > 0 ? name + " (" + stat.combatLevel + ")" : name;
	}

	private static boolean isMixed(NpcStat stat)
	{
		int grades = 0;
		if (stat.exact > 0)
		{
			grades++;
		}
		if (stat.inferred > 0)
		{
			grades++;
		}
		if (stat.ambiguous > 0)
		{
			grades++;
		}
		return grades > 1;
	}

	/** Raw npc_id lives here rather than in the row. Available, not in the way. */
	private static String tooltip(NpcStat stat)
	{
		final StringBuilder sb = new StringBuilder("<html>");
		sb.append("npc_id ").append(stat.npcId);
		if (stat.combatLevel > 0)
		{
			sb.append(" &middot; level ").append(stat.combatLevel);
		}
		sb.append("<br>").append(span(Confidence.EXACT, stat.exact + " exact"));
		if (stat.inferred > 0)
		{
			sb.append("<br>").append(span(Confidence.INFERRED, stat.inferred + " inferred"));
		}
		if (stat.ambiguous > 0)
		{
			sb.append("<br>").append(span(Confidence.AMBIGUOUS, stat.ambiguous + " ambiguous"));
		}
		if (stat.xp > 0)
		{
			sb.append("<br>").append(shortXp(stat.xp)).append(" xp");
		}
		return sb.append("</html>").toString();
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
