# Spec — Data model & reference data

Events are immutable and granular; everything else is derived. Get this layer right and any feature can be added later without a migration.

## Design principles

- **Store what changed, not what's constant.** Stable values live on session or loadout and are referenced by ID
- **Never aggregate on the client.** Per-kill granularity is required for personal bests, drop maths and tick efficiency
- **Raw `npc_id` forever.** Display grouping is read-time and must stay reversible
- **Server assigns anything trust-sensitive** — patch tags, receipt timestamps, ranking order
- **Aggregates are rebuildable** from events alone

---

## kill_event

> **This table is the server-side target design, not what the client emits.**
> It is much wider than the real record — slayer fields, prayers, boosts,
> loadout hashes, coordinates and `weapon_speed_ticks` (dropped, FINDINGS
> 2026-08-20) do not exist on it. For what the client actually sends today,
> build against **`docs/spec-kill-contract.md`**, which is checked field for
> field against `KillRecord`.

Immutable, ~300 bytes.

| Group | Fields |
|---|---|
| Identity | `event_id` (client UUID — dedups batch retries), `player_id`, `session_id`, `seq` |
| Time | `killed_at`, `server_received_at`, `fight_start_tick`, `kill_tick` |
| Mob | `npc_id`, `npc_name`, `npc_combat_level`, `is_superior`, `region_id`, coords |
| Slayer | `on_task`, `slayer_master_id`, `task_id`, `task_size` |
| Combat | `damage_by_player`, `damage_total`, `xp_gained` (skill→delta map), `attribution_confidence`, `kill_source`, `non_xp_damage_residual` |
| Performance *(added 2026-08-14, see `spec-performance.md` §2 — cannot be backfilled)* | `our_attacks` (count of our hitsplats, including zero-damage), `our_hits` (count with damage > 0), `our_max_hit`, `weapon_speed_ticks` |
| Style | `style_category`, `attack_style`, `weapon_id`, `spell_id` |
| Gear | `loadout_hash`, `prayers_active` (bitmask), `boosts_active` |
| Risk | `damage_taken`, `food_eaten`, `potions_used`, `player_died` |
| Loot | `drops[]`, `loot_confidence`, `agreement_vector` |
| Meta | `plugin_version`, `client_version`, `patch_tag` *(server-assigned)* |

`kill_source` values: `self` / `cannon` / `thrall` / `other_player`.

Kills are sent as **individual events inside batches**, never pre-aggregated. ~300 KB for a heavy 1,000-kill session — acceptable.

## session

Context container. Referenced by every kill event.

- **Time** — `started_at`, `ended_at`, `last_event_at`, `end_reason` (logout / idle_timeout / crash / manual), `tz_offset` for local-time streaks
- **World** — `world_id`, `world_flags[]` **as an array, never a single enum** (a world can be members + PvP + high-risk at once, and Jagex keeps adding types). Category derived server-side
- **Account** — `account_type` stored **per session, not per player** (people de-iron, HCIMs die, GIM converts), plus `player_name_at_time`
- **Stats** — `stats_start` snapshot, with separate `level_up_event` rows during the session. Gives kills-per-level without stamping 23 skill values onto every kill
- **Integrity** — `seq_max`, `events_received` (gap detection), `is_hiscore_eligible`, versions, `upload_interval_setting`, `scene_has_other_players`
- **Derived on close** — `active_ticks` vs `total_ticks` (idle-adjusted xp/hr), `total_kills`, `total_xp`, `deaths`

**Canonical session boundary for hiscore purposes must be a fixed value** (10 min idle or logout). Personal display can be configurable; comparability cannot.

## loadout

**Content-addressed** — primary key is a hash of the 11 equipment slot IDs, so identical setups collapse to one row across the entire userbase. Thousands of players in rune armour share a single record.

- Slots: head, cape, neck, ammo, weapon, body, shield, legs, hands, feet, ring
- `first_seen_at`, `use_count`
- Stat bonuses and `gear_tier` **derived server-side** — re-bucketing becomes a rerun, not a plugin update
- New row only on actual gear change; `loadout_change_event` preserves the timeline

## inventory

**No per-kill snapshots.** "Snapshot" here means a data read of item IDs and quantities — nothing visual.

- One snapshot at **task or session start**
- Running counters: `food_eaten`, `potion_doses_used`, `ammo_consumed`, `runes_consumed`
- The **set of distinct consumables used**

Produces supplies-cost/hr and "what people actually bring" per mob, at a fraction of the storage.

## Supporting tables

- `drop_event` — `item_id`, `quantity`, `ge_value_at_time`; normalised server-side
- `mob` — auto-created on first sighting. **Page only created once a death with player damage is recorded**, which filters out pets, shopkeepers and event NPCs
- `mob_group` — optional read-time layer mapping many `npc_id`s to one display entity. Deferrable because raw IDs are stored
- `player_mob_stat` — rolling aggregate for fast hiscore reads
- `rollup_*` — precomputed hour/day/week/month buckets. Global counters must never touch raw events

---

## NPC stat table

**Hard prerequisite.** Blocks derived XP, the anti-cheat ceiling, Slayer reconciliation and the corpse counter.

### Source: the OSRS Wiki Bucket API

The wiki exposes structured data at `api.php?action=bucket`, explicitly so external users can query it without scraping. The older `action=ask` SMW endpoint is **hard-deprecated and being removed — do not use it**.

```
https://oldschool.runescape.wiki/api.php?action=bucket
  &query=bucket('infobox_monster')
    .select('npc_id','hitpoints','defence_level', ...)
    .run()
```

All bucket and field names lowercase, spaces as underscores. `Bucket:Infobox monster` on the wiki lists every field. Rate-limit politely and cache — this is a read-once-per-patch job, not a live dependency.

**Licensing:** wiki content is CC BY-NC-SA 3.0 (non-commercial). Fine while there are no ads. Long-term fix is deriving our own data from kill logs.

### Fields to pull

- **Identity** — name, version, combat level, npc IDs, size
- **Levels** — hitpoints, attack, strength, defence, magic, ranged
- **Bonuses** — attack bonuses, defence bonuses (stab/slash/crush/magic/ranged), strength bonus → feeds the XP multiplier formula
- **Slayer** — level requirement, category, assigning masters
- **Attributes** — demon, dragon, undead etc.

### Fields we compute or maintain

- `xp_bonus_multiplier` — computed from the formula, with a **manual override column**
- `always_drops[]` — see corpse counter below
- `has_scaled_hp` — raids and scaled instances, excluded from all HP maths
- `is_transform_death`, `drop_location_override`, `exclude_from_tracking`
- Provenance — source, fetch date, wiki revision, patch tag

### The hard part — joining wiki data to npc_ids

The wiki keys on page name + version; we key on `npc_id`. Bridge via `NPCComposition` at runtime, which gives name, combat level and size per ID. **Match on name + combat level** — exactly how RuneLite's `NpcManager` resolves health, so the approach is proven.

Ambiguous matches go to a **review queue**, never guessed.

### Design rule

**The stat table must never block kill recording.** A monster missing from it still gets kills logged in full; derived XP and HP validation are skipped and backfilled later. Otherwise new content breaks tracking on release day, destroying the auto-discovery advantage and the race boards with it.

---

## Corpse counter

Uses guaranteed drops to prove a loot pile contains exactly our kills.

| Outcome | Meaning |
|---|---|
| Count **equals** our kills | Pile is entirely ours, fully attributable |
| Count **exceeds** our kills | A foreign kill contributed → `unknown` |
| Count **below** our kills | We over-recorded, or drops were destroyed → investigate, don't assume |

It validates in both directions, making it a check on the kill detector as well as on loot.

### Bonecrusher caveat — the signal moves, it doesn't vanish

With a bonecrusher or ash sanctifier active, the guaranteed drop **never reaches the ground**. Naive counting reports zero corpses and marks every pile unknown.

But those items convert to Prayer experience, **one conversion per corpse**. So **Prayer XP becomes the corpse counter**. Detect the device from the equipment/inventory snapshot, then switch counting mode.

### Three counting modes

| Condition | Method |
|---|---|
| Non-stackable guaranteed drop | Count `ItemSpawned` across the NPC footprint ÷ drop quantity |
| Bonecrusher / ash sanctifier active | Prayer XP delta ÷ per-bone prayer value |
| Neither | Counter unavailable — degrade confidence, rely on the other three signals |

### Stackables can't be counted

A stackable guaranteed drop merges into any existing pile and surfaces as a quantity delta. Three kills dropping 50 coins each is indistinguishable from one dropping 150. Only non-stackable guaranteed drops qualify — flag per item with `is_countable`.

### Building `always_drops[]`

Same Bucket API, drops bucket. Filter rows with rarity **Always**.

- Store `item_id`, `quantity`, `is_stackable`, `is_countable`
- **Record quantity carefully** — a monster that always drops 2 bones means 3 corpses produce 6 items
- **Explicitly flag monsters with no guaranteed drop.** "No guaranteed drop" and "we haven't populated this yet" are different states and must not both be an empty list
- **Concrete example, verified 2026-08-14:** Rockslug has no 100%/always drop at all — no bones, no ashes, nothing guaranteed (checked against the wiki drop table directly). The corpse counter is simply unavailable for this monster, not a missing-data case. Worth keeping as the reference example when implementing the flag, since it's a transform-death NPC (`spec-kill-detection.md` edge case A) as well as a no-guaranteed-drop one — two independent reasons its kill/loot confidence should never reach `uncontested`.

### Wrinkles to test

Guaranteed drops varying by variant or location; bonecrusher charge state (uncharged drops normally); Prayer XP from other sources in the same window (manual burial, altar); whether Prayer XP arrives on the kill tick or the next one.

---

## Batch attribution

Recovering contested piles that would otherwise be discarded.

### Why it's valid

A drop rate estimate is items ÷ kills. That estimator is **unbiased regardless of which specific kill produced which item**. Per-kill attribution is never required for rate maths — only for identifying which kill was the lucky one.

A pile from N same-mob kills is therefore statistically identical to N separately-attributed kills. For rate purposes nothing is lost.

### What is lost

KC-at-drop precision, bounded to batch size. At a 1/128 drop around 500 KC, ±3 is under one percent — immaterial for dry-streak display or luck percentiles.

### Eligibility — all required

- Every kill shares `npc_id` **and** region
- Corpse count equals recorded kill count
- No foreign hitsplats on any batch member
- Batch size ≤ **5**

Refuse batching for: mixed `npc_id`s, superior variants pooled with normals, on-task/off-task mixes.

### Integrity rule

On competitive boards (lowest-KC rare, spooned rankings), a batched drop is recorded at the **highest** possible KC in the batch. Nobody gains rank from uncertainty. Personal profiles may show the range.

---

## Four-signal agreement model

The game never says "this corpse produced this item". Four independent signals that must agree is the closest defensible substitute.

| Signal | What it proves |
|---|---|
| Tile coincidence | Items appeared where this monster died |
| Kill record | We dealt the damage and it died |
| Corpse accounting | The pile contains exactly our corpses, no more |
| Chat confirmation | The game itself named the item |

Their independence is the point — tile logic can be fooled by a coincident foreign drop, and corpse counting catches exactly that case.

**Confidence becomes an agreement count**, not a hand-assigned label:

- All four agree → `confirmed`, drop-rate eligible
- Two or three agree, none contradict → `probable`, totals only
- Any contradiction → `unknown`, excluded

Store the **agreement vector** so grading is self-documenting years later.

---

## Anti-cheat primitives (server-side, for later)

- **HP-XP ceiling** — Hitpoints XP is a fixed function of lifetime damage dealt (~1.33 per damage). Sum claimed kills × mob HP; if it exceeds implied lifetime damage, the claim is false. Works retroactively from official hiscores. **Needs a tolerance band** because thralls, poison and recoil deal XP-free damage — size it from measured residuals, don't guess
- **Slayer XP** equals monster HP × its XP bonus, on task only — a second independent read
- **Combat Achievements** requiring N kills give a hard lower bound on prior KC
- **Collection log completeness** works as an exposure estimator to downweight suspicious claims
- **Verification tiers** — `official` (tracked continuously from the account's first-ever kill of that mob) vs `unofficial` (started mid-grind). Veteran accounts stay eligible for official status on any mob they've never touched. Race boards on new content are inherently official
