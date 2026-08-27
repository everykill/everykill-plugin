/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.detect;

import com.everykill.model.AccountType;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.clan.ClanID;
import net.runelite.api.gameval.VarbitID;

/**
 * Reads the account's game mode.
 *
 * <p>Lifted out of the plugin class because two callers now need it — the loot rules,
 * which have always used it, and the publish path, which offers ironmen the choice of
 * withholding it. Two copies of this would be two chances to disagree about whether
 * someone is a hardcore.
 *
 * <p>Must be called on the client thread.
 */
@Singleton
public class AccountTypes
{
	private final Client client;

	@Inject
	public AccountTypes(Client client)
	{
		this.client = client;
	}

	/**
	 * The current mode, or {@link AccountType#UNKNOWN} when not logged in.
	 *
	 * <p>Read fresh every time rather than cached at login. Mode changes mid-session:
	 * a hardcore that dies becomes a regular ironman immediately, and a cached value
	 * would keep publishing "Hardcore" to a public board after the fact — wrong in
	 * exactly the direction that matters for someone who did not want the label.
	 */
	public AccountType get()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return AccountType.UNKNOWN;
		}

		final AccountType fromVarbit =
			AccountType.fromVarbit(client.getVarbitValue(VarbitID.IRONMAN));

		// a fallen hardcore reads as a plain ironman in VarbitID.IRONMAN - the mode
		// really did change. IRONMAN_HARDCORE_DEAD (5403) is what separates one from
		// an account that was never hardcore.
		//
		// UNVERIFIED: no core plugin reads 5403, so the name comes from the cache
		// constants and the "nonzero means dead" reading is inference, not a
		// measurement. designed so being wrong is cheap - a false positive labels a
		// regular iron as fallen-hardcore on a board they were not on, and every loot
		// rule is identical either way. the server's own mode-transition check is the
		// authority; this is the fast path so a fresh install can tell.
		if (fromVarbit == AccountType.IRONMAN
			&& client.getVarbitValue(VarbitID.IRONMAN_HARDCORE_DEAD) != 0)
		{
			return AccountType.DEAD_HARDCORE_IRONMAN;
		}

		// group ironman isn't in that varbit at all - core's own switch has no case
		// for it and falls through. it lives in the group's clan channel instead.
		// verified live 2026-08-24: the test account is a GIM and the varbit alone
		// reported UNRESOLVED.
		if (fromVarbit == AccountType.GROUP_UNRESOLVED
			&& client.getClanSettings(ClanID.GROUP_IRONMAN) != null)
		{
			return AccountType.GROUP_IRONMAN;
		}

		return fromVarbit;
	}
}
