/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.ui;

import com.everykill.detect.SlayerTask;
import com.everykill.detect.WorldFilter;
import com.everykill.ledger.LocalLedger;
import com.google.gson.Gson;
import com.everykill.model.Confidence;
import com.everykill.model.NpcStat;
import com.everykill.upload.UploadService;
import com.everykill.notice.MilestoneNotifier;
import com.everykill.xp.XpService;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
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
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
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
	private static final Color SITE_BG = new Color(0x0b, 0x0c, 0x0e);
	private static final Color SITE_PANEL = new Color(0x16, 0x18, 0x1d);
	private static final Color SITE_BG_ALT = new Color(0x10, 0x12, 0x16);
	private static final Color SITE_LINE = new Color(0x23, 0x26, 0x2d);
	private static final Color SITE_FG = new Color(0xe8, 0xea, 0xed);
	private static final Color SITE_FG_DIM = new Color(0x9a, 0xa0, 0xa8);
	private static final Color SITE_FG_FAINT = new Color(0x63, 0x69, 0x6f);
	private static final Color SITE_ACC = new Color(0xd9, 0x4f, 0x2b);

	// --good from the site, used there for status dots and 'done' tags. gp is the one
	// number that means something good happened, so it gets it.
	private static final Color SITE_GOLD = new Color(0x4f, 0x9d, 0x5d);

	// core's own supporting-text grey. was a hand-picked 0x8e8e8e, which is the same
	// idea two shades off - matching ColorScheme is how the panel looks native.
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

	/**
	 * The slayer task, snapshotted on the client thread.
	 *
	 * <p>{@code rebuild()} runs on the EDT and varp / DB-table reads are client-thread
	 * work — the same trap that {@code getItemPrice} sprang on the drop rows. Refresh
	 * off-EDT, paint from the snapshot.
	 */
	private volatile String taskLine;

	private volatile String taskProgress;

	/** Non-null when this world's kills don't count toward the board. */
	private volatile String unrankedWorld;

	private enum View
	{
		KILLS("Kill log"),
		SESSION("Session"),
		RECORDS("Records"),
		ACCOUNT("Account");

		private final String label;

		View(String label)
		{
			this.label = label;
		}
	}

	private View view = View.KILLS;

	private final JLabel sessionKills = new JLabel("0");
	private final JLabel sessionSub = new JLabel("kills");
	private final JLabel sessionGrades = new JLabel(" ");
	private final JLabel unallocated = new JLabel(" ");
	private final GradeBar sessionBar = new GradeBar();
	private final JPanel monsterList = new JPanel();
	private final JLabel monsterHeader = new JLabel("ALL TIME");
	private final JLabel noticeLabel = new JLabel(" ");
	private final MaterialTabGroup tabs = new MaterialTabGroup();

	private final JPanel viewTabs = new JPanel();

	private final NpcIcons npcIcons;

	private final UploadService uploadService;
	private final SlayerTask slayerTask;
	private final WorldFilter worldFilter;

	// npc ids whose skill breakdown is open. panel state, never persisted.
	private final Set<Integer> expanded = new HashSet<>();

	private Window window = Window.ALL;

	/** How far back the list looks. SESSION is the live one; the rest read day buckets. */
	private enum Window
	{
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
		ItemManager itemManager, ClientThread clientThread, Gson gson,
		UploadService uploadService, SlayerTask slayerTask, WorldFilter worldFilter)
	{
		super(false);
		this.ledger = ledger;
		this.notifier = notifier;
		this.xpService = xpService;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.uploadService = uploadService;
		this.slayerTask = slayerTask;
		this.worldFilter = worldFilter;

		// injected Gson, per CONVENTIONS - never build one. read once at construction
		// because it's a 238-entry file off the classpath, not per repaint.
		this.npcIcons = NpcIcons.load(gson);

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
		setBackground(SITE_BG);

		final JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(SITE_BG);

		body.add(buildBrandBar());
		body.add(javax.swing.Box.createVerticalStrut(6));
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
		top.setBackground(SITE_BG);
		top.add(body, BorderLayout.NORTH);

		final JScrollPane scroll = new JScrollPane(top,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setBackground(SITE_BG);
		scroll.getViewport().setBackground(SITE_BG);

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
		bar.setUI(flatScrollBar());

		top.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));

		add(scroll, BorderLayout.CENTER);
	}

	/**
	 * The name, a wiki-style link out to the site, and the config button.
	 *
	 * <p>Mirrors the site's nav: a rust square with the initials, the product name,
	 * links on the right. Same brand mark, so the plugin and the site read as one
	 * thing rather than two projects that happen to share a name.
	 */
	/**
	 * Kills / Records. The time tabs below only apply to the kill log, so they are
	 * hidden on Records rather than left visible and inert - which is what stranded
	 * you there with no way back.
	 */
	private JPanel buildViewTabs()
	{
		final JPanel row = new JPanel(new java.awt.GridLayout(1, View.values().length, 1, 0));
		row.setBackground(SITE_BG);
		row.setMaximumSize(new Dimension(Short.MAX_VALUE, 20));
		row.setAlignmentX(LEFT_ALIGNMENT);

		for (View v : View.values())
		{
			row.add(viewTab(v.label, view == v, () ->
			{
				view = v;
				rebuild();
			}));
		}
		return row;
	}

	/** One view tab: rust underline when selected, same idiom as the time tabs. */
	private static JLabel viewTab(String text, boolean selected, Runnable action)
	{
		final JLabel l = new JLabel(text, SwingConstants.CENTER);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(selected ? SITE_FG : SITE_FG_FAINT);
		l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		l.setBorder(BorderFactory.createMatteBorder(0, 0, 2, 0,
			selected ? SITE_ACC : SITE_BG));
		l.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (!selected)
				{
					l.setForeground(SITE_FG_DIM);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (!selected)
				{
					l.setForeground(SITE_FG_FAINT);
				}
			}
		});
		return l;
	}

	/**
	 * Flat thumb, no arrow buttons.
	 *
	 * <p>The default metal bar puts a button at each end, which in a narrow lane
	 * leaves almost no thumb — that is what made the first scrollbar look squashed
	 * into itself. One factory so both bars can never drift apart.
	 */
	private static javax.swing.plaf.basic.BasicScrollBarUI flatScrollBar()
	{
		return new javax.swing.plaf.basic.BasicScrollBarUI()
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
		};
	}

	/**
	 * The Everykill mark. Four tally uprights, struck through.
	 *
	 * <p>Same geometry as {@code brand/everykill-mark.svg} in a 64-unit box, scaled
	 * to whatever size it is given — one set of numbers for the plugin, the hub icon
	 * and the site, so they cannot drift apart.
	 */
	private static final class TallyMark extends JComponent
	{
		private static final int[] UPRIGHTS = {18, 27, 36, 45};
		private static final int TOP = 20, BOT = 44;
		private static final int SX1 = 13, SY1 = 41, SX2 = 50, SY2 = 23;

		TallyMark()
		{
			setPreferredSize(new Dimension(20, 20));
			setMaximumSize(new Dimension(20, 20));
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics graphics)
		{
			final Graphics2D g = (Graphics2D) graphics.create();
			try
			{
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
					RenderingHints.VALUE_STROKE_PURE);

				final double s = Math.min(getWidth(), getHeight()) / 64.0;
				g.scale(s, s);

				g.setColor(SITE_ACC);
				g.setStroke(new BasicStroke(4.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				for (int x : UPRIGHTS)
				{
					g.drawLine(x, TOP, x, BOT);
				}
				g.drawLine(SX1, SY1, SX2, SY2);
			}
			finally
			{
				g.dispose();
			}
		}
	}

	private JPanel buildBrandBar()
	{
		final JPanel bar = new JPanel(new BorderLayout());
		bar.setBackground(SITE_BG);
		bar.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		bar.setMaximumSize(new Dimension(Short.MAX_VALUE, 24));
		bar.setAlignmentX(LEFT_ALIGNMENT);

		// the tally: four uprights struck through, rust on --panel. drawn rather
		// than loaded so it scales with the panel and needs no resource round-trip.
		final JComponent mark = new TallyMark();

		final JLabel name = new JLabel(" Everykill");
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(SITE_FG);

		final JPanel left = new JPanel(new BorderLayout());
		left.setOpaque(false);
		left.add(mark, BorderLayout.WEST);
		left.add(name, BorderLayout.CENTER);

		// the whole lockup is the link, mark included - that's how the site's nav
		// works and it's a much bigger target than 'site' on its own.
		left.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		left.setToolTipText("Open everykill.com");
		left.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse("https://www.everykill.com");
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				name.setForeground(SITE_ACC);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				name.setForeground(SITE_FG);
			}
		});

		bar.add(left, BorderLayout.WEST);
		return bar;
	}

	/** A clickable bit of text that lights up on hover. */
	private static JLabel linkLabel(String text, String tooltip, Runnable action)
	{
		final JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(SITE_FG_FAINT);
		l.setToolTipText(tooltip);
		l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		l.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				l.setForeground(SITE_ACC);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				l.setForeground(SITE_FG_FAINT);
			}
		});
		return l;
	}

	private JPanel buildSessionBox()
	{
		final JPanel box = box();

		final JLabel head = caption("THIS SESSION");

		// RuneLite's three RuneScape faces are all fixed-size — "small" is a different
		// TTF, not a smaller point size — so bold is the only emphasis available.
		sessionKills.setFont(FontManager.getRunescapeBoldFont());
		sessionKills.setForeground(SITE_ACC);

		sessionSub.setFont(FontManager.getRunescapeSmallFont());
		sessionSub.setForeground(SITE_FG_DIM);

		sessionGrades.setFont(FontManager.getRunescapeSmallFont());
		sessionGrades.setForeground(SITE_FG_DIM);

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
		monsterHeader.setForeground(SITE_FG_DIM);
		monsterHeader.setAlignmentX(LEFT_ALIGNMENT);

		monsterList.setLayout(new BoxLayout(monsterList, BoxLayout.Y_AXIS));
		monsterList.setBackground(SITE_PANEL);

		noticeLabel.setFont(FontManager.getRunescapeSmallFont());
		noticeLabel.setForeground(SITE_FG_DIM);

		// MaterialTabGroup rather than a JComboBox. A stock combo box renders with a
		// white popup and black text in the middle of a dark panel and looks exactly as
		// bad as that sounds. This is what core's own panels use.
		// MaterialTab hardcodes a 10px empty border each side. That cost a fifth tab
		// its label back when "Now" lived here; with four, a 54px cell leaves 34px of
		// text room and the longest label is 21px - measured, not guessed - so core's
		// own border is left alone.
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

				return true;
			});
			tabs.addTab(tab);

			if (w == window)
			{
				tabs.select(tab);
			}

			// unselected tabs get it straight away; the selected one is queued above.
		}

		viewTabs.setLayout(new BorderLayout());
		viewTabs.setBackground(SITE_BG);
		viewTabs.setMaximumSize(new Dimension(Short.MAX_VALUE, 20));
		viewTabs.setAlignmentX(LEFT_ALIGNMENT);
		box.add(viewTabs);
		box.add(javax.swing.Box.createVerticalStrut(6));
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

		// --panel, not runelite grey. this backs the session header and the monster
		// list, so it was the largest flat area on screen and the reason the panel
		// read as black rather than dark.
		p.setBackground(SITE_PANEL);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 0, 0, SITE_LINE),
			BorderFactory.createEmptyBorder(7, 4, 7, 4)));
		return p;
	}

	private static JLabel caption(String text)
	{
		final JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(SITE_FG_DIM);
		return l;
	}

	/** Safe to call from the client thread; hops to Swing itself. */
	public void refresh()
	{
		SwingUtilities.invokeLater(this::rebuild);
	}

	/**
	 * Reads the slayer task on the client thread, repainting only if it moved.
	 */
	private void refreshTask()
	{
		clientThread.invokeLater(() ->
		{
			String line = null;
			String progress = null;
			final String unranked = worldFilter.excludedReason();

			if (slayerTask.active())
			{
				final String name = slayerTask.name();
				if (name != null)
				{
					final String where = slayerTask.location();
					line = where == null ? title(name) : title(name) + " · " + where;

					final int left = slayerTask.remaining();
					final int assigned = slayerTask.assigned();
					progress = assigned > 0 ? left + " of " + assigned : String.valueOf(left);
				}
			}

			// guard the repaint or this loops forever: rebuild -> refresh -> rebuild.
			if (!java.util.Objects.equals(line, taskLine)
				|| !java.util.Objects.equals(progress, taskProgress)
				|| !java.util.Objects.equals(unranked, unrankedWorld))
			{
				taskLine = line;
				taskProgress = progress;
				unrankedWorld = unranked;
				SwingUtilities.invokeLater(this::rebuild);
			}
		});
	}

	/**
	 * Personal records, from data we already store.
	 *
	 * <p>{@code spec-plugin-ux.md} also lists fastest kill and best xp/hour. Neither is
	 * built because no per-kill duration is recorded anywhere - inventing one from
	 * kill timestamps would measure how fast you walked between monsters. The tab says
	 * so rather than quietly omitting them.
	 */
	/**
	 * Live view of this session.
	 *
	 * <p>{@code spec-plugin-ux.md} §1b also lists supplies consumed, damage taken and
	 * deaths. None are built: nothing tracks inventory changes or our own hitpoints,
	 * and a panel showing 0 for all three would read as "you took no damage" rather
	 * than "we are not watching". They arrive when something measures them.
	 *
	 * <p>The session boundary is the spec's other open item. Ours is "since the
	 * counters were last zeroed" — login or a manual reset — not the fixed 10-minute
	 * idle rule, so the elapsed figure is labelled plainly rather than dressed up as
	 * a standard everyone shares.
	 */
	/**
	 * The recovery code, shown until the user says they have it.
	 *
	 * <p>Deliberately loud and deliberately blocking-ish. The server mints this exactly
	 * once and there is no RSN on file, so it is the only route back into a history
	 * after a reinstall. A quiet one-time toast would lose people their data, and the
	 * honest cost of doing identity properly should be stated rather than buried.
	 */
	/**
	 * A wrapping paragraph that reports the height it actually needs.
	 *
	 * <p>An HTML {@link JLabel} derives its preferred height from its preferred
	 * width. Inside a vertical {@code BoxLayout} nothing tells it how wide it will
	 * end up, so it assumes one long line, reports a one-line height, and everything
	 * past that gets clipped — which is exactly what happened to the recovery text.
	 *
	 * <p>Setting the view width first and re-measuring is the documented way round
	 * it: {@code View.setSize} then re-read the preferred size.
	 */
	private static JLabel paragraph(String html, Color colour)
	{
		final JLabel l = new JLabel("<html>" + html + "</html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(colour);
		l.setAlignmentX(LEFT_ALIGNMENT);

		// PluginPanel.PANEL_WIDTH minus the scrollbar lane and the card's padding.
		final int width = PluginPanel.PANEL_WIDTH - 34;

		final javax.swing.text.View view = javax.swing.plaf.basic.BasicHTML.createHTMLView(
			l, l.getText());
		view.setSize(width, 0);
		final int height = (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS));

		l.setPreferredSize(new Dimension(width, height));
		l.setMaximumSize(new Dimension(Short.MAX_VALUE, height));
		return l;
	}

	private JPanel recoveryBanner(String code)
	{
		// wording note: this used to say the code alone was the way back. it is the
		// code AND the account still existing server-side. delk kept a code from a
		// dev run and it was worthless, because the salt had rotated and the server
		// had never heard of that client id. on a real deployment the salt is fixed
		// and the promise holds - but the banner should not claim more than it can.
		final JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(SITE_PANEL);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, SITE_ACC),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)));
		p.setAlignmentX(LEFT_ALIGNMENT);

		final JLabel head = new JLabel("RECOVERY CODE");
		head.setFont(FontManager.getRunescapeSmallFont());
		head.setForeground(SITE_ACC);
		head.setAlignmentX(LEFT_ALIGNMENT);

		final JLabel value = new JLabel(code);
		value.setFont(FontManager.getRunescapeBoldFont());
		value.setForeground(SITE_FG);
		value.setAlignmentX(LEFT_ALIGNMENT);

		final JLabel why = paragraph("Your account is identified by a random id,"
			+ " not your RuneScape name. That id is saved with your RuneLite settings,"
			+ " so a reinstall usually restores it on its own. Keep this code somewhere"
			+ " in case it doesn’t.", SITE_FG_DIM);

		final JLabel copy = new JLabel("Copy");
		copy.setFont(FontManager.getRunescapeSmallFont());
		copy.setForeground(SITE_FG_FAINT);
		copy.setAlignmentX(LEFT_ALIGNMENT);
		copy.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		copy.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				Toolkit.getDefaultToolkit().getSystemClipboard()
					.setContents(new StringSelection(code), null);
				copy.setText("Copied");
				copy.setForeground(SITE_GOLD);
			}
		});

		final JLabel done = new JLabel("I've saved it");
		done.setFont(FontManager.getRunescapeSmallFont());
		done.setForeground(SITE_FG_FAINT);
		done.setAlignmentX(LEFT_ALIGNMENT);
		done.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		done.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// only now does it leave disk. clearing on display would mean a
				// misclick costs someone their history.
				uploadService.acknowledgeRecoveryCode();
				rebuild();
			}
		});

		final JPanel actions = new JPanel(new java.awt.GridLayout(1, 2, 6, 0));
		actions.setOpaque(false);
		actions.setAlignmentX(LEFT_ALIGNMENT);
		actions.setMaximumSize(new Dimension(Short.MAX_VALUE, 16));
		actions.add(copy);
		actions.add(done);

		p.add(head);
		p.add(value);
		p.add(javax.swing.Box.createVerticalStrut(4));
		p.add(why);
		p.add(javax.swing.Box.createVerticalStrut(6));
		p.add(actions);
		p.setMaximumSize(new Dimension(Short.MAX_VALUE, p.getPreferredSize().height));
		return p;
	}

	/** Export and delete, as the privacy policy promises. */
	/**
	 * Upload state, the recovery code, and the data rights buttons.
	 *
	 * <p>Everything here is about the ACCOUNT rather than the current sitting, which
	 * is why it left the Session tab — a recovery banner sitting on top of your
	 * kills/xp/elapsed pushed the actual session data down the panel and stayed there
	 * until dismissed.
	 */
	/**
	 * A titled card, matching the Records tab's own container.
	 *
	 * <p>Account used to stack bare lines directly on the background while every
	 * other tab used bordered cards, which made one tab look like a different
	 * screen. Same shell, so the tabs read as one panel.
	 */
	private static JPanel titledCard(String title)
	{
		final JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(SITE_PANEL);
		p.setAlignmentX(LEFT_ALIGNMENT);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(SITE_LINE, 1),
			BorderFactory.createEmptyBorder(7, 8, 8, 8)));

		final JLabel h = new JLabel(title);
		h.setFont(FontManager.getRunescapeSmallFont());
		h.setForeground(SITE_FG_FAINT);
		h.setAlignmentX(LEFT_ALIGNMENT);
		h.setMaximumSize(new Dimension(Short.MAX_VALUE, 14));
		p.add(h);
		p.add(javax.swing.Box.createVerticalStrut(3));
		return p;
	}

	/**
	 * Fixes a card at the height its content needs.
	 *
	 * <p>Called AFTER the children are added — measuring an empty panel returns the
	 * border and nothing else, which is a different wrong answer. Without this a
	 * card has no maximum, so BoxLayout treats it as stretchable and hands it a
	 * share of the leftover space on a short tab.
	 */
	private static void pin(JPanel card)
	{
		card.setMaximumSize(new Dimension(Short.MAX_VALUE, card.getPreferredSize().height));
	}

	/** The DB stores task names upper case; the panel doesn't shout. */
	private static String title(String upper)
	{
		if (upper == null || upper.isEmpty())
		{
			return upper;
		}
		return upper.charAt(0) + upper.substring(1).toLowerCase();
	}

	private void buildAccount()
	{
		monsterHeader.setText("ACCOUNT");

		final String recovery = uploadService.getRecoveryCode();
		if (recovery != null)
		{
			monsterList.add(recoveryBanner(recovery));
			monsterList.add(javax.swing.Box.createVerticalStrut(6));
		}

		final JPanel upload = titledCard("UPLOAD");
		upload.add(detailLine("status", uploadService.getStatus()));

		// a world whose kills don't count has to say so. "Up to date" while nothing
		// is being sent is indistinguishable from working.
		final String unranked = unrankedWorld;
		if (unranked != null)
		{
			upload.add(detailLine("world", unranked));
			upload.add(paragraph("Kills here are recorded locally but not uploaded - "
				+ "this world has its own save.", SITE_FG_DIM));
		}

		final int queued = uploadService.queued();
		if (queued > 0)
		{
			upload.add(detailLine("waiting", String.valueOf(queued)));
		}

		final int dropped = uploadService.dropped();
		if (dropped > 0)
		{
			upload.add(detailLine("dropped", String.valueOf(dropped)));
		}

		final String halted = uploadService.getHalted();
		if (halted != null)
		{
			final JLabel warn = paragraph(halted
				+ "<br>Your kills are safe and still queued.", SITE_ACC);
			warn.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
			upload.add(warn);
		}
		pin(upload);
		monsterList.add(upload);
		monsterList.add(javax.swing.Box.createVerticalStrut(6));

		// the slot the recovery code goes into. here rather than in config because
		// this is where the code was shown when it was minted, so it is where someone
		// will look for the hole it fits.
		final JPanel restore = titledCard("RESTORE AN ACCOUNT");
		restore.add(paragraph("Reinstalled or moved machine? Paste the recovery code "
			+ "you saved to bring your history back to this install.", SITE_FG_DIM));
		restore.add(javax.swing.Box.createVerticalStrut(6));

		final javax.swing.JTextField codeField = new javax.swing.JTextField();
		codeField.setFont(FontManager.getRunescapeSmallFont());
		codeField.setBackground(SITE_BG_ALT);
		codeField.setForeground(SITE_FG);
		codeField.setCaretColor(SITE_ACC);
		codeField.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(SITE_LINE, 1),
			BorderFactory.createEmptyBorder(4, 5, 4, 5)));
		codeField.setAlignmentX(LEFT_ALIGNMENT);
		codeField.setMaximumSize(new Dimension(Short.MAX_VALUE, 24));
		restore.add(codeField);
		restore.add(javax.swing.Box.createVerticalStrut(6));

		restore.add(actionLabel("Restore my history", () ->
		{
			final String code = codeField.getText();
			if (code == null || code.trim().isEmpty())
			{
				return;
			}
			uploadService.recover(code);
			codeField.setText("");
			// the service writes progress into the status line above. rebuild so the
			// player sees it move instead of wondering whether the click landed.
			SwingUtilities.invokeLater(this::rebuild);
		}));

		pin(restore);
		monsterList.add(restore);
		monsterList.add(javax.swing.Box.createVerticalStrut(8));

		final JPanel board = titledCard("LEADERBOARD");
		board.add(detailLine("name",
			uploadService.isPublishing() ? "published" : "not shown"));

		// an ironman deciding whether to hide their mode needs to see what is
		// currently exposed, not go and read the config to work it out.
		if (uploadService.isPublishing())
		{
			board.add(detailLine("account type",
				uploadService.isPublishingAccountType() ? "shown" : "hidden"));
		}

		// the name itself is never held anywhere - not on the record, not in the
		// identity file, not cached here for display. showing the STATE says what the
		// user needs without this panel becoming the field that gets populated.
		final JLabel note = paragraph(uploadService.isPublishing()
			? "Your display name is on public leaderboards."
			: "You are ranked anonymously. Turn on “Publish my name”"
				+ " in settings to appear by name.", SITE_FG_DIM);
		note.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
		board.add(note);
		pin(board);
		monsterList.add(board);
		monsterList.add(javax.swing.Box.createVerticalStrut(6));

		final JPanel data = titledCard("YOUR DATA");
		data.add(dataRights());
		pin(data);
		monsterList.add(data);

		// takes the leftover vertical space so the cards above keep their own
		// height instead of stretching to fill a short tab.
		monsterList.add(javax.swing.Box.createVerticalGlue());
	}

	private JPanel dataRights()
	{
		final JPanel row = new JPanel(new java.awt.GridLayout(1, 2, 6, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Short.MAX_VALUE, 16));

		row.add(actionLabel("Export my data", () ->
			uploadService.exportData(
				path -> SwingUtilities.invokeLater(() ->
					JOptionPane.showMessageDialog(this, "Saved to: " + path,
						"Everykill", JOptionPane.INFORMATION_MESSAGE)),
				err -> SwingUtilities.invokeLater(() ->
					JOptionPane.showMessageDialog(this, err,
						"Everykill", JOptionPane.WARNING_MESSAGE)))));

		row.add(actionLabel("Delete my data", () ->
		{
			// irreversible and keeps no tombstone, so it has to be asked plainly
			// rather than done on one click.
			final int answer = JOptionPane.showConfirmDialog(this,
				"Permanently delete every kill you have uploaded? "
					+ "This cannot be undone and your ranks will disappear.",
				"Everykill", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

			if (answer != JOptionPane.YES_OPTION)
			{
				return;
			}

			uploadService.eraseData(
				msg -> SwingUtilities.invokeLater(() ->
				{
					JOptionPane.showMessageDialog(this, msg,
						"Everykill", JOptionPane.INFORMATION_MESSAGE);
					rebuild();
				}),
				err -> SwingUtilities.invokeLater(() ->
					JOptionPane.showMessageDialog(this, err,
						"Everykill", JOptionPane.WARNING_MESSAGE)));
		}));

		return row;
	}

	private static JLabel actionLabel(String text, Runnable action)
	{
		final JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(SITE_FG_FAINT);
		l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		l.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				l.setForeground(SITE_ACC);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				l.setForeground(SITE_FG_FAINT);
			}
		});
		return l;
	}

	private void buildSession()
	{
		monsterHeader.setText("THIS SESSION");


		final int kills = ledger.getSessionKills();
		final long xp = ledger.sessionXp();
		final long elapsed = System.currentTimeMillis() - ledger.getSessionStartMillis();
		final double hours = elapsed / 3_600_000.0;

		monsterList.add(statRow(
			statBlock(String.valueOf(kills), "kills"),
			statBlock(shortXp(xp), "xp"),
			statBlock(elapsedShort(elapsed), "elapsed")));
		monsterList.add(javax.swing.Box.createVerticalStrut(4));

		// rates need enough time to mean anything. a 90-second sample extrapolated to
		// an hour is a number that moves every tick and tells you nothing - so it
		// stays hidden rather than showing something that looks measured.
		if (hours >= RATE_MIN_HOURS && kills > 0)
		{
			monsterList.add(statRow(
				statBlock(String.valueOf(Math.round(kills / hours)), "kills/hr"),
				statBlock(shortXp(Math.round(xp / hours)), "xp/hr")));
			monsterList.add(javax.swing.Box.createVerticalStrut(4));
		}
		else if (kills > 0)
		{
			monsterList.add(caption("rates after 5 minutes"));
			monsterList.add(javax.swing.Box.createVerticalStrut(4));
		}

		final List<NpcStat> rows = rollUp(new ArrayList<>(ledger.getSession().values()));
		if (rows.isEmpty())
		{
			monsterList.add(caption("Nothing killed yet."));
			return;
		}

		final String task = taskLine;
		if (task != null)
		{
			monsterList.add(sectionLine("SLAYER TASK"));
			monsterList.add(detailLine("task", task));
			if (taskProgress != null)
			{
				monsterList.add(detailLine("remaining", taskProgress));
			}
			monsterList.add(javax.swing.Box.createVerticalStrut(8));
		}

		// spec-plugin-ux 1b wants upload state always visible, so a one-line summary
		// stays here; the detail and the account controls live on the Account tab.
		monsterList.add(sectionLine("UPLOAD"));
		monsterList.add(detailLine("status", uploadService.getStatus()));

		final int queued = uploadService.queued();
		if (queued > 0)
		{
			monsterList.add(detailLine("waiting", String.valueOf(queued)));
		}

		monsterList.add(javax.swing.Box.createVerticalStrut(6));
		monsterList.add(sectionLine("BY MONSTER"));
		for (NpcStat stat : rows)
		{
			monsterList.add(row(stat));
		}
	}

	/** "2h 14m", "14m", "40s". */
	private static String elapsedShort(long millis)
	{
		final long mins = millis / 60_000L;
		if (mins < 1)
		{
			return (millis / 1000L) + "s";
		}
		if (mins < 60)
		{
			return mins + "m";
		}
		return (mins / 60) + "h " + (mins % 60) + "m";
	}

	private void buildRecords(java.util.Collection<NpcStat> all)
	{
		monsterHeader.setText("RECORDS");

		int totalKills = 0;
		long totalXp = 0L;
		long totalGp = 0L;
		NpcStat mostKilled = null;
		long firstEver = Long.MAX_VALUE;

		String bestItem = null;
		int bestItemId = -1;
		long bestValue = 0L;
		String bestFrom = null;

		String driestItem = null;
		int driestItemId = -1;
		int driestKills = 0;
		String driestFrom = null;

		for (NpcStat stat : all)
		{
			totalKills += stat.total();
			totalXp += stat.xp;

			if (mostKilled == null || stat.total() > mostKilled.total())
			{
				mostKilled = stat;
			}
			if (stat.firstKillMillis > 0 && stat.firstKillMillis < firstEver)
			{
				firstEver = stat.firstKillMillis;
			}
			if (stat.drops == null)
			{
				continue;
			}

			for (Map.Entry<String, NpcStat.DropTally> e : stat.drops.entrySet())
			{
				final NpcStat.DropTally tally = e.getValue();
				totalGp += valueOf(e.getKey(), tally);

				// per-drop value, not the pile - 400 bones isn't a lucky drop.
				final long each = tally.drops > 0
					? valueOf(e.getKey(), tally) / tally.drops : 0L;
				if (each > bestValue)
				{
					bestValue = each;
					bestItem = tally.name == null ? "item " + e.getKey() : tally.name;
					bestItemId = itemIdOf(e.getKey());
					bestFrom = stat.name;
				}

				try
				{
					final int since = stat.killsSince(Integer.parseInt(e.getKey()));
					if (since > driestKills)
					{
						driestKills = since;
						driestItem = tally.name == null ? "item " + e.getKey() : tally.name;
						driestItemId = itemIdOf(e.getKey());
						driestFrom = stat.name;
					}
				}
				catch (NumberFormatException ex)
				{
					// not an id, no streak to read.
				}
			}
		}

		monsterList.add(statRow(
			statBlock(String.valueOf(totalKills), "kills"),
			statBlock(shortXp(totalXp), "xp"),
			statBlock(totalGp > 0 ? gp(totalGp) : "-", "gp")));

		monsterList.add(javax.swing.Box.createVerticalStrut(6));

		if (bestItem != null)
		{
			monsterList.add(recordCard("MOST VALUABLE DROP", bestItem,
				gp(bestValue) + " gp  ·  " + bestFrom, bestItemId));
			monsterList.add(javax.swing.Box.createVerticalStrut(4));
		}

		if (driestItem != null && driestKills > 0)
		{
			monsterList.add(recordCard("LONGEST DRY STREAK", driestItem,
				driestKills + " kills since  ·  " + driestFrom, driestItemId));
			monsterList.add(javax.swing.Box.createVerticalStrut(4));
		}

		// fastest fight we've measured, across every monster. ticks * 0.6 = seconds.
		NpcStat fastest = null;
		for (NpcStat stat : all)
		{
			if (stat.fastestTicks > 0
				&& (fastest == null || stat.fastestTicks < fastest.fastestTicks))
			{
				fastest = stat;
			}
		}

		if (fastest != null)
		{
			monsterList.add(recordCard("FASTEST KILL",
				String.format("%.1fs", fastest.fastestTicks * 0.6),
				fastest.name + "  ·  " + fastest.fastestTicks + " ticks",
				npcIcons.forName(fastest.name)));
			monsterList.add(javax.swing.Box.createVerticalStrut(4));
		}

		final int bestSession = ledger.getBestSessionKills();
		if (bestSession > 0)
		{
			monsterList.add(recordCard("BEST SESSION", bestSession + " kills",
				"most in one sitting"));
			monsterList.add(javax.swing.Box.createVerticalStrut(4));
		}

		// best day, summed across every monster. the day buckets already exist for the
		// Day/Wk/Mth tabs - nothing new is stored for this.
		final Map<String, Integer> byDay = new HashMap<>();
		for (NpcStat stat : all)
		{
			if (stat.days == null)
			{
				continue;
			}
			for (Map.Entry<String, NpcStat.DayTally> e : stat.days.entrySet())
			{
				final NpcStat.DayTally d = e.getValue();
				byDay.merge(e.getKey(), d.uncontested + d.inferred + d.ambiguous, Integer::sum);
			}
		}

		String bestDay = null;
		int bestDayKills = 0;
		for (Map.Entry<String, Integer> e : byDay.entrySet())
		{
			if (e.getValue() > bestDayKills)
			{
				bestDayKills = e.getValue();
				bestDay = e.getKey();
			}
		}

		if (bestDay != null && bestDayKills > 0)
		{
			monsterList.add(recordCard("BEST DAY", bestDayKills + " kills", bestDay));
			monsterList.add(javax.swing.Box.createVerticalStrut(4));
		}

		if (mostKilled != null && mostKilled.total() > 0)
		{
			monsterList.add(recordCard("MOST KILLED", mostKilled.name,
				mostKilled.total() + " kills", npcIcons.forName(mostKilled.name)));
			monsterList.add(javax.swing.Box.createVerticalStrut(4));
		}

		// how far up MilestoneNotifier's own ladder the biggest monster has climbed,
		// and what's next. same numbers it announces in chat, so they agree.
		if (mostKilled != null)
		{
			final int[] ladder = {100, 250, 500, 1000, 2500, 5000, 10000};
			int passed = 0;
			int next = 0;
			for (int rung : ladder)
			{
				if (mostKilled.total() >= rung)
				{
					passed = rung;
				}
				else
				{
					next = rung;
					break;
				}
			}

			if (passed > 0 || next > 0)
			{
				monsterList.add(recordCard("MILESTONE",
					passed > 0 ? passed + " " + mostKilled.name : "none yet",
					next > 0 ? (next - mostKilled.total()) + " to " + next : "ladder complete"));
				monsterList.add(javax.swing.Box.createVerticalStrut(4));
			}
		}

		if (firstEver != Long.MAX_VALUE)
		{
			monsterList.add(recordCard("TRACKING SINCE",
				new java.text.SimpleDateFormat("d MMM yyyy").format(new java.util.Date(firstEver)),
				ago(firstEver)));
		}

		if (totalKills == 0)
		{
			monsterList.add(caption("Nothing counted yet."));
		}
	}

	/** One record: a small caps heading, the answer, and what backs it. */
	/** The drops map is keyed on item id as a string. -1 when it isn't a number. */
	private static int itemIdOf(String key)
	{
		try
		{
			return Integer.parseInt(key);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private JPanel recordCard(String heading, String value, String detail)
	{
		return recordCard(heading, value, detail, -1);
	}

	/**
	 * A record card with the thing it is about drawn beside it.
	 *
	 * <p>{@code itemId} is a real item for drop records and a stand-in from
	 * {@link NpcIcons} for monster records — the panel cannot tell the difference and
	 * does not need to. -1 draws no icon, which is the normal case for a monster that
	 * is not in the table.
	 */
	private JPanel recordCard(String heading, String value, String detail, int itemId)
	{
		final JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(SITE_PANEL);

		// a card: 1px line all round like the site's .card, then padding inside it.
		// on the page background these read as objects rather than a run of text.
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(SITE_LINE, 1),
			BorderFactory.createEmptyBorder(7, 8, 8, 8)));
		p.setAlignmentX(LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(Short.MAX_VALUE, 58));

		final JLabel h = new JLabel(heading);
		h.setFont(FontManager.getRunescapeSmallFont());
		h.setForeground(SITE_FG_FAINT);
		h.setAlignmentX(LEFT_ALIGNMENT);

		final JLabel v = new JLabel(value);
		v.setFont(FontManager.getRunescapeBoldFont());
		v.setForeground(SITE_FG);
		v.setAlignmentX(LEFT_ALIGNMENT);

		final JLabel d = new JLabel(detail);
		d.setFont(FontManager.getRunescapeSmallFont());
		d.setForeground(SITE_FG_DIM);
		d.setAlignmentX(LEFT_ALIGNMENT);

		p.add(h);
		p.add(v);
		p.add(d);

		if (itemId <= 0)
		{
			return p;
		}

		// icon to the left of the whole card, so the text column still lines up with
		// the cards that have no icon.
		final JLabel face = new JLabel();
		face.setPreferredSize(new Dimension(38, 32));
		face.setHorizontalAlignment(SwingConstants.CENTER);
		itemManager.getImage(itemId, 1, false).addTo(face);

		final JPanel withIcon = new JPanel(new BorderLayout());
		withIcon.setBackground(SITE_PANEL);
		withIcon.setBorder(BorderFactory.createLineBorder(SITE_LINE, 1));
		withIcon.setAlignmentX(LEFT_ALIGNMENT);
		withIcon.setMaximumSize(new Dimension(Short.MAX_VALUE, 58));

		p.setBorder(BorderFactory.createEmptyBorder(7, 4, 8, 8));
		withIcon.add(face, BorderLayout.WEST);
		withIcon.add(p, BorderLayout.CENTER);
		return withIcon;
	}

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
		refreshTask();
		monsterHeader.setText(window.tooltip.toUpperCase() + " · " + rows.size());

		// the view tabs are rebuilt from showRecords each time, so they always reflect
		// the real state rather than whatever the last click set.
		viewTabs.removeAll();
		viewTabs.add(buildViewTabs());
		// the time tabs only mean something for the kill log. a lifetime record and a
		// live session both ignore them, so they hide rather than sit there inert.
		tabs.setVisible(view == View.KILLS);

		if (view == View.RECORDS)
		{
			buildRecords(ledger.allTimeSorted());
			return;
		}

		if (view == View.SESSION)
		{
			buildSession();
			return;
		}

		if (view == View.ACCOUNT)
		{
			buildAccount();
			return;
		}

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
		return window == Window.ALL
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
	private static final Color NEST_BG = SITE_BG_ALT;

	/** drop rows visible before the list starts scrolling. */
	/** hours of session before per-hour rates are worth showing. */
	private static final double RATE_MIN_HOURS = 5.0 / 60.0;

	private static final int VISIBLE_DROPS = 5;

	private static final int DROP_ROW_HEIGHT = 36;

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
	/**
	 * A card of centred stat blocks. Two or three, the layout splits evenly.
	 *
	 * <p>Existed three times identically before this — session totals, session rates
	 * and the records header. Three copies of a card is three places a border tweak
	 * gets forgotten.
	 */
	private static JPanel statRow(JPanel... blocks)
	{
		final JPanel row = new JPanel(new java.awt.GridLayout(1, 0, 2, 0));
		row.setBackground(SITE_PANEL);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(SITE_LINE, 1),
			BorderFactory.createEmptyBorder(8, 4, 8, 4)));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Short.MAX_VALUE, 46));

		for (JPanel b : blocks)
		{
			row.add(b);
		}
		return row;
	}

	/** A short absolute date, for things that happened once. */
	private static String shortDate(long millis)
	{
		return new java.text.SimpleDateFormat("d MMM yy")
			.format(new java.util.Date(millis));
	}

	private static JPanel statBlock(String value, String caption)
	{
		final JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(NEST_BG);

		final JLabel v = new JLabel(value, SwingConstants.CENTER);
		v.setFont(FontManager.getRunescapeBoldFont());
		v.setForeground(SITE_ACC);
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
		l.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, SITE_ACC),
			BorderFactory.createEmptyBorder(0, 5, 3, 0)));
		l.setAlignmentX(LEFT_ALIGNMENT);

		// without this the label is laid out at its text width and BoxLayout centres
		// it. alignmentX positions a child, it does not stretch one.
		l.setMaximumSize(new Dimension(Short.MAX_VALUE, l.getPreferredSize().height));
		return l;
	}

	/**
	 * The drop list, capped in height and scrolling past that.
	 *
	 * <p>Five rows fit; a longer list scrolls rather than shoving everything below it
	 * down the panel. Short lists get no scrollbar at all — a box that scrolls when it
	 * doesn't need to looks broken.
	 *
	 * <p>The wheel is forwarded to the outer scroll pane once this box hits its end,
	 * so scrolling past the last drop keeps moving the panel instead of dead-stopping
	 * under the cursor. That is the usual complaint about nested scroll panes and it
	 * is worth the twenty lines to avoid.
	 */
	private static JComponent dropBox(JPanel list, int rows)
	{
		// measure, don't assume. a row is 36px of sprite plus its border, so five of
		// them never fitted in 5 * 36 - the last one clipped and there was no
		// scrollbar to reach it because the list "fitted".
		final int rowHeight = rows > 0 && list.getComponentCount() > 0
			? list.getComponent(0).getPreferredSize().height
			: DROP_ROW_HEIGHT;

		if (rows <= VISIBLE_DROPS)
		{
			// it fits, so let it be exactly as tall as it is.
			list.setAlignmentX(LEFT_ALIGNMENT);
			list.setMaximumSize(new Dimension(Short.MAX_VALUE, rows * rowHeight));
			return list;
		}

		final int height = VISIBLE_DROPS * rowHeight;

		final JScrollPane box = new JScrollPane(list,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		box.setBorder(BorderFactory.createEmptyBorder());
		box.setBackground(SITE_PANEL);
		box.getViewport().setBackground(SITE_PANEL);
		box.setAlignmentX(LEFT_ALIGNMENT);
		box.setPreferredSize(new Dimension(0, height));
		box.setMaximumSize(new Dimension(Short.MAX_VALUE, height));

		final JScrollBar bar = box.getVerticalScrollBar();
		bar.setUnitIncrement(rowHeight);
		bar.setPreferredSize(new Dimension(6, 0));
		bar.setUI(flatScrollBar());

		box.addMouseWheelListener(e ->
		{
			final JScrollBar own = box.getVerticalScrollBar();
			final boolean atTop = own.getValue() <= own.getMinimum();
			final boolean atEnd = own.getValue() + own.getVisibleAmount() >= own.getMaximum();
			final boolean scrollingPastTheEnd =
				(e.getWheelRotation() < 0 && atTop) || (e.getWheelRotation() > 0 && atEnd);

			if (scrollingPastTheEnd)
			{
				// hand it upward rather than swallowing it.
				box.getParent().dispatchEvent(
					SwingUtilities.convertMouseEvent(box, e, box.getParent()));
			}
		});

		return box;
	}

	private JPanel row(NpcStat stat)
	{
		// the per-skill split is all-time and session only. day buckets keep a total,
		// not a breakdown, so a windowed row has nothing honest to expand into.
		// day buckets keep a total, not a breakdown - so in a windowed view the xp
		// split and the drop list are ALL-TIME numbers, not that period's. they used to
		// be suppressed entirely, which left Day/Wk/Mth rows with no dropdown at all.
		// showing them labelled beats hiding them; spec says degrade, don't guess.
		final boolean windowed = window != Window.ALL;
		final boolean hasSkills = stat.xpBySkill != null && !stat.xpBySkill.isEmpty();
		final boolean hasDrops = stat.drops != null && !stat.drops.isEmpty();
		final boolean expandable = hasSkills || hasDrops;
		final boolean open = expanded.contains(stat.npcId);

		// the title strip. core gives every LootTrackerBox entry its own darker header
		// with real padding, and that's the whole reason their lists read as rows and
		// ours read as a wall - see the panel research in FINDINGS.
		final JPanel p = new JPanel(new BorderLayout());
		p.setBackground(TITLE_BG);
		p.setBorder(BorderFactory.createEmptyBorder(7, 4, 7, 4));

		// same BoxLayout trap as the section labels: no maximum size means the strip is
		// laid out at its content width and centred, which insets the whole row.
		p.setAlignmentX(LEFT_ALIGNMENT);

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

		// the icon lane is reserved on EVERY row, drawn or not. two reasons: a row
		// with an icon is 24px tall and one without is text-height, so mixing them
		// made the list ragged - and reserving it keeps every name starting in the
		// same column instead of stepping left when a monster has no icon.
		final JLabel face = new JLabel();
		face.setPreferredSize(new Dimension(26, 24));
		face.setHorizontalAlignment(SwingConstants.CENTER);

		final int iconId = npcIcons.forName(stat.name);
		if (iconId > 0)
		{
			// quantity 1: a stack number burnt into a monster's face is nonsense.
			itemManager.getImage(iconId, 1, false).addTo(face);
		}

		final JPanel lead = new JPanel(new BorderLayout());
		lead.setOpaque(false);
		lead.add(face, BorderLayout.WEST);
		lead.add(name, BorderLayout.CENTER);

		p.add(lead, BorderLayout.WEST);
		p.add(right, BorderLayout.EAST);

		// AFTER the children are in. this was hardcoded to 26, then a 24px icon plus
		// 14px of padding needed 38 and every row with a face got crushed into the
		// cap. measuring an empty panel would have been just as wrong - it returns the
		// border and nothing else.
		p.setMaximumSize(new Dimension(Short.MAX_VALUE, p.getPreferredSize().height));

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
					BorderFactory.createEmptyBorder(8, 2, 8, 4)));
				detail.setAlignmentX(LEFT_ALIGNMENT);
				detail.setMaximumSize(new Dimension(Short.MAX_VALUE, Short.MAX_VALUE));

				// the headline numbers as centred blocks - big value, small caption
				// under it. a stack of left-label/right-number rows all at one weight
				// is what made this look like a spreadsheet.
				final JPanel stats = new JPanel(new java.awt.GridLayout(1, 0, 2, 0));
				stats.setBackground(NEST_BG);
				stats.setAlignmentX(LEFT_ALIGNMENT);
				stats.setMaximumSize(new Dimension(Short.MAX_VALUE, 34));

				final long perKill = stat.total() > 0 ? stat.xp / stat.total() : 0L;
				stats.add(statBlock(shortXp(perKill), windowed ? "xp/kill*" : "xp/kill"));
				stats.add(statBlock(stat.uncontested + "/" + stat.total(),
					windowed ? "clean*" : "clean"));
				if (stat.lastKillMillis > 0)
				{
					stats.add(statBlock(ago(stat.lastKillMillis).replace(" ago", ""), "ago"));
				}

				detail.add(stats);

				// a second row for the numbers we were storing and never showing.
				// only the ones that have a value - a row of dashes is worse than a
				// shorter row.
				final JPanel more = new JPanel(new java.awt.GridLayout(1, 0, 2, 0));
				more.setBackground(NEST_BG);
				more.setAlignmentX(LEFT_ALIGNMENT);
				more.setMaximumSize(new Dimension(Short.MAX_VALUE, 34));
				int extras = 0;

				if (stat.fastestTicks > 0)
				{
					// ticks, not seconds. it is what we measured, and 0.6s per tick
					// means converting invents precision we do not have.
					more.add(statBlock(stat.fastestTicks + "t", "fastest"));
					extras++;
				}

				// average damage per kill, over kills we actually measured damage on -
				// dividing by total() would drag it down with every kill recorded
				// before damage tracking existed.
				if (stat.killsWithDamage > 0 && stat.myDamageTotal > 0)
				{
					more.add(statBlock(
						String.valueOf(stat.myDamageTotal / stat.killsWithDamage), "dmg/kill"));
					extras++;
				}

				if (stat.firstKillMillis > 0)
				{
					more.add(statBlock(shortDate(stat.firstKillMillis), "first"));
					extras++;
				}

				if (extras > 0)
				{
					detail.add(javax.swing.Box.createVerticalStrut(4));
					detail.add(more);
				}

				if (hasSkills)
				{
					detail.add(javax.swing.Box.createVerticalStrut(10));
					detail.add(sectionLine(windowed ? "XP  (ALL TIME)" : "XP"));

					// combat skills first, then hitpoints and slayer. sorting purely
					// by value puts Hitpoints top on nearly every monster - it is paid
					// on every hit whatever the style - which buries the skill you
					// actually trained.
					stat.xpBySkill.entrySet().stream()
						.sorted(java.util.Comparator
							.comparingInt((Map.Entry<String, Long> e) -> skillRank(e.getKey()))
							.thenComparing(Map.Entry.<String, Long>comparingByValue().reversed()))
						.forEach(e -> detail.add(skillLine(e.getKey(), e.getValue())));

					// the sum, next to the parts. it was on stat.xp all along.
					detail.add(javax.swing.Box.createVerticalStrut(2));
					detail.add(totalLine("total", shortXp(stat.xp)));
				}

				if (hasDrops)
				{
					detail.add(javax.swing.Box.createVerticalStrut(10));
					detail.add(dropHeader(stat, windowed));

					// by total value, not quantity. nobody opens a drop list to find
					// out how many bones they have.
					// a monster with thirty drop types would push every row below it off
					// the panel, so the list gets its own capped, scrolling box.
					final JPanel list = new JPanel();
					list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
					list.setBackground(SITE_PANEL);

					stat.drops.entrySet().stream()
						.sorted((a, b) -> Long.compare(valueOf(b.getKey(), b.getValue()),
							valueOf(a.getKey(), a.getValue())))
						.forEach(e -> list.add(dropLine(stat, e.getKey(), e.getValue())));

					detail.add(dropBox(list, stat.drops.size()));
				}

				wrap.add(detail);
			}
		}

		// pin the height. without a maximum, BoxLayout treats every row as stretchable
		// and redistributes space when a sibling expands - which is what squashed the
		// collapsed rows and clipped their names.
		wrap.setMaximumSize(new Dimension(Short.MAX_VALUE, wrap.getPreferredSize().height));
		return wrap;
	}

	/** DROPS heading with the pile's total value aligned over the gp column. */
	private JPanel dropHeader(NpcStat stat, boolean windowed)
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

		final JLabel l = new JLabel(windowed ? "DROPS  (ALL TIME)" : "DROPS");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(SITE_FG_FAINT);
		l.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, SITE_ACC),
			BorderFactory.createEmptyBorder(0, 5, 0, 0)));

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
		p.setBackground(SITE_PANEL);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, NEST_BG),
			BorderFactory.createEmptyBorder(3, 4, 3, 6)));
		p.setMaximumSize(new Dimension(Short.MAX_VALUE, 36));
		p.setAlignmentX(LEFT_ALIGNMENT);

		// an id is not a name, but it beats showing nothing when the composition
		// wasn't loaded at the time - and it stays diagnosable.
		final String label = tally.name != null ? tally.name : "item " + itemId;

		final JLabel left = new JLabel(label);
		left.setFont(FontManager.getRunescapeSmallFont());
		left.setForeground(SITE_FG);
		left.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 4));

		// a long item name must not widen the row - core sets the same 1px minimum in
		// LootTrackerBox with the comment "make BoxLayout truncate the name".
		left.setMinimumSize(new Dimension(1, left.getPreferredSize().height));

		// the icon, with the stack count drawn into it when there's more than one -
		// that's what the quantity argument buys. async, so it appears when ready
		// rather than holding up the repaint.
		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		try
		{
			final int id = Integer.parseInt(itemId);
			// quantity 1: the count column already says how many, and the stack
			// number baked into the sprite printed it a second time on the icon.
			itemManager.getImage(id, 1, false).addTo(icon);
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
		count.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 3));
		count.setPreferredSize(new Dimension(18, count.getPreferredSize().height));

		final long value = valueOf(itemId, tally);
		final JLabel worth = new JLabel(value > 0 ? gp(value) : "");
		worth.setFont(FontManager.getRunescapeSmallFont());
		worth.setForeground(value > 0 ? SITE_GOLD : SITE_FG_FAINT);

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

	/** Combat skills before the ones every style pays into. */
	private static int skillRank(String skill)
	{
		switch (skill.toLowerCase())
		{
			case "attack":
			case "strength":
			case "defence":
			case "ranged":
			case "magic":
				return 0;
			case "hitpoints":
				return 1;
			default:
				return 2;
		}
	}

	/** A skill line with a rule above it, for the sum of the lines before it. */
	private static JPanel totalLine(String label, String value)
	{
		final JPanel p = new JPanel(new BorderLayout());
		p.setBackground(NEST_BG);
		p.setAlignmentX(LEFT_ALIGNMENT);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, SITE_LINE),
			BorderFactory.createEmptyBorder(3, 0, 0, 0)));
		p.setMaximumSize(new Dimension(Short.MAX_VALUE, 16));

		final JLabel l = new JLabel(label);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(SITE_FG_DIM);

		final JLabel v = new JLabel(value);
		v.setFont(FontManager.getRunescapeSmallFont());
		v.setForeground(SITE_FG);

		p.add(l, BorderLayout.WEST);
		p.add(v, BorderLayout.EAST);
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



	/**
	 * What the row does not already say.
	 *
	 * <p>This used to repeat the grade split, xp and dates that expanding the row now
	 * shows, and led with {@code npc_id} — a debug field on a tooltip that fires every
	 * time the cursor crosses the panel. What is left is the grade split, because
	 * that is provenance and appears nowhere else in the UI once the overlay's split
	 * was turned off.
	 */
	private static String tooltip(NpcStat stat)
	{
		final StringBuilder sb = new StringBuilder("<html>");
		sb.append(span(Confidence.UNCONTESTED, stat.uncontested + " uncontested"));

		if (stat.inferred > 0)
		{
			sb.append("<br>").append(span(Confidence.INFERRED, stat.inferred + " inferred"));
		}

		if (stat.ambiguous > 0)
		{
			sb.append("<br>").append(span(Confidence.AMBIGUOUS, stat.ambiguous + " ambiguous"));
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
