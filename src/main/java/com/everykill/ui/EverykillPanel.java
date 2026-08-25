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
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import net.runelite.client.util.LinkBrowser;
import okhttp3.HttpUrl;
import net.runelite.client.callback.ClientThread;
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
	// the site's palette, from everykill-site/styles.css. one product, one look - and
	// these are close enough to ColorScheme that the panel still reads as runelite.
	//   --panel     #16181d   row background
	//   --bg-alt    #101216   title strip, darker than the row
	//   --line      #23262d   rules
	//   --fg        #e8eaed   the thing itself
	//   --fg-dim    #9aa0a8   supporting numbers
	//   --fg-faint  #63696f   headings, absent values
	//   --acc       #d94f2b   "rs-adjacent rust, not jagex gold"
	private static final Color SITE_PANEL = new Color(0x16, 0x18, 0x1d);
	private static final Color SITE_BG_ALT = new Color(0x10, 0x12, 0x16);
	private static final Color SITE_LINE = new Color(0x23, 0x26, 0x2d);
	private static final Color SITE_FG = new Color(0xe8, 0xea, 0xed);
	private static final Color SITE_FG_DIM = new Color(0x9a, 0xa0, 0xa8);
	private static final Color SITE_FG_FAINT = new Color(0x63, 0x69, 0x6f);
	private static final Color SITE_ACC = new Color(0xd9, 0x4f, 0x2b);

	// core's own supporting-text grey. was a hand-picked 0x8e8e8e, which is the same
	// idea two shades off - matching ColorScheme is how the panel looks native.
	private static final Color SUBTLE = SITE_FG_DIM;

	private final LocalLedger ledger;
	private final MilestoneNotifier notifier;
	private final XpService xpService;

	// for drop icons. getImage is async - addTo(label) repaints when it lands, so
	// nothing blocks swing. same call LootTrackerBox makes.
	private final ItemManager itemManager;

	private final ClientThread clientThread;

	/**
	 * item id -> price, refreshed on the client thread.
	 *
	 * <p>{@code ItemManager.itemPrices} starts as an empty map and is filled by an
	 * async HTTP fetch, so the price read at drop time is often 0 - and storing that
	 * made it permanently 0. Reading it here means it fixes itself once prices land.
	 */
	private final Map<Integer, Integer> priceCache = new HashMap<>();

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
		ItemManager itemManager, ClientThread clientThread)
	{
		super(false);
		this.ledger = ledger;
		this.notifier = notifier;
		this.xpService = xpService;
		this.itemManager = itemManager;
		this.clientThread = clientThread;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 2));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		body.add(buildSessionBox());
		body.add(javax.swing.Box.createVerticalStrut(6));
		body.add(buildMonsterBox());

		// a scroll pane, not add(body, NORTH). NORTH hands the child its full preferred
		// height, so expanding a monster with a long drop list grew the panel until it
		// stretched the whole client window. CENTER plus a scroll pane means the panel
		// keeps its size and the content moves instead.
		// the view has to take the VIEWPORT's width, not its own natural one. a scroll
		// pane hands its view the width the content asks for, so one long drop name
		// widened everything and pushed the right-hand numbers off the edge - with
		// HORIZONTAL_SCROLLBAR_NEVER they were just clipped, not reachable.
		final JPanel top = new JPanel(new BorderLayout())
		{
			@Override
			public Dimension getPreferredSize()
			{
				final Dimension d = super.getPreferredSize();
				final java.awt.Container vp = getParent();
				return vp == null ? d : new Dimension(vp.getWidth(), d.height);
			}
		};
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(body, BorderLayout.NORTH);

		final JScrollPane scroll = new JScrollPane(top,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);

		// the default is 1px per notch, which makes a long list feel broken.
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		// PluginPanel hands us PANEL_WIDTH + SCROLLBAR_WIDTH and expects the extra 17px
		// to belong to the bar - its own comment says "prevent scrollbar overlapping
		// over contents". our scroll pane didn't reserve it, so the bar sat on top of
		// the counts. give the bar its width and pad the content off it.
		final javax.swing.JScrollBar bar = scroll.getVerticalScrollBar();
		bar.setPreferredSize(new Dimension(10, 0));
		bar.setBackground(SITE_BG_ALT);
		bar.setBorder(BorderFactory.createEmptyBorder());

		// flat thumb in the site's line colour. the default metal bar brings arrow
		// buttons at both ends, which is what squashed it into itself in a 10px lane.
		bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI()
		{
			@Override
			protected void configureScrollBarColors()
			{
				thumbColor = SITE_LINE;
				trackColor = SITE_BG_ALT;
			}

			@Override
			protected javax.swing.JButton createIncreaseButton(int orientation)
			{
				return zeroButton();
			}

			@Override
			protected javax.swing.JButton createDecreaseButton(int orientation)
			{
				return zeroButton();
			}

			private javax.swing.JButton zeroButton()
			{
				final javax.swing.JButton b = new javax.swing.JButton();
				b.setPreferredSize(new Dimension(0, 0));
				b.setMinimumSize(new Dimension(0, 0));
				b.setMaximumSize(new Dimension(0, 0));
				return b;
			}
		});

		top.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));

		add(scroll, BorderLayout.CENTER);
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

	/**
	 * Fills {@link #priceCache} for anything we haven't priced yet.
	 *
	 * <p>Hops to the client thread because {@code getItemPrice} asserts it, then comes
	 * back to Swing to repaint only if something actually changed - otherwise this
	 * would loop forever against its own rebuild.
	 */
	private void refreshPrices(List<NpcStat> rows)
	{
		final List<Integer> wanted = new ArrayList<>();
		for (NpcStat stat : rows)
		{
			if (stat.drops == null)
			{
				continue;
			}
			for (String key : stat.drops.keySet())
			{
				try
				{
					final int id = Integer.parseInt(key);
					if (!priceCache.containsKey(id))
					{
						wanted.add(id);
					}
				}
				catch (NumberFormatException e)
				{
					// not an id, nothing to price.
				}
			}
		}

		if (wanted.isEmpty())
		{
			return;
		}

		clientThread.invokeLater(() ->
		{
			boolean changed = false;
			for (int id : wanted)
			{
				try
				{
					final int price = itemManager.getItemPrice(id);
					if (price > 0)
					{
						priceCache.put(id, price);
						changed = true;
					}
				}
				catch (RuntimeException | AssertionError e)
				{
					// unknown or untradeable. leave it unpriced rather than caching a
					// zero we'd never revisit.
				}
			}

			if (changed)
			{
				SwingUtilities.invokeLater(this::rebuild);
			}
		});
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

		// top the price cache up on the client thread, then repaint. getItemPrice
		// asserts that thread, and its price map is empty until an async fetch lands -
		// so anything read at drop time may have been 0 and needs a second chance.
		refreshPrices(rows);
		monsterHeader.setText(window.tooltip.toUpperCase() + " · " + rows.size());

		// no cap. it used to stop at 12, which quietly hid the 13th row the header was
		// already counting - and expanding a row pushed others off the end, so the list
		// appeared to lose monsters when you clicked one. the panel scrolls; let it.
		for (NpcStat stat : rows)
		{
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
	private static final Color TITLE_BG = SITE_BG_ALT;

	/**
	 * Background for expanded detail. Slightly lifted off the row body so nesting is
	 * visible - an 8px indent alone left skill and drop lines floating with nothing
	 * tying them to their monster.
	 */
	private static final Color NEST_BG = SITE_PANEL;

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
			final Integer live = priceCache.get(Integer.parseInt(itemId));
			if (live != null && live > 0)
			{
				return (long) live * tally.quantity;
			}
		}
		catch (NumberFormatException e)
		{
			// not an id. fall through to the stored price.
		}
		return storedValue(tally);
	}

	private static long storedValue(NpcStat.DropTally tally)
	{
		// the stored price, NOT itemManager.getItemPrice. that call asserts it's on the
		// client thread and throws AssertionError from swing - which took out the whole
		// repaint mid-loop, so the list rendered three rows and stopped. the header
		// still said 13 because it counts before drawing.
		return (long) tally.price * tally.quantity;
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

	/**
	 * A small "w" button that opens the wiki.
	 *
	 * <p>Visible rather than a right-click menu - a hidden context menu is a feature
	 * nobody finds. Kept to one character because the panel is 225px and the button
	 * sits beside a name that can already be long.
	 */
	private static JLabel wikiButton(String type, int id, String name)
	{
		final JLabel b = new JLabel("w");
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setForeground(SITE_FG_FAINT);
		b.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 4));
		b.setToolTipText("Open the wiki page");
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		b.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// consume it, or the row's own listener toggles the expand as well.
				e.consume();
				wiki(type, id, name);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				b.setForeground(SITE_ACC);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				b.setForeground(SITE_FG_FAINT);
			}
		});

		return b;
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
		p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		p.setMaximumSize(new Dimension(Short.MAX_VALUE, 17));
		p.setAlignmentX(LEFT_ALIGNMENT);

		final JLabel l = new JLabel(label);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(SITE_FG_FAINT);

		final JLabel v = new JLabel(value);
		v.setFont(FontManager.getRunescapeSmallFont());
		v.setForeground(SITE_FG_DIM);

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

	/**
	 * One headline number with its caption under it, centred.
	 *
	 * <p>Three of these side by side read at a glance. The same three facts as
	 * left-label/right-number rows read as a spreadsheet, which is what the expanded
	 * row looked like before.
	 */
	private static JPanel statBlock(String value, String caption)
	{
		final JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(NEST_BG);

		final JLabel v = new JLabel(value, SwingConstants.CENTER);
		v.setFont(FontManager.getRunescapeBoldFont());
		v.setForeground(SITE_FG);
		v.setAlignmentX(CENTER_ALIGNMENT);

		final JLabel c = new JLabel(caption, SwingConstants.CENTER);
		c.setFont(FontManager.getRunescapeSmallFont());
		c.setForeground(SITE_FG_FAINT);
		c.setAlignmentX(CENTER_ALIGNMENT);

		p.add(v);
		p.add(c);
		return p;
	}

	/** A small caps heading inside an expanded row, with a rule above it. */
	private static JLabel sectionLine(String text)
	{
		final JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(SITE_FG_FAINT);
		l.setOpaque(true);
		l.setBackground(NEST_BG);
		l.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
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
		p.setBorder(BorderFactory.createEmptyBorder(7, 4, 7, 4));

		final JLabel name = new JLabel((expandable ? (open ? "▾ " : "▸ ") : "") + label(stat));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(SITE_FG);
		name.setMinimumSize(new Dimension(1, name.getPreferredSize().height));

		final JLabel count = new JLabel(String.valueOf(countFor(stat)));
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(SITE_FG_DIM);

		final JPanel right = new JPanel(new BorderLayout());
		right.setOpaque(false);
		right.add(count, BorderLayout.CENTER);
		right.add(wikiButton("npc", stat.npcId, stat.name), BorderLayout.EAST);

		p.add(name, BorderLayout.WEST);
		p.add(right, BorderLayout.EAST);

		final JPanel wrap = new JPanel();
		wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
		wrap.setBackground(SITE_PANEL);
		wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		wrap.setToolTipText(tooltip(stat));
		wrap.add(p);

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
				final JPanel detail = new JPanel();
				detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
				detail.setBackground(NEST_BG);
				detail.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createMatteBorder(1, 0, 0, 0, SITE_LINE),
					BorderFactory.createEmptyBorder(8, 4, 8, 4)));
				detail.setAlignmentX(LEFT_ALIGNMENT);

				// the headline numbers as centred blocks - big value, small caption
				// under it. a stack of left-label/right-number rows all at one weight
				// is what made this look like a spreadsheet.
				final JPanel stats = new JPanel(new java.awt.GridLayout(1, 0, 2, 0));
				stats.setBackground(NEST_BG);
				stats.setAlignmentX(LEFT_ALIGNMENT);
				stats.setMaximumSize(new Dimension(Short.MAX_VALUE, 34));

				final long perKill = stat.total() > 0 ? stat.xp / stat.total() : 0L;
				stats.add(statBlock(shortXp(perKill), "xp/kill"));
				stats.add(statBlock(stat.uncontested + "/" + stat.total(), "clean"));
				if (stat.lastKillMillis > 0)
				{
					stats.add(statBlock(ago(stat.lastKillMillis).replace(" ago", ""), "ago"));
				}

				detail.add(stats);

				if (hasSkills)
				{
					detail.add(javax.swing.Box.createVerticalStrut(10));
					detail.add(sectionLine("XP"));
					stat.xpBySkill.entrySet().stream()
						.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
						.forEach(e -> detail.add(skillLine(e.getKey(), e.getValue())));
				}

				if (hasDrops)
				{
					detail.add(javax.swing.Box.createVerticalStrut(10));
					detail.add(dropHeader(stat));

					// by total value, not quantity. nobody opens a drop list to find
					// out how many bones they have.
					stat.drops.entrySet().stream()
						.sorted((a, b) -> Long.compare(valueOf(b.getKey(), b.getValue()),
							valueOf(a.getKey(), a.getValue())))
						.forEach(e -> detail.add(dropLine(stat, e.getKey(), e.getValue())));
				}

				wrap.add(detail);
			}
		}

		return wrap;
	}

	/** DROPS heading with the pile's total value aligned over the gp column. */
	private JPanel dropHeader(NpcStat stat)
	{
		long total = 0L;
		for (Map.Entry<String, NpcStat.DropTally> e : stat.drops.entrySet())
		{
			total += valueOf(e.getKey(), e.getValue());
		}

		final JPanel p = new JPanel(new BorderLayout());
		p.setBackground(NEST_BG);
		p.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
		p.setMaximumSize(new Dimension(Short.MAX_VALUE, 14));
		p.setAlignmentX(LEFT_ALIGNMENT);

		final JLabel l = new JLabel("DROPS");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(SITE_FG_FAINT);

		// sits directly above the per-item gp, so the column adds up visually.
		final JLabel t = new JLabel(total > 0 ? gp(total) + " gp" : "");
		t.setFont(FontManager.getRunescapeSmallFont());
		t.setForeground(SITE_FG_FAINT);

		p.add(l, BorderLayout.WEST);
		p.add(t, BorderLayout.EAST);
		return p;
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
		p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		p.setMaximumSize(new Dimension(Short.MAX_VALUE, 20));
		p.setAlignmentX(LEFT_ALIGNMENT);

		// an id is not a name, but it beats showing nothing when the composition
		// wasn't loaded at the time - and it stays diagnosable.
		final String label = tally.name != null ? tally.name : "item " + itemId;

		final JLabel left = new JLabel(label);
		left.setFont(FontManager.getRunescapeSmallFont());
		left.setForeground(SITE_FG);

		// a long item name must not widen the row - core sets the same 1px minimum in
		// LootTrackerBox with the comment "make BoxLayout truncate the name".
		left.setMinimumSize(new Dimension(1, left.getPreferredSize().height));

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

		// how many on the left of the name, what it's worth on the right. the two
		// numbers answer different questions and putting both on the same side made
		// you read them as one.
		final JLabel count = new JLabel(String.valueOf(tally.quantity));
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(SITE_FG_DIM);
		count.setHorizontalAlignment(SwingConstants.RIGHT);
		count.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));
		count.setPreferredSize(new Dimension(24, count.getPreferredSize().height));

		final long value = valueOf(itemId, tally);
		final JLabel worth = new JLabel(value > 0 ? gp(value) : "");
		worth.setFont(FontManager.getRunescapeSmallFont());
		worth.setForeground(SITE_FG_DIM);

		// gp and dry streak in the tooltip. both are worth knowing and neither is worth
		// a permanent column in a 225px panel.
		final StringBuilder tip = new StringBuilder("<html>");
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

		// the item name IS the link. a 'w' on every drop row was four extra glyphs in
		// a column that's already icon + name + count, and the name is a bigger target.
		try
		{
			final int id = Integer.parseInt(itemId);
			left.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			left.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					e.consume();
					wiki("item", id, tally.name);
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					left.setForeground(SITE_ACC);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					left.setForeground(SITE_FG);
				}
			});
		}
		catch (NumberFormatException e)
		{
			// a key that isn't an id can't be looked up. no link, row still draws.
		}

		final JPanel head = new JPanel(new BorderLayout());
		head.setOpaque(false);
		head.add(count, BorderLayout.WEST);
		head.add(icon, BorderLayout.EAST);

		p.add(head, BorderLayout.WEST);
		p.add(left, BorderLayout.CENTER);
		p.add(worth, BorderLayout.EAST);
		return p;
	}

	private static JPanel skillLine(String skill, long xp)
	{
		// a label/value pair, not one string with spaces in it. the numbers line up on
		// the right edge with everything else in the expanded row instead of floating
		// wherever the skill name happens to end.
		return detailLine(pretty(skill), shortXp(xp));
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
