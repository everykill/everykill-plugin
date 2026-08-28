/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.ui;

import com.everykill.EverykillConfig;
import com.everykill.EverykillPlugin;
import com.everykill.ledger.LocalLedger;
import com.everykill.model.Confidence;
import com.everykill.model.NpcStat;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Session counter on the canvas - kills, what you're farming, grade split.
 *
 * <p>The number moving is the whole trust mechanism. If a player can't watch it tick
 * up they assume we're broken, and they're not wrong to.
 *
 * <p>TOP_LEFT on ABOVE_SCENE: over the world, under every game widget, so it can't
 * cover an interface. Alt-drag to move it, that's RuneLite's job not ours.
 */
public class EverykillOverlay extends OverlayPanel
{
	public static final String RESET_OPTION = "Reset";
	public static final String MENU_TARGET = "Everykill session";

	/** everykill-site --acc. Same rust as the panel and the hub icon. */
	private static final Color BRAND = new Color(0xd9, 0x4f, 0x2b);

	private final Client client;
	private final EverykillConfig config;
	private final LocalLedger ledger;

	@Inject
	EverykillOverlay(EverykillPlugin plugin, Client client, EverykillConfig config,
		LocalLedger ledger)
	{
		super(plugin);
		this.client = client;
		this.config = config;
		this.ledger = ledger;

		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPreferredSize(new Dimension(158, 0));

		// Handled in EverykillPlugin.onOverlayMenuClicked — resetting touches the
		// ledger and the notifier, which an overlay has no business reaching into.
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, RESET_OPTION, MENU_TARGET);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// cutscenes are the game telling a story with the hud out of the way. an
		// overlay sitting on top of one is the plugin talking over it. core does the
		// same check in TileIndicatorsOverlay.
		if (client.getVarbitValue(VarbitID.CUTSCENE_STATUS) == 1)
		{
			return null;
		}

		if (!config.showOverlay())
		{
			return null;
		}

		final int kills = ledger.getSessionKills();

		if (config.compactOverlay())
		{
			final NpcStat focus = ledger.sessionFocus();
			panelComponent.getChildren().add(LineComponent.builder()
				.left(focus == null ? "Everykill" : trim(focus.name))
				.right(focus == null ? String.valueOf(kills) : focus.total() + " / " + lifetime(focus))
				.build());
			return super.render(graphics);
		}

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Everykill")
			// the site's --acc. it used to borrow Confidence.INFERRED's colour purely
			// because it looked right, which quietly taught people that the title
			// meant "inferred" - grade colours have to mean grades everywhere.
			.color(BRAND)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Session")
			.right(String.valueOf(kills))
			.build());

		final NpcStat focus = ledger.sessionFocus();
		if (focus != null)
		{
			// spec asks for kills this session AND lifetime kc. session-only was
			// half the line - the lifetime number is the one you're actually
			// chasing, and sessionFocus() returns the SESSION row, so it has to be
			// looked up separately rather than read off the same object.
			panelComponent.getChildren().add(LineComponent.builder()
				.left(trim(focus.name))
				.right(focus.total() + " / " + lifetime(focus))
				.build());
		}

		if (config.showGradeSplit() && kills > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("uncontested")
				.leftColor(Confidence.UNCONTESTED.getColor())
				.right(String.valueOf(ledger.sessionCount(Confidence.UNCONTESTED)))
				.rightColor(Confidence.UNCONTESTED.getColor())
				.build());

			final int inferred = ledger.sessionCount(Confidence.INFERRED);
			if (inferred > 0)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("inferred")
					.leftColor(Confidence.INFERRED.getColor())
					.right(String.valueOf(inferred))
					.rightColor(Confidence.INFERRED.getColor())
					.build());
			}

			final int ambiguous = ledger.sessionCount(Confidence.AMBIGUOUS);
			if (ambiguous > 0)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("ambiguous")
					.leftColor(Confidence.AMBIGUOUS.getColor())
					.right(String.valueOf(ambiguous))
					.rightColor(Confidence.AMBIGUOUS.getColor())
					.build());
			}
		}

		return super.render(graphics);
	}

	/**
	 * Lifetime KC for a monster the session row is tracking.
	 *
	 * <p>{@code sessionFocus()} hands back the SESSION stat, whose {@code total()} is
	 * this sitting's kills. The all-time row is a different object with the same npc
	 * id, so it has to be fetched rather than read off the one we already have.
	 */
	private String lifetime(NpcStat focus)
	{
		final NpcStat allTime = ledger.get(focus.npcId);
		return String.valueOf(allTime == null ? focus.total() : allTime.total());
	}

	private static String trim(String name)
	{
		if (name == null)
		{
			return "Unknown";
		}
		return name.length() > 16 ? name.substring(0, 15) + "…" : name;
	}
}
