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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import net.runelite.client.util.LinkBrowser;
import okhttp3.HttpUrl;
import net.runelite.client.game.ItemManager;
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
	// core's own supporting-text grey. was a hand-picked 0x8e8e8e, which is the same
	// idea two shades off - matching ColorScheme is how the panel looks native.
	private static final Color SUBTLE = ColorScheme.LIGHT_GRAY_COLOR;

	private final LocalLedger ledger;
	private final MilestoneNotifier notifier;
	private final XpService xpService;

	// for drop icons. getImage is async - addTo(label) repaints when it lands, so
	// nothing blocks swing. same call LootTrackerBox makes.
	private final ItemManager itemManager;

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
	EverykillPanel(LocalLedger ledger, MilestoneNotifier notifier, XpService xpService,
		ItemManager itemManager)
	{
		super(false);
		this.ledger = ledger;
		this.notifier = notifier;
		this.xpService = xpService;
		this.itemManager = itemManager;

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
		monsterHeader.setAlignmentX(LEFT_ALIGNMENT);

		monsterList.setLayout(new BoxLayout(monsterList, BoxLayout.Y_AXIS));
		monsterList.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		noticeLabel.setFont(FontManager.getRunescapeSmallFont());
		noticeLabel.setForeground(SUBTLE);

		// MaterialTabGroup rather than a JComboBox. A stock combo box renders with a
		// white popup and black text in the middle of a dark panel and looks exactly as
		// bad as that sounds. This is what core's own panels use.
		// MaterialTab hardcodes a 10px empty border each side. Five of those in 225px
		// leaves ~24px for text, and "Now" needs 31 - measured, not guessed - so it
		// rendered as "N...". Narrowing the gaps doesn't help: the padding is the
		// problem, so the border gets replaced below.
		tabs.setLayout(new java.awt.GridLayout(1, Window.values().length, 1, 0));
		tabs.setMaximumSize(new Dimension(Short.MAX_VALUE, 22));

		// BoxLayout lines children up by alignmentX relative to EACH OTHER, so one
		// component at LEFT and the rest at the 0.5 default lands everything in a
		// compromise position. they all have to agree or none of them do.
		tabs.setAlignmentX(LEFT_ALIGNMENT);
		monsterList.setAlignmentX(LEFT_ALIGNMENT);
		noticeLabel.setAlignmentX(LEFT_ALIGNMENT);

		for (Window w : Window.values())
		{
			final MaterialTab tab = new MaterialTab(w.label, tabs, null);
			tab.setFont(FontManager.getRunescapeSmallFont());
			tab.setToolTipText(w.tooltip);
			tab.setOnSelectEvent(() ->
			{
				window = w;
				rebuild();

				// core sets its own border AFTER this returns - select() calls the
				// event first, then setBorder(SELECTED_BORDER) - so narrowing it here
				// would be overwritten. queue it behind that.
				SwingUtilities.invokeLater(() -> narrow(tab, true));
				return true;
			});
			tabs.addTab(tab);

			if (w == window)
			{
				tabs.select(tab);
			}

			// unselected tabs get it straight away; the selected one is queued above.
			narrow(tab, w == window);
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
		final int uncontested = ledger.sessionCount(Confidence.UNCONTESTED);
		final int inferred = ledger.sessionCount(Confidence.INFERRED);
		final int ambiguous = ledger.sessionCount(Confidence.AMBIGUOUS);

		sessionKills.setText(String.valueOf(kills));

		final long xp = ledger.sessionXp();
		sessionSub.setText(kills == 1
			? "kill" + (xp > 0 ? " · " + shortXp(xp) + " xp" : "")
			: "kills" + (xp > 0 ? " · " + shortXp(xp) + " xp" : ""));
		sessionBar.set(uncontested, inferred, ambiguous);

		if (kills == 0)
		{
			sessionGrades.setText("nothing yet");
		}
		else
		{
			final StringBuilder sb = new StringBuilder("<html>");
			sb.append(span(Confidence.UNCONTESTED, uncontested + " uncontested"));
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
			// the first thing every new user sees, and it used to be one sentence.
			for (String line : window == Window.ALL
				? new String[]{
					"Nothing counted yet.",
					"",
					"Everykill counts every monster you",
					"kill, not just the ~90 on the hiscores.",
					"",
					"Go hit something."}
				: new String[]{"Nothing killed in this period."})
			{
				monsterList.add(caption(line.isEmpty() ? " " : line));
			}
		}

		final int suppressed = notifier.getSuppressedThisSession();
		noticeLabel.setText(suppressed == 0
			? " "
			: "<html>" + suppressed + " notice" + (suppressed == 1 ? "" : "s")
				+ " suppressed by your notice level. Nothing is lost.</html>");

		revalidate();
		repaint();
	}

	/**
	 * Rebuilds a tab's border with 2px sides instead of core's 10.
	 *
	 * <p>{@code MaterialTab} pads 10px each side. Five tabs in a 225px panel leaves
	 * about 24px of text room and "Now" needs 31, so the first tab rendered as "N...".
	 * Gap tuning cannot fix that — the padding is the whole budget.
	 *
	 * <p>The selected form keeps core's orange underline, because that stripe is the
	 * only thing showing which window you're looking at.
	 */
	private static void narrow(MaterialTab tab, boolean selected)
	{
		tab.setBorder(selected
			? new CompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.BRAND_ORANGE),
				BorderFactory.createEmptyBorder(5, 2, 4, 2))
			: BorderFactory.createEmptyBorder(5, 2, 5, 2));
	}

	/**
	 * Folds rows that are the same monster wearing different ids.
	 *
	 * <p>Returns synthetic {@link NpcStat}s rather than a new type so every row-drawing
	 * path downstream is untouched. Nothing here is written back — the ledger keeps raw
	 * ids forever and this is a read-time view, per {@code PROJECT.md}.
	 */
	private static List<NpcStat> rollUp(List<NpcStat> stats)
	{
		final Map<String, NpcStat> byKey = new LinkedHashMap<>();
		final Map<String, Integer> biggest = new HashMap<>();

		for (NpcStat stat : stats)
		{
			final String key = (stat.name == null ? "?" : stat.name) + "\u0000" + stat.combatLevel;
			final NpcStat into = byKey.get(key);

			if (into == null)
			{
				byKey.put(key, copyOf(stat));
				biggest.put(key, stat.total());
				continue;
			}

			absorb(into, stat);

			// keep the id of whichever one the player actually killed most, so
			// expanding the row lands somewhere real.
			if (stat.total() > biggest.get(key))
			{
				biggest.put(key, stat.total());
				into.npcId = stat.npcId;
			}
		}

		return new ArrayList<>(byKey.values());
	}

	private static NpcStat copyOf(NpcStat from)
	{
		final NpcStat out = new NpcStat(from.npcId, from.name);
		out.combatLevel = from.combatLevel;
		absorb(out, from);
		return out;
	}

	private static void absorb(NpcStat into, NpcStat from)
	{
		into.uncontested += from.uncontested;
		into.inferred += from.inferred;
		into.ambiguous += from.ambiguous;
		into.xp += from.xp;
		into.myDamageTotal += from.myDamageTotal;
		into.othersDamageTotal += from.othersDamageTotal;
		into.killsWithDamage += from.killsWithDamage;

		if (from.firstKillMillis > 0
			&& (into.firstKillMillis == 0 || from.firstKillMillis < into.firstKillMillis))
		{
			into.firstKillMillis = from.firstKillMillis;
		}
		into.lastKillMillis = Math.max(into.lastKillMillis, from.lastKillMillis);

		if (from.xpBySkill != null)
		{
			if (into.xpBySkill == null)
			{
				into.xpBySkill = new HashMap<>();
			}
			from.xpBySkill.forEach((skill, xp) -> into.xpBySkill.merge(skill, xp, Long::sum));
		}

		if (from.drops != null)
		{
			if (into.drops == null)
			{
				into.drops = new HashMap<>();
			}
			from.drops.forEach((item, tally) ->
			{
				final NpcStat.DropTally target =
					into.drops.computeIfAbsent(item, k -> new NpcStat.DropTally());

				// the name too. leaving it out is why the panel showed "item 532"
				// while the saved ledger held "Big bones" - every row the panel draws
				// comes through this merge, including single-id ones.
				if (tally.name != null)
				{
					target.name = tally.name;
				}
				target.quantity += tally.quantity;
				target.drops += tally.drops;

				// the most recent sighting across the merged ids wins. a dry streak on
				// a rolled-up row is "since ANY of these dropped it", which is what a
				// player killing lesser demons of four ids actually wants to know.
				if (tally.lastMillis > target.lastMillis)
				{
					target.lastMillis = tally.lastMillis;
					target.killCountAtLast = tally.killCountAtLast;
				}
			});
		}

		if (from.days != null)
		{
			if (into.days == null)
			{
				into.days = new HashMap<>();
			}
			from.days.forEach((day, tally) ->
			{
				final NpcStat.DayTally target =
					into.days.computeIfAbsent(day, k -> new NpcStat.DayTally());
				target.uncontested += tally.uncontested;
				target.inferred += tally.inferred;
				target.ambiguous += tally.ambiguous;
				target.xp += tally.xp;
				target.myDamage += tally.myDamage;
				target.othersDamage += tally.othersDamage;
				target.killsWithDamage += tally.killsWithDamage;
			});
		}
	}

	/** Rows for whatever window is picked, biggest first, empties dropped. */
	private List<NpcStat> statsForWindow()
	{
		if (window == Window.SESSION)
		{
			final List<NpcStat> out = rollUp(new ArrayList<>(ledger.getSession().values()));
			out.sort(Comparator.comparingInt(NpcStat::total).reversed());
			return out;
		}

		if (window == Window.ALL)
		{
			final List<NpcStat> out = rollUp(ledger.allTimeSorted());
			out.sort(Comparator.comparingInt(NpcStat::total).reversed());
			return out;
		}

		final List<NpcStat> out = new ArrayList<>();
		for (NpcStat stat : rollUp(ledger.allTimeSorted()))
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

	/**
	 * The title-strip background. Core uses {@code DARKER_GRAY_COLOR.darker()} on every
	 * LootTrackerBox header so an entry reads as one object rather than adjacent lines.
	 */
	private static final Color TITLE_BG = ColorScheme.DARKER_GRAY_COLOR.darker();

	/**
	 * Background for expanded detail. Slightly lifted off the row body so nesting is
	 * visible - an 8px indent alone left skill and drop lines floating with nothing
	 * tying them to their monster.
	 */
	private static final Color NEST_BG = new Color(38, 38, 38);

	/**
	 * Highlights a whole row on hover.
	 *
	 * <p>Recurses into children because a row that lights up in pieces looks broken -
	 * {@code GrandExchangeItemPanel.matchComponentBackground} does the same. The title
	 * strip keeps its own darker shade, so it is passed separately rather than being
	 * flattened to match the body.
	 */
	private static void hoverable(JPanel row, JPanel title)
	{
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				paint(ColorScheme.DARK_GRAY_HOVER_COLOR, ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				paint(ColorScheme.DARKER_GRAY_COLOR, TITLE_BG);
			}

			private void paint(Color body, Color header)
			{
				row.setBackground(body);
				for (Component c : row.getComponents())
				{
					if (c == title)
					{
						continue;
					}
					c.setBackground(body);
				}
				title.setBackground(header);
			}
		});
	}

	/**
	 * What this pile is worth, for sorting. Zero when the price is unknown.
	 *
	 * <p>Sorting on value rather than quantity is the difference between a drop list
	 * that opens on the interesting item and one that opens on four hundred bones.
	 */
	private long valueOf(String itemId, NpcStat.DropTally tally)
	{
		try
		{
			return (long) itemManager.getItemPrice(Integer.parseInt(itemId)) * tally.quantity;
		}
		catch (RuntimeException e)
		{
			return 0L;
		}
	}

	/** Opens the wiki page for an npc or item id. */
	private static void wiki(String type, int id, String name)
	{
		// Special:Lookup resolves by ID, which matters here - "Lesser demon" is eight
		// npc ids and a name search would land on whichever the wiki prefers. core's
		// WikiPlugin builds the same url.
		final HttpUrl url = HttpUrl.get("https://oldschool.runescape.wiki").newBuilder()
			.addPathSegments("w/Special:Lookup")
			.addQueryParameter("type", type)
			.addQueryParameter("id", String.valueOf(id))
			.addQueryParameter("name", name == null ? "" : name)
			.addQueryParameter("utm_source", "runelite")
			.build();

		LinkBrowser.browse(url.toString());
	}

	/** Right-click menu with a wiki link, so a left click still expands the row. */
	private static void wikiMenu(JComponent on, String type, int id, String name)
	{
		final JPopupMenu menu = new JPopupMenu();
		menu.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));

		final JMenuItem open = new JMenuItem("Wiki");
		open.addActionListener(e -> wiki(type, id, name));
		menu.add(open);

		on.setComponentPopupMenu(menu);
	}

	/** 1.2m, 340k, 900. Long numbers in a 225px panel are unreadable. */
	private static String gp(long amount)
	{
		if (amount >= 1_000_000L)
		{
			return (amount / 100_000L) / 10.0 + "m";
		}
		if (amount >= 1_000L)
		{
			return (amount / 100L) / 10.0 + "k";
		}
		return String.valueOf(amount);
	}

	/** A label/value pair inside an expanded row. */
	private static JPanel detailLine(String label, String value)
	{
		final JPanel p = new JPanel(new BorderLayout());
		p.setBackground(NEST_BG);
		p.setBorder(BorderFactory.createEmptyBorder(1, 7, 1, 7));
		p.setMaximumSize(new Dimension(Short.MAX_VALUE, 14));
		p.setAlignmentX(LEFT_ALIGNMENT);

		final JLabel l = new JLabel(label);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);

		final JLabel v = new JLabel(value);
		v.setFont(FontManager.getRunescapeSmallFont());
		v.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		p.add(l, BorderLayout.WEST);
		p.add(v, BorderLayout.EAST);
		return p;
	}

	/** "3d ago", "2h ago". Absolute timestamps mean nothing at a glance. */
	private static String ago(long millis)
	{
		final long mins = (System.currentTimeMillis() - millis) / 60_000L;
		if (mins < 60)
		{
			return Math.max(mins, 0) + "m ago";
		}
		if (mins < 1440)
		{
			return (mins / 60) + "h ago";
		}
		return (mins / 1440) + "d ago";
	}

	private JPanel row(NpcStat stat)
	{
		// the per-skill split is all-time and session only. day buckets keep a total,
		// not a breakdown, so a windowed row has nothing honest to expand into.
		final boolean windowed = window != Window.ALL && window != Window.SESSION;
		final boolean hasSkills = !windowed && stat.xpBySkill != null && !stat.xpBySkill.isEmpty();
		final boolean hasDrops = !windowed && stat.drops != null && !stat.drops.isEmpty();
		final boolean expandable = hasSkills || hasDrops;
		final boolean open = expanded.contains(stat.npcId);

		// the title strip. core gives every LootTrackerBox entry its own darker header
		// with real padding, and that's the whole reason their lists read as rows and
		// ours read as a wall - see the panel research in FINDINGS.
		final JPanel p = new JPanel(new BorderLayout());
		p.setBackground(TITLE_BG);
		p.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));

		final JLabel name = new JLabel((expandable ? (open ? "▾ " : "▸ ") : "") + label(stat));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);

		final JLabel count = new JLabel(String.valueOf(countFor(stat)));
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		p.add(name, BorderLayout.WEST);
		p.add(count, BorderLayout.EAST);

		final JPanel wrap = new JPanel();
		wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
		wrap.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		wrap.setToolTipText(tooltip(stat));
		wrap.add(p);

		// hover the whole row, not just the label under the pointer. core recurses into
		// children for exactly this reason - a row that highlights in pieces looks
		// broken.
		hoverable(wrap, p);

		// right-click rather than left, so the left click still expands the row.
		wikiMenu(wrap, "npc", stat.npcId, stat.name);

		// no sparkline, no grade bar under every row. a 35-day sparkline on a monster
		// you killed today is one tick in an empty box, and a coloured bar under each
		// row is the wall the box layout just fixed. both facts live in the tooltip.
		if (expandable)
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
				// the facts that used to be a sparkline and a coloured bar. as text
				// they say what they mean, and they only cost space when opened.
				wrap.add(detailLine("xp per kill",
					shortXp(stat.total() > 0 ? stat.xp / stat.total() : 0L)));

				if (stat.total() > 0 && stat.uncontested < stat.total())
				{
					wrap.add(detailLine("clean kills",
						stat.uncontested + " of " + stat.total()));
				}

				if (stat.lastKillMillis > 0)
				{
					wrap.add(detailLine("last killed", ago(stat.lastKillMillis)));
				}

				// biggest first. nobody scans an alphabetical list looking for where
				// their xp went.
				if (hasSkills)
				{
					stat.xpBySkill.entrySet().stream()
						.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
						.forEach(e -> wrap.add(skillLine(e.getKey(), e.getValue())));
				}

				if (hasDrops)
				{
					wrap.add(dropHeader(stat));

					// by total value, not quantity. nobody opens a drop list to find out
					// how many bones they have - the 400 bones would sit on top of the
					// visitor's item forever.
					stat.drops.entrySet().stream()
						.sorted((a, b) -> Long.compare(valueOf(b.getKey(), b.getValue()),
							valueOf(a.getKey(), a.getValue())))
						.forEach(e -> wrap.add(dropLine(stat, e.getKey(), e.getValue())));
				}
			}
		}

		return wrap;
	}

	/** Separator above the drop list, so it doesn't read as more skill lines. */
	private JLabel dropHeader(NpcStat stat)
	{
		final int kinds = stat.drops.size();

		long total = 0L;
		for (Map.Entry<String, NpcStat.DropTally> e : stat.drops.entrySet())
		{
			total += valueOf(e.getKey(), e.getValue());
		}

		final JLabel l = new JLabel("drops  ·  " + kinds + (kinds == 1 ? " item" : " items")
			+ (total > 0 ? "  ·  " + gp(total) + " gp" : ""));
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		l.setOpaque(true);
		l.setBackground(NEST_BG);
		l.setBorder(BorderFactory.createEmptyBorder(4, 7, 2, 0));
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	/**
	 * One item: what it is, how many, and how long since the last one.
	 *
	 * <p>The dry number is deliberately plain — "312 since". Saying whether that is
	 * unlucky needs the item's published rate, and {@code spec-reference-data.md} keeps
	 * the reference table server-side, so the client states the fact and stops.
	 */
	private JPanel dropLine(NpcStat stat, String itemId, NpcStat.DropTally tally)
	{
		final JPanel p = new JPanel(new BorderLayout());
		p.setBackground(NEST_BG);
		p.setBorder(BorderFactory.createEmptyBorder(1, 7, 1, 7));
		p.setMaximumSize(new Dimension(Short.MAX_VALUE, 18));
		p.setAlignmentX(LEFT_ALIGNMENT);

		// an id is not a name, but it beats showing nothing when the composition
		// wasn't loaded at the time - and it stays diagnosable.
		final String label = tally.name != null ? tally.name : "item " + itemId;

		final JLabel left = new JLabel(label);
		left.setFont(FontManager.getRunescapeSmallFont());
		left.setForeground(Color.WHITE);

		// the icon, with the stack count drawn into it when there's more than one -
		// that's what the quantity argument buys. async, so it appears when ready
		// rather than holding up the repaint.
		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(20, 16));
		try
		{
			final int id = Integer.parseInt(itemId);
			final int shown = (int) Math.min(Integer.MAX_VALUE, tally.quantity);
			itemManager.getImage(id, shown, shown > 1).addTo(icon);
		}
		catch (RuntimeException e)
		{
			// no icon is survivable; a missing row is not.
		}

		final StringBuilder right = new StringBuilder();
		right.append(tally.quantity);
		if (tally.drops > 1 && tally.quantity != tally.drops)
		{
			// 400 bones over 400 drops says nothing extra. 3960 coins over 40 drops
			// does, so only show the split when the two differ.
			right.append("  (").append(tally.drops).append(')');
		}

		final JLabel count = new JLabel(right.toString());
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		// gp and dry streak in the tooltip. both are worth knowing and neither is worth
		// a permanent column in a 225px panel.
		final StringBuilder tip = new StringBuilder("<html>");
		final long value = valueOf(itemId, tally);
		if (value > 0)
		{
			tip.append(gp(value)).append(" gp total");
			if (tally.quantity > 1)
			{
				tip.append("  ·  ").append(gp(value / tally.quantity)).append(" ea");
			}
		}

		final int since = stat.killsSince(Integer.parseInt(itemId));
		if (since > 0)
		{
			if (tip.length() > 6)
			{
				tip.append("<br>");
			}
			tip.append(since).append(" kills since the last one");
		}

		if (tip.length() > 6)
		{
			p.setToolTipText(tip.append("</html>").toString());
		}

		try
		{
			wikiMenu(p, "item", Integer.parseInt(itemId), tally.name);
		}
		catch (NumberFormatException e)
		{
			// a key that isn't an id can't be looked up. no menu, row still draws.
		}

		p.add(icon, BorderLayout.WEST);
		p.add(left, BorderLayout.CENTER);
		p.add(count, BorderLayout.EAST);
		return p;
	}

	private static JLabel skillLine(String skill, long xp)
	{
		final JLabel l = new JLabel(pretty(skill) + "  " + shortXp(xp));
		l.setOpaque(true);
		l.setBackground(NEST_BG);
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

	private static String day(long millis)
	{
		return java.time.Instant.ofEpochMilli(millis)
			.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString();
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
		sb.append("<br>").append(span(Confidence.UNCONTESTED, stat.uncontested + " uncontested"));
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

		final int best = stat.bestDay();
		if (best > 1)
		{
			sb.append("<br>best day ").append(best);
		}
		if (stat.firstKillMillis > 0)
		{
			sb.append("<br>first ").append(day(stat.firstKillMillis));
			if (stat.lastKillMillis > stat.firstKillMillis)
			{
				sb.append(" &middot; last ").append(day(stat.lastKillMillis));
			}
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
		private int uncontested;
		private int inferred;
		private int ambiguous;

		private GradeBar()
		{
			setBackground(new Color(0x14, 0x14, 0x14));
		}

		private void set(int uncontested, int inferred, int ambiguous)
		{
			this.uncontested = uncontested;
			this.inferred = inferred;
			this.ambiguous = ambiguous;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);

			final int total = uncontested + inferred + ambiguous;
			if (total <= 0)
			{
				return;
			}

			final int w = getWidth();
			int x = 0;

			final int we = Math.round(w * (uncontested / (float) total));
			g.setColor(Confidence.UNCONTESTED.getColor());
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
