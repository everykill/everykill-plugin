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
 * Everykill — kill counts for every monster, not just the ~90 on the hiscores.
 *
 * The plugin <b>records</b>; it does not analyse. Rates, percentiles, ranks and
 * streaks are server-side, which keeps Plugin Hub review trivial and lets the site
 * grow without a plugin release. The rule for anything proposed here: <i>could it be
 * displayed with no network call?</i> If not, it does not belong in the client.
 *
 * <p>It runs <b>alongside</b> Loot Tracker, which already holds per-NPC counts — but
 * only for kills that dropped something, and it infers ownership from loot rather
 * than damage. Counting from damage is what makes lootless kills visible.
 *
 * <p><b>P1: local only.</b> The upload toggle exists, is off, and nothing reads it —
 * it ships early so the required disclosure cannot be forgotten.
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

	@Provides
	EverykillConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(EverykillConfig.class);
	}

	@Override
	protected void startUp()
	{
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
		// One full tick after login has passed, so the client's skill data has arrived
		// and the login XP burst is over. GameStateChanged is too early — see
		// XpService.prime().
		if (!xpService.isPrimed() && client.getGameState() == GameState.LOGGED_IN)
		{
			xpService.prime();
		}

		detector.onGameTick(event);
		xpService.drain(ledger::addXp);
	}

	/**
	 * <b>Diagnostic only, P1.</b> The game tells an ironman outright when another
	 * player has damaged their target — authoritative ground truth for exactly the
	 * contest our {@code AMBIGUOUS} grade is supposed to catch. Logging it beside our
	 * own grade turns "we think attribution is wrong" into a measured miss rate.
	 *
	 * <p>Nothing branches on this. It reads no player's name and records nothing about
	 * anyone else — it is our own client's message to us. Remove once the miss rate is
	 * understood, or promote it to a real signal if it proves reliable.
	 */
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
			// The RS profile may have changed under us; counts are profile-scoped.
			ledger.load();
			panel.refresh();

			// XP baselines are deliberately NOT seeded here — see XpService.prime().
			// This event fires on every scene load, and the first one lands before the
			// client has any skill data.
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			// Experience measured since the last kill has nothing to ride out on now.
			ledger.flush();

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

		// One line per recorded kill, at debug. This is the audit trail a hand count is
		// checked against — without it a wrong total is a number with no explanation,
		// and there is no way to tell a missed kill from a double-count from a
		// misgrade. Run the dev client with --debug to see it.
		log.debug("Kill: npc_id={} name={} grade={} signal={} region={} dmg={}/{} attacks={} hits={} maxHit={} kc={} xp={} sessionKills={} unallocatedXp={}",
			kill.npcId, kill.npcName, kill.grade, kill.signal, kill.regionId,
			kill.myDamage, kill.totalDamage(), kill.attacksCount, kill.hitsCount, kill.maxHit,
			after.total(), after.xp, ledger.getSessionKills(), xpService.getUnallocatedXp());

		notifier.onKillRecorded(kill, after, firstEver);
		panel.refresh();
	}
}
