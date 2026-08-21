/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill;

import com.everykill.detect.KillDetector;
import com.everykill.ledger.LocalLedger;
import com.everykill.model.KillRecord;
import com.everykill.model.NpcStat;
import com.everykill.notice.MilestoneNotifier;
import com.everykill.ui.EverykillOverlay;
import com.everykill.ui.EverykillPanel;
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
	private LocalLedger ledger;

	@Inject
	private MilestoneNotifier notifier;

	@Inject
	private XpService xpService;

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

		detector.onGameTick(event);
		xpService.drain(ledger::addXp);
	}

	// temp: the game just tells ironmen when someone else damaged their target. free
	// ground truth for the contest AMBIGUOUS is supposed to catch, and we were missing
	// it. nothing branches on this, no player names. bin it once we know the miss rate.
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

	private void onKill(KillRecord kill)
	{
		final NpcStat before = ledger.get(kill.npcId);
		final boolean firstEver = before == null || before.total() == 0;

		final NpcStat after = ledger.record(kill);

		// what a hand count gets checked against. without it a wrong total is just a
		// wrong number. needs --debug.
		log.debug("Kill: npc_id={} name={} grade={} signal={} region={} dmg={}/{} attacks={} hits={} maxHit={} kc={} xp={} sessionKills={} unallocatedXp={}",
			kill.npcId, kill.npcName, kill.grade, kill.signal, kill.regionId,
			kill.myDamage, kill.totalDamage(), kill.attacksCount, kill.hitsCount, kill.maxHit,
			after.total(), after.xp, ledger.getSessionKills(), xpService.getUnallocatedXp());

		notifier.onKillRecorded(kill, after, firstEver);
		panel.refresh();
	}
}
