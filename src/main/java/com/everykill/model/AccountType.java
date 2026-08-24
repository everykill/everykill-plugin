/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.model;

/**
 * What kind of account this is, because the loot rules differ by mode.
 *
 * <h2>Why the client cares at all</h2>
 *
 * A main who deals 90% of a contested kill <b>wins the drop</b> — the game gives it to
 * whoever dealt the most damage. An ironman in the same fight gets <b>nothing</b>:
 * measured 2026-08-24, 17 kills, 8 of them contested, zero loot on all 8 including one
 * at 90% of the damage. Applying either rule to the wrong account type is silent data
 * corruption, so nothing may assume.
 *
 * <h2>Where the values come from</h2>
 *
 * {@code VarbitID.IRONMAN} (1777). Core reads the same varbit in
 * {@code GroundItemsPlugin} and {@code HiscorePlugin}; the mapping below is theirs, not
 * ours, and {@code DailyTasksPlugin} corroborates 2 = ultimate in a comment.
 *
 * <h2>The trap</h2>
 *
 * <b>Group ironman is not in this varbit.</b> Core's own switch has no case for it and
 * falls through to normal. Group status comes from
 * {@code client.getClanSettings(ClanID.GROUP_IRONMAN)} — the group's clan channel, not
 * an account varbit. So a GIM reading this alone looks like {@link #MAIN} or
 * {@link #IRONMAN}, and neither is right: the game's own warning says <i>"players
 * outside your group"</i>, so a groupmate's damage does not void their drop.
 *
 * {@link #GROUP_UNRESOLVED} exists so that gap is visible rather than silently wrong.
 * Resolving it needs the clan-settings read, which isn't written yet.
 */
public enum AccountType
{
	MAIN("Main", 0),
	IRONMAN("Ironman", 1),
	ULTIMATE_IRONMAN("Ultimate Ironman", 2),
	HARDCORE_IRONMAN("Hardcore Ironman", 3),

	/**
	 * The varbit said something we don't have a name for.
	 *
	 * <p>Not folded into {@link #MAIN}: an unknown mode treated as a main would have
	 * ironman rules silently switched off. Callers must decide explicitly.
	 */
	GROUP_UNRESOLVED("Unresolved", -1),

	/** Not logged in, or the varbit hasn't been read yet. */
	UNKNOWN("Unknown", -2);

	private final String label;
	private final int varbitValue;

	AccountType(String label, int varbitValue)
	{
		this.label = label;
		this.varbitValue = varbitValue;
	}

	public String getLabel()
	{
		return label;
	}

	public int getVarbitValue()
	{
		return varbitValue;
	}

	/**
	 * Maps a raw {@code VarbitID.IRONMAN} value.
	 *
	 * <p>An unrecognised value becomes {@link #GROUP_UNRESOLVED} rather than
	 * {@link #MAIN}, because Jagex adding a mode must not quietly turn the ironman
	 * rules off for accounts that need them.
	 */
	public static AccountType fromVarbit(int value)
	{
		for (AccountType type : values())
		{
			if (type.varbitValue == value && value >= 0)
			{
				return type;
			}
		}
		return GROUP_UNRESOLVED;
	}

	/**
	 * Whether another player's involvement voids this account's drops entirely.
	 *
	 * <p>True for every ironman mode. False for a main, whose contested kills still
	 * roll — they just lose to whoever dealt more damage.
	 *
	 * <p>{@link #GROUP_UNRESOLVED} answers <b>false</b> on purpose. Being wrong in that
	 * direction means a groupmate's fair kill stays in the denominator; being wrong the
	 * other way would void every legitimate group kill a GIM ever makes. Neither is
	 * good, and the fix is resolving group status, not picking the tidier default.
	 */
	public boolean outsideDamageVoidsLoot()
	{
		return this == IRONMAN || this == ULTIMATE_IRONMAN || this == HARDCORE_IRONMAN;
	}
}
