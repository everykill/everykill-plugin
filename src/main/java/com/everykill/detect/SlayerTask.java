/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.detect;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * The current slayer task, read from the game rather than guessed.
 *
 * <p>Name and location come out of the client's own DB tables, which is how core's
 * slayer plugin does it — no scraped task list to go stale when Jagex adds a
 * monster, and no name-matching heuristics.
 *
 * <p>Everything here must be called on the client thread.
 */
@Slf4j
@Singleton
public class SlayerTask
{
	/** From {@code [proc,helper_slayer_current_assignment]} — "Bosses" is a sublist. */
	private static final int TASK_ID_BOSSES = 98;

	/** SLAYER_MODIFIER_ID 2 means the amount was adjusted at assignment. */
	private static final int MODIFIER_AMOUNT = 2;

	private final Client client;

	@Inject
	public SlayerTask(Client client)
	{
		this.client = client;
	}

	/** Kills left on the current task, or 0 when there is no task. */
	public int remaining()
	{
		return client.getVarpValue(VarPlayerID.SLAYER_COUNT);
	}

	/** Whether a task is currently assigned. */
	public boolean active()
	{
		return remaining() > 0;
	}

	/**
	 * The task's monster name in the game's own wording, or null.
	 *
	 * <p>Upper case as the DB stores it — the caller decides how to present it.
	 */
	public String name()
	{
		if (!active())
		{
			return null;
		}

		try
		{
			final int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
			final int row = taskId == TASK_ID_BOSSES ? bossRow() : taskRow(taskId);
			if (row < 0)
			{
				return null;
			}

			final Object[] field = client.getDBTableField(
				row, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0);
			return field.length > 0 ? (String) field[0] : null;
		}
		catch (RuntimeException e)
		{
			// the db tables are cache data; a miss is a missing name, not a crash.
			log.debug("everykill: could not read slayer task name", e);
			return null;
		}
	}

	/** Where the task was assigned for, or null when it is unrestricted. */
	public String location()
	{
		final int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);
		if (areaId <= 0)
		{
			return null;
		}

		try
		{
			final var rows = client.getDBRowsByValue(
				DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, 0, areaId);
			if (rows.isEmpty())
			{
				return null;
			}

			final Object[] field = client.getDBTableField(
				rows.get(0), DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, 0);
			return field.length > 0 ? (String) field[0] : null;
		}
		catch (RuntimeException e)
		{
			log.debug("everykill: could not read slayer area", e);
			return null;
		}
	}

	/**
	 * How many were assigned, including any modifier applied at assignment.
	 *
	 * <p>Returns 0 when unknown. The modifier matters: without it a task shows as
	 * "40 of 30 killed" whenever a perk extended it.
	 */
	public int assigned()
	{
		int initial = client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL);
		if (initial <= 0)
		{
			return 0;
		}

		if (client.getVarbitValue(VarbitID.SLAYER_MODIFIER_ID) == MODIFIER_AMOUNT)
		{
			final boolean negative = client.getVarbitValue(VarbitID.SLAYER_MODIFIER_NEGATIVE) == 1;
			final int value = client.getVarbitValue(VarbitID.SLAYER_MODIFIER_VALUE);
			initial += negative ? -value : value;
		}
		return Math.max(initial, 0);
	}

	private int taskRow(int taskId)
	{
		final var rows = client.getDBRowsByValue(
			DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
		return rows.isEmpty() ? -1 : rows.get(0);
	}

	private int bossRow()
	{
		final var rows = client.getDBRowsByValue(
			DBTableID.SlayerTaskSublist.ID,
			DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID,
			0,
			client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID));
		if (rows.isEmpty())
		{
			return -1;
		}

		final Object[] field = client.getDBTableField(
			rows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0);
		return field.length > 0 ? (Integer) field[0] : -1;
	}
}
