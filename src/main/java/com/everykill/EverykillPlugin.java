/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill;

import com.everykill.detect.KillDetector;
import com.everykill.detect.LootDetector;
import com.everykill.ledger.LocalLedger;
import com.everykill.model.AccountType;
import net.runelite.api.clan.ClanID;
import net.runelite.api.gameval.VarbitID;
import com.everykill.model.Confidence;
import com.everykill.model.Drop;
import com.everykill.model.LootConfidence;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.everykill.model.KillRecord;
import com.everykill.model.NpcStat;
import com.everykill.notice.MilestoneNotifier;
import com.everykill.ui.EverykillOverlay;
import com.everykill.ui.EverykillPanel;
import com.everykill.upload.UploadService;
import com.everykill.xp.XpService;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

/**
 * Kill counts for every monster, not just the ~90 on the hiscores.
 *
 * <p>This thing records. It does not analyse. Rates, ranks, dry streaks - all server
 * side. Before adding anything here ask: can it be shown with no network call? No?
 * Then it's not a client feature, and hub review stays boring, which is the point.
 *
 * <p>Runs alongside Loot Tracker rather than replacing it. That counts kills that
 * dropped something and guesses ownership from the loot. We count from damage, which
 * is the only way a lootless kill ever shows up.
 *
 * <p>P1 is local only. The upload toggle exists, is off, and nothing reads it - it's
 * there early so the disclosure can't get forgotten later.
 */
@Slf4j
@PluginDescriptor(
	name = "Everykill",
	description = "Kill counts for every monster in the game, not just the ~90 on the hiscores",
	tags = {"kill", "count", "kc", "slayer", "boss", "monster", "tracker", "rank"}
)
public class EverykillPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private EverykillConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private EverykillOverlay overlay;

	@Inject
	private EverykillPanel panel;

	@Inject
	private KillDetector detector;

	@Inject
	private ItemManager itemManager;

	@Inject
	private LootDetector lootDetector;

	@Inject
	private LocalLedger ledger;

	@Inject
	private MilestoneNotifier notifier;

	@Inject
	private XpService xpService;

	@Inject
	private UploadService uploadService;

	private NavigationButton navButton;

	// guards against reloading the ledger on every loading zone
	private boolean ledgerLoaded;

	@Provides
	EverykillConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(EverykillConfig.class);
	}

	@Override
	protected void startUp()
	{
		// deliberately does NOT set ledgerLoaded. startUp runs before login, there's no
		// rs profile yet, so this reads nothing. marking it loaded here means we never
		// pick up the real counts and then save an empty map over them.
		ledger.load();
		ledger.startSession();
		uploadService.startUp();
		notifier.startSession();
		detector.reset();
		xpService.reset();
		detector.setDamageListener(xpService::damage);

		overlayManager.add(overlay);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/panel_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Everykill")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
		panel.refresh();
	}

	@Override
	protected void shutDown()
	{
		// cancel first: a flush firing mid-teardown would work against a ledger that
		// is being written out from under it.
		uploadService.shutDown();
		ledger.flush();
		overlayManager.remove(overlay);
		clientToolbar.removeNavigation(navButton);
		detector.setDamageListener(null);
		detector.reset();
		xpService.reset();
	}

	// Note what is absent: no animation, projectile or incoming-hitsplat
	// subscription, and no per-boss branch. See KillDetector for why.

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!config.recordKills())
		{
			return;
		}
		detector.onHitsplatApplied(event);
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (!config.recordKills())
		{
			return;
		}
		detector.onActorDeath(event, this::onKill);
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		if (!config.recordKills())
		{
			return;
		}
		detector.onNpcDespawned(event, this::onKill);
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		detector.onNpcChanged(event);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!config.recordKills())
		{
			return;
		}
		detector.onMenuOptionClicked(event);
	}

	/** XP is the game's own number; damage only says which monster it came from. */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (!config.recordKills())
		{
			return;
		}
		xpService.onStatChanged(event);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// a tick has passed so the skill data is actually here and the login burst is
		// over. GameStateChanged is too early, see prime().
		if (!xpService.isPrimed() && client.getGameState() == GameState.LOGGED_IN)
		{
			xpService.prime();
		}

		// kills resolved during this tick come out here, not at the moment of death -
		// their loot arrives after they do. see KillStateMachine.resolve.
		detector.onGameTick(event, this::onKillWithLoot);
		xpService.drain(ledger::addXp);

		// after the kills for this tick have resolved, so anything still buffered is
		// genuinely unclaimed rather than just early. nothing joins loot to kills yet -
		// step 6 measures first, see docs/plan-step6-loot.md.
		lootDetector.expire(client.getTickCount());
	}

	// step 6, capture only. the server names the monster and the item outright, and
	// LOOTTRACKER_ADD_LOOT carries a per-kill eventId that ServerNpcLoot throws away
	// before posting - measured 77265..77268 across four cyclops kills. that id is the
	// only thing that can separate two identical monsters dying together, so we read
	// the script rather than the tidier event.
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		lootDetector.onScriptPreFired(event);
	}

	// KEPT ON PURPOSE - not a leftover, don't sweep it before checking FINDINGS.
	//
	// the game just tells ironmen when someone else damaged their target. that's free
	// ground truth for the contest AMBIGUOUS is meant to catch, and we are demonstrably
	// missing it: thirteen multicombat kills on 2026-08-20 showed zero foreign damage to
	// us while the game printed this warning on every one.
	//
	// nothing branches on it and no player name is ever read - it exists to measure how
	// often our own tracking misses a contest. exit condition: once that miss rate is
	// known, either build it into grading properly or bin it. ironman-only, so it can
	// never be the primary mechanism.
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		final String message = event.getMessage();
		if (message.contains("kill-credit") || message.contains("helped you kill"))
		{
			log.debug("Contest signal from the game: \"{}\"", message);
		}
	}

	/** Resets the <b>session</b> only. All-time counts are untouched. */
	@Subscribe
	public void onOverlayMenuClicked(OverlayMenuClicked event)
	{
		if (event.getOverlay() != overlay
			|| !EverykillOverlay.RESET_OPTION.equals(event.getEntry().getOption()))
		{
			return;
		}

		ledger.startSession();
		notifier.startSession();
		panel.refresh();
		log.debug("session counters reset from the overlay menu");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// once per login, NOT per scene load. fires at every loading zone and
			// load() nukes the map wholesale, so reloading here threw away any xp
			// accrued since the last kill saved. four times in 32s, measured.
			if (!ledgerLoaded)
			{
				ledger.load();
				ledgerLoaded = true;
			}
			panel.refresh();

			// no xp seeding here either, however much it looks like the right spot.
			// see XpService.prime().
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			// xp since the last kill has nothing to ride out on now
			ledger.flush();
			ledgerLoaded = false;

			// Partial fights do not survive a logout. XP baselines go with them: a
			// stale one would read the whole gap as a single enormous gain.
			detector.reset();
			xpService.reset();
		}
	}

	// ------------------------------------------------------------------

	/**
	 * Attaches the server's loot to a kill, then records it.
	 *
	 * <p>Runs on the tick boundary, after the kill was held for exactly this — the
	 * loot event lands after the death, measured 2026-08-24.
	 */
	private void onKillWithLoot(KillRecord kill)
	{
		final List<LootDetector.ServerLoot> reported =
			lootDetector.drainFor(kill.npcId, client.getTickCount());

		for (LootDetector.ServerLoot loot : reported)
		{
			loot.resolveNames(this::itemName, this::itemPrice);
		}

		onKill(attachLoot(kill, reported, accountType()));
	}

	/**
	 * Decides what a kill's loot is, and how much that answer can be trusted.
	 *
	 * <p>Static and free of client state so the decision can be tested directly. The
	 * hard case is two of the same monster dying together: the server reports two
	 * drops for one npc id and nothing distinguishes which kill earned which. That is
	 * {@code UNKNOWN}, and per spec-drop-attribution those kills leave drop-rate
	 * denominators entirely rather than being counted as dry.
	 */
	static KillRecord attachLoot(KillRecord kill, List<LootDetector.ServerLoot> reported,
		AccountType accountType)
	{
		if (reported.isEmpty())
		{
			// NOT "dropped nothing". could be a genuinely lootless monster, an
			// ironman's voided drop, or us missing it - LootConfidence.NONE says so.
			return kill.withLoot(Collections.emptyList(), LootConfidence.NONE);
		}

		// an ironman whose kill someone else touched should not have received this at
		// all - measured 2026-08-24, 8 contested kills, zero loot, one at 90% of the
		// damage. so if loot turns up on one anyway, our contest detection and the
		// server disagree about the same kill, and the safe reading is that ours is
		// wrong. UNKNOWN keeps it out of denominators either way.
		//
		// a MAIN in the same position is fine and must not be filtered: most damage
		// wins the drop, so a contested kill paying out is exactly correct.
		final boolean voided = accountType.outsideDamageVoidsLoot()
			&& (kill.othersDamage > 0 || kill.grade == Confidence.AMBIGUOUS);

		if (voided)
		{
			final List<Drop> all = new ArrayList<>();
			for (LootDetector.ServerLoot loot : reported)
			{
				addAll(all, loot);
			}
			return kill.withLoot(all, LootConfidence.UNKNOWN);
		}

		if (reported.size() > 1)
		{
			// two same-id kills on one tick. we have the items but not which kill they
			// belong to, so hand them over labelled rather than guessing or dropping
			// them on the floor.
			final List<Drop> all = new ArrayList<>();
			for (LootDetector.ServerLoot loot : reported)
			{
				addAll(all, loot);
			}
			return kill.withLoot(all, LootConfidence.UNKNOWN);
		}

		final List<Drop> drops = new ArrayList<>();
		addAll(drops, reported.get(0));

		// the loot is unambiguous; whether it's rate-eligible is the KILL's problem.
		// a contested kill's drop is real and ours, it just can't sit in a denominator.
		final LootConfidence confidence = kill.grade == Confidence.UNCONTESTED
			? LootConfidence.CONFIRMED
			: LootConfidence.PROBABLE;

		return kill.withLoot(drops, confidence);
	}

	private static void addAll(List<Drop> into, LootDetector.ServerLoot loot)
	{
		for (ItemStack item : loot.getItems())
		{
			into.add(new Drop(item.getId(), item.getQuantity(),
				loot.nameOf(item.getId()), loot.priceOf(item.getId())));
		}
	}

	/**
	 * The item's name, resolved here because we're on the client thread.
	 *
	 * <p>ItemManager reads through to the client and the panel paints on Swing, so the
	 * name is captured at drop time and stored. Core's loot tracker resolves names in
	 * the plugin for the same reason.
	 */
	/** Price per item, read here because getItemPrice asserts the client thread. */
	private int itemPrice(int itemId)
	{
		try
		{
			return itemManager.getItemPrice(itemId);
		}
		catch (RuntimeException | AssertionError e)
		{
			return 0;
		}
	}

	private String itemName(int itemId)
	{
		try
		{
			final String name = itemManager.getItemComposition(itemId).getName();
			return name == null || name.isEmpty() || "null".equals(name) ? null : name;
		}
		catch (RuntimeException e)
		{
			// an unknown id is not worth losing the drop over. the panel falls back to
			// showing the raw id, which stays diagnosable.
			return null;
		}
	}

	/** Compact drop list for the kill log, so a hand check can read it. */
	private static String describe(List<Drop> drops)
	{
		if (drops.isEmpty())
		{
			return "-";
		}

		final StringBuilder sb = new StringBuilder();
		for (Drop drop : drops)
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(drop.itemId).append('x').append(drop.quantity);
		}
		return sb.toString();
	}

	/**
	 * What kind of account we're on, read live rather than cached at login.
	 *
	 * <p>Core reads this varbit at the point of use too. People de-iron mid-session,
	 * hardcores die and become regular irons, and {@code spec-data-model.md} stores
	 * account type <b>per session, not per player</b> for exactly that reason.
	 */
	private AccountType accountType()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return AccountType.UNKNOWN;
		}

		final AccountType fromVarbit =
			AccountType.fromVarbit(client.getVarbitValue(VarbitID.IRONMAN));

		// group ironman isn't in that varbit at all - core's own switch has no case for
		// it and falls through. it lives in the group's clan channel instead, which is
		// how NameAutocompleter finds it. verified live 2026-08-24: the test account is a GIM and
		// the varbit alone reported UNRESOLVED.
		if (fromVarbit == AccountType.GROUP_UNRESOLVED
			&& client.getClanSettings(ClanID.GROUP_IRONMAN) != null)
		{
			return AccountType.GROUP_IRONMAN;
		}

		return fromVarbit;
	}

	private void onKill(KillRecord kill)
	{
		final NpcStat before = ledger.get(kill.npcId);
		final boolean firstEver = before == null || before.total() == 0;

		final NpcStat after = ledger.record(kill);

		// queued, not sent. the service decides when, and drops it on the floor
		// when upload is off - so toggling it on never uploads a backlog.
		uploadService.offer(kill);

		// what a hand count gets checked against. without it a wrong total is just a
		// wrong number. needs --debug.
		log.debug("Kill: npc_id={} name={} grade={} signal={} region={} dmg={}/{} attacks={} hits={} maxHit={} kc={} xp={} sessionKills={} unallocatedXp={} account={} loot={} drops={}",
			kill.npcId, kill.npcName, kill.grade, kill.signal, kill.regionId,
			kill.myDamage, kill.totalDamage(), kill.attacksCount, kill.hitsCount, kill.maxHit,
			after.total(), after.xp, ledger.getSessionKills(), xpService.getUnallocatedXp(), accountType(),
			kill.lootConfidence, describe(kill.drops));

		notifier.onKillRecorded(kill, after, firstEver);
		panel.refresh();
	}
}
