/*
 * Copyright (c) 2026, Everykill contributors
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.everykill.xp;

import java.util.EnumMap;
import java.util.Map;

/**
 * Expected combat XP for a quantity of damage — <b>a checksum, never a published
 * figure.</b> It exists to catch bugs in our own attribution.
 *
 * <h2>Known bias — read before trusting an output</h2>
 *
 * <ul>
 *   <li><b>Overkill.</b> Fed rolled damage, so it overstates on every killing blow,
 *       one-directionally.</li>
 *   <li><b>Per-monster bonus.</b> A parameter here, never computed — manual overrides
 *       ignore the published formula. 1.0 means "unknown", not "none".</li>
 *   <li><b>Rounding.</b> XP is stored in tenths and 1.33/damage is not representable;
 *       small drift is expected, not a bug.</li>
 *   <li><b>No-XP damage.</b> Poison, cannons, dummies and Barbarian Assault all have
 *       their own rules. None are modelled here.</li>
 * </ul>
 *
 * So divergence is normal and only worth reporting when large — see
 * {@link #DIVERGENCE_TOLERANCE}. All facts verified against the wiki 2026-08-16.
 */
public class XpModel
{
	/** Divergence worth flagging. Desk-chosen; P1 measurement sets the real value. */
	public static final double DIVERGENCE_TOLERANCE = 0.10;

	/** Hitpoints experience per point of damage, for every style. */
	private static final double HP_PER_DAMAGE = 1.33;

	/**
	 * How a style pays out per point of damage. Defensive casting genuinely totals
	 * 2.33 rather than 4 — the asymmetry is in the source, not a typo.
	 */
	public enum Style
	{
		MELEE_ACCURATE,
		MELEE_AGGRESSIVE,
		MELEE_DEFENSIVE,
		MELEE_CONTROLLED,
		RANGED_ACCURATE,
		RANGED_RAPID,
		RANGED_LONGRANGE,
		MAGIC_STANDARD,
		MAGIC_DEFENSIVE,
		/** Cannon damage: 2 Ranged per damage, and no Hitpoints XP at all. */
		CANNON,
		/** Style could not be determined. Produces no expectation rather than a guess. */
		UNKNOWN
	}

	/**
	 * @param damage     damage dealt, as rolled
	 * @param style      the attack style in use
	 * @param xpBonus    per-monster multiplier; 1.0 means "unknown", not "none"
	 * @param baseCastXp flat spell XP for magic, which is paid even on a splash; 0 otherwise
	 */
	public static Map<CombatSkill, Double> expected(int damage, Style style, double xpBonus, double baseCastXp)
	{
		final Map<CombatSkill, Double> out = new EnumMap<>(CombatSkill.class);

		if (style == Style.UNKNOWN || damage < 0)
		{
			return out;
		}

		final double d = damage;

		switch (style)
		{
			case MELEE_ACCURATE:
				put(out, CombatSkill.ATTACK, 4 * d);
				break;
			case MELEE_AGGRESSIVE:
				put(out, CombatSkill.STRENGTH, 4 * d);
				break;
			case MELEE_DEFENSIVE:
				put(out, CombatSkill.DEFENCE, 4 * d);
				break;
			case MELEE_CONTROLLED:
				put(out, CombatSkill.ATTACK, 1.33 * d);
				put(out, CombatSkill.STRENGTH, 1.33 * d);
				put(out, CombatSkill.DEFENCE, 1.33 * d);
				break;
			case RANGED_ACCURATE:
			case RANGED_RAPID:
				put(out, CombatSkill.RANGED, 4 * d);
				break;
			case RANGED_LONGRANGE:
				put(out, CombatSkill.RANGED, 2 * d);
				put(out, CombatSkill.DEFENCE, 2 * d);
				break;
			case MAGIC_STANDARD:
				put(out, CombatSkill.MAGIC, 2 * d + baseCastXp);
				break;
			case MAGIC_DEFENSIVE:
				put(out, CombatSkill.MAGIC, 1.33 * d + baseCastXp);
				put(out, CombatSkill.DEFENCE, 1.0 * d);
				break;
			case CANNON:
				// Half rate, and explicitly no Hitpoints XP. Return early so the
				// Hitpoints line below is not added.
				put(out, CombatSkill.RANGED, 2 * d);
				applyBonus(out, xpBonus);
				return out;
			default:
				return out;
		}

		put(out, CombatSkill.HITPOINTS, HP_PER_DAMAGE * d);
		applyBonus(out, xpBonus);
		return out;
	}

	private static void applyBonus(Map<CombatSkill, Double> map, double xpBonus)
	{
		if (xpBonus == 1.0)
		{
			return;
		}
		map.replaceAll((k, v) -> v * xpBonus);
	}

	private static void put(Map<CombatSkill, Double> map, CombatSkill skill, double value)
	{
		map.merge(skill, value, Double::sum);
	}

	/**
	 * Measured against expected, or {@code NaN} with nothing to compare. Since the
	 * model overstates on overkill, negative is the normal resting state; a large
	 * positive suggests an unmodelled bonus or XP landing on the wrong monster.
	 */
	public static double divergence(double measured, double expectedTotal)
	{
		if (expectedTotal <= 0.0)
		{
			return Double.NaN;
		}
		return (measured - expectedTotal) / expectedTotal;
	}
}
