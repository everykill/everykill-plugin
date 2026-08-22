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
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
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

	private final EverykillConfig config;
	private final LocalLedger ledger;

	@Inject
	EverykillOverlay(EverykillPlugin plugin, EverykillConfig config, LocalLedger ledger)
	{
		super(plugin);
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
		if (!config.showOverlay())
		{
			return null;
		}

		final int kills = ledger.getSessionKills();

		if (config.compactOverlay())
		{
			final NpcStat focus = ledger.sessionFocus();
			panelComponent.getChildren().add(LineComponent.builder()
				.left(String.valueOf(kills))
				.right(focus == null ? "" : String.valueOf(focus.total()))
				.build());
			return super.render(graphics);
		}

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Everykill")
			.color(Confidence.INFERRED.getColor())
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Session")
			.right(String.valueOf(kills))
			.build());

		final NpcStat focus = ledger.sessionFocus();
		if (focus != null)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left(trim(focus.name))
				.right(String.valueOf(focus.total()))
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

	private static String trim(String name)
	{
		if (name == null)
		{
			return "Unknown";
		}
		return name.length() > 16 ? name.substring(0, 15) + "…" : name;
	}
}
