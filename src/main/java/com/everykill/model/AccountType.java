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
	 * Group ironman. Not a varbit value — this is only reachable by checking the
	 * group's clan channel, so {@link #fromVarbit} can never return it.
	 *
	 * <p>Outside damage voids their loot like any other iron, but <b>a groupmate is
	 * not outside</b>: the game's warning reads <i>"players outside your group helped
	 * you kill the monster"</i>. Telling those apart needs to know who dealt the
	 * damage, which the kill record does not currently carry.
	 */
	GROUP_IRONMAN("Group Ironman", -3),

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
	 * <p>True for every ironman mode including {@link #GROUP_IRONMAN} — but for a group
	 * account "another player" means someone <b>outside the group</b>. A groupmate's
	 * damage voids nothing, and the kill record does not currently carry who dealt what,
	 * so a GIM's contested kills come out conservative rather than exact.
	 *
	 * <p>False for a main, whose contested kills still roll — they just lose to whoever
	 * dealt more damage.
	 *
	 * <p>{@link #GROUP_UNRESOLVED} answers <b>false</b> on purpose. Being wrong in that
	 * direction means a fair kill stays in a denominator; being wrong the other way
	 * voids legitimate kills wholesale.
	 */
	public boolean outsideDamageVoidsLoot()
	{
		return this == IRONMAN || this == ULTIMATE_IRONMAN || this == HARDCORE_IRONMAN
			|| this == GROUP_IRONMAN;
	}

	/** Any ironman mode, group included. */
	public boolean isIronman()
	{
		return outsideDamageVoidsLoot();
	}
}
