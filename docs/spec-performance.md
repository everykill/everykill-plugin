# Spec — Performance measurement

Replaces the earlier "gear tiers and efficiency scoring" design. Covers observed DPS, theoretical DPS, the two efficiency metrics, cohorts, and the recommender.

**Read §2 first if you are implementing anything.** It contains a `kill_event` change that cannot be backfilled.

---

## 1. The core idea

We record every hitsplat, so **we measure performance rather than predicting it.** The combat formulas are only needed to compute what a setup *should* achieve, as a reference point.

That gives two independent measurements, and their ratio decomposes cleanly:

| Metric | Formula | What it isolates |
|---|---|---|
| **Observed DPS** | our damage ÷ fight time | What actually happened |
| **Theoretical DPS** | combat formulas from gear + NPC stats | What should have happened |
| **Damage efficiency** | observed ÷ theoretical | Gear, stats and style choice performing as expected |
| **Uptime efficiency** | observed kills/hr ÷ theoretical kills/hr *at your own observed DPS* | Banking, travel, respawn waits, AFK — gear removed entirely |

This is why the earlier composite "efficiency score" was the wrong shape. It mashed two unrelated things together behind a weighting that would have been our opinion. These two stand alone, each with a single meaning.

---

## 2. Schema change — record this now or lose it permanently

Observed DPS needs **attempt counts, not just damage totals.** The current `kill_event` sums `damage_by_player` but never counts attacks, so observed accuracy is uncomputable and cannot be reconstructed later.

Add to `kill_event`:

| Field | Meaning |
|---|---|
| `our_attacks` | Count of our hitsplats, **including zero-damage ones** |
| `our_hits` | Count of our hitsplats with damage > 0 |
| `our_max_hit` | Largest single hitsplat of ours this fight |
| `weapon_speed_ticks` | Attack speed of the weapon used |

From these:

```
observed_accuracy = our_hits ÷ our_attacks
observed_avg_hit  = damage_by_player ÷ our_hits
observed_dps      = damage_by_player ÷ ((kill_tick − fight_start_tick) × 0.6)
```

**Note the interaction with an existing bug.** `spec-kill-detection.md` records that a kill whose only hitsplats were zero-damage is silently dropped, because `emitKillIfOurs` returns early on `damageByPlayer <= 0`. Zero-damage hitsplats are now *load-bearing data*, so that path must be fixed rather than tolerated.

**Splashes count as attempts.** A magic splash is a zero-damage hitsplat and belongs in `our_attacks` — that's precisely what makes observed accuracy meaningful for Magic.

---

## 3. Theoretical DPS

Standard published formulas, implemented from documentation rather than copied from any implementation. Mathematics is not copyrightable; see `LICENSING.md`.

```
effective_level = floor(base_level × prayer_multiplier) + style_bonus + 8
attack_roll     = effective_level × (equipment_attack_bonus + 64)
defence_roll    = (npc_defence_level + 9) × (npc_defence_bonus + 64)

hit_chance = 1 − (defence_roll + 2) ÷ (2 × (attack_roll + 1))    if attack_roll > defence_roll
           = attack_roll ÷ (2 × (defence_roll + 1))               otherwise

max_hit = floor(0.5 + effective_strength × (strength_bonus + 64) ÷ 640)
dps     = (max_hit ÷ 2) × hit_chance ÷ (weapon_speed_ticks × 0.6)
```

**Inputs:** player levels and equipment bonuses from the loadout; NPC defence level and the defence bonus **matching the style used** from the stat table. Style matters — a monster's stab, slash and crush defences differ, and using the wrong one produces a wrong reference.

**Multipliers to apply:** black mask / slayer helm on task (7/6 melee, 1.15 ranged), salve amulet on undead (7/6, or 1.2 enchanted — does **not** stack with black mask), void (1.1, elite 1.125). Rapid style reduces attack speed by one tick.

### Known gaps — record, don't paper over

Theoretical DPS **does not model**: special attacks, weapon-specific effects (Osmumten's fang re-rolls, Scythe multi-hits, dragon claws), or damage-over-time. A player using specials will legitimately exceed 100% of theoretical.

That is a limitation of the **reference**, not the measurement. Observed DPS is unaffected. Where a fight includes a special attack, flag the damage-efficiency figure rather than suppressing it.

---

## 4. Cohorts — by observed DPS band

The earlier plan bucketed by "relevant equipment bonus," which was crude. Observed DPS is strictly better: it already accounts for the monster's defences, the player's levels, accuracy, style and weapon speed in one number.

- **Band by observed DPS against that specific `npc_id` + region**
- **Style is a hard partition** — melee, ranged and magic cohorts never pool
- Bands are **absolute and computable from a player's own kills**, so a cohort exists immediately rather than waiting for a distribution to form
- `uncontested` confidence and `kill_source = self` only
- Normal worlds only

**Why this fixes the volume problem:** the band needs nobody else. What needs volume is the *median within the band*, which fills in monster by monster.

**And it makes the comparison meaningful.** Cohorting by DPS means the remaining variance in kills/hour *is* uptime. That's a genuine decomposition, not a correlation.

---

## 5. Presentation rules

Every figure carries its sample size. No composites. No grades, letters or stars.

Good:

> **Observed DPS** 6.4 · theoretical 7.1 · **90% damage efficiency**
> **Kills/hour** 142 · theoretical at your DPS 186 · **76% uptime**
> Cohort median kills/hour: 128 *(61 players, 14,200 kills)*

Never: a single "efficiency" number, a ranking of gear, or any phrasing that reads as advice.

**The confound must be stated on the page.** Someone half-watching TV, someone deliberately slow-killing for a pet, someone tick-manipulating — all produce honest-but-misleading uptime figures. The measurement is sound; the causal story people will read into it is not.

---

## 6. The recommender — a view, not advice

**"What are players like me actually killing, and how is it going?"**

A sortable table: monster, players in your DPS band, median kills/hr, median GP/hr, deaths per 100, sample size. Sorted by the user's chosen column. **No recommended label, no ranking, no top pick.** Requirements the player doesn't meet are hidden, not greyed.

**Gating:** minimum 20 players and 5,000 kills on a monster before it appears at all.

**Stated limitation:** the population is self-selected — people who install tracking plugins are more efficiency-minded than average. That belongs on the page in plain language.

---

## 7. What this replaces

| Removed | Why |
|---|---|
| Absolute gear tiers | No single axis exists; naming tiers implicitly ranks gear, which is prescriptive |
| Composite efficiency score | Any weighting is our opinion presented as measurement; composites hide their inputs |
| Relevant-bonus quintile cohorts | Observed DPS is a better single number and needs no distribution to compute |
| "What should I kill" as advice | Prescriptive by construction. Reframed as an observation table |

---

## 8. Build sequencing

### Step 0c — combat formula implementation *(new, parallel track)*

No game access needed. Implement the formulas above as a pure function: player levels + equipment bonuses + NPC stats + style → max hit, hit chance, DPS.

**Requires the NPC stat table (Step 0a)** for defence levels and per-style defence bonuses.

**Validation:** compare output against the OSRS Wiki DPS calculator for a handful of known setups. Any disagreement is a bug in ours until proven otherwise.

### Order within Tier 3

1. **Record the inputs now** — the §2 fields, and active-tick accounting. Impossible to backfill
2. **Ship single-player metrics** — observed DPS, damage efficiency, uptime. These work at N=1, from the first kill, on the most obscure monster in the game
3. **Add cohort medians** monster by monster as each crosses its threshold
4. **Add the recommender table** last, only for monsters already clearing cohort gating

The personal metrics ship early; the comparative ones fill in over time. Nothing waits on volume that doesn't exist.

---

## 9. Open questions

- **Prayer and potion state at kill time** — needed for theoretical DPS. `prayers_active` and `boosts_active` are already on `kill_event`; confirm they capture enough to reconstruct the multipliers
- **Fight time definition** — `kill_tick − fight_start_tick` includes ticks where we weren't attacking (running, eating). Decide whether observed DPS uses elapsed time or attack-attempt time, and be consistent
- **Multi-target fights** — DPS across simultaneous targets needs a rule
- **Which monsters have unusual defence handling** — some ignore certain styles entirely
- **Whether to expose theoretical DPS in-client at all**, or keep it website-only. Leaning website-only: it edges toward combat advice in a live context
