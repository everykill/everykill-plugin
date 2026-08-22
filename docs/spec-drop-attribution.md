# Spec — Drop attribution

Deciding which kill produced which item. Everything user-facing depends on this: expected-vs-actual, dry streaks, hall of fame, live drop feed.

## How RuneLite already does it

Reference implementation: `net.runelite.client.game.LootManager`. Read it before implementing.

### Core mechanic — tile coincidence

- Every `ItemSpawned` is recorded into a multimap keyed by packed scene coords (`x << 8 | y`)
- On NPC death, the drop tile is computed and every item on that tile is claimed
- **The item map is cleared every game tick.** Loot must appear in the same tick the death is processed or it's gone

The system is tick-synchronous, not window-based. That's why delayed loot needs hand-written exceptions.

### Details worth copying exactly

- **NPC size** — iterates the full `size × size` footprint; large monsters drop across multiple tiles
- **Stackables** — `ItemQuantityChanged` handled separately, counting only the **difference**, so a drop merging into an existing pile isn't over-claimed
- **`killPoints` set** — prevents one tile's loot being claimed twice in a tick
- **Death animations** — Gauntlet crystalline/corrupted NPCs and cave kraken drop on the death *animation*, matched via an explicit animation map
- **PvP** — `PlayerDespawned` with health ratio 0

### Hardcoded drop-location overrides

| NPC | Where loot actually lands |
|---|---|
| Kraken / cave kraken | at the **player's** location, not the monster's |
| Zulrah | found by locating the tile containing Zulrah's scales |
| Vorkath | offset from centre, computed from player position |
| The Nightmare (id 9433) | delayed — polled up to 15 ticks on an adjacent tile |

No general rule. Expect to add to this list rather than solve it.

## Where LootManager isn't enough for us

It answers "what loot appeared". We need "which kill produced it, and how sure are we".

1. **No ownership verification.** Tile coincidence doesn't prove loot is ours. Partly mitigated because another player's drop only becomes visible after its ownership timer expires, firing `ItemSpawned` at an unrelated moment. "Partly" isn't good enough — **our kill record with our damage must exist first**.
2. **Multi-kills on one tile lose loot.** The `killPoints` guard means the second kill silently records zero drops. These must be marked `loot_unknown`, **not** loot-empty.
3. **Drops that never touch the ground** — pets, some untradeables, interface-delivered rewards. Missed entirely.

## Signals to add that RuneLite doesn't use

Independent of tile logic, which makes them ideal verification:

- **Collection log chat message** — unambiguous, names the item, fires on first-time uniques
- **Pet chat message** — the "funny feeling" line; catches drops with no ground item at all
- **Rare drop broadcasts**
- **`ItemContainerChanged`** — inventory deltas as a cross-check

## Our algorithm

1. Record item spawns per tile per tick, as LootManager does, including quantity deltas for stackables
2. On a confirmed kill (death **and** our damage), compute the drop tile — NPC location by default, or the per-monster override
3. Claim items across the NPC's full footprint
4. If another of our kills already claimed that tile this tick → mark `loot_unknown`, not empty
5. Run the parallel path (inventory deltas, collection log, pet messages) and merge anything not seen on the ground
6. Grade and emit as part of the kill event's `drops` array

Loot only ever attaches to a kill that already passed kill detection. No orphan loot events, no loot-first inference.

## Loot confidence grading

Separate from kill confidence.

| Grade | Conditions | Used for |
|---|---|---|
| `confirmed` | Kill graded `uncontested` · single kill claiming that tile this tick · no foreign hitsplats · known drop tile | Drop-rate eligible |
| `probable` | Kill graded `inferred` · parallel-path-only loot · delayed-loot monster | Totals only |
| `unknown` | Tile contested · multi-kill on one tile · scene changed before loot resolved | **Excluded from denominators** |

### The denominator rule

A kill graded `unknown` is excluded from drop-rate maths **entirely** — never counted as a dry kill. Counting unknowns as dry is the single easiest way to make every published rate wrong, and it would be invisible until someone checked our numbers against the wiki's.

## Reducing `unknown` — recovery strategies

Ordered by value. See `spec-data-model.md` for the batch maths and corpse counter.

1. **Kill-batch attribution** — a pile from N same-mob kills stays fully rate-eligible. Biggest win.
2. **Corpse counting** — guaranteed drops reveal how many corpses contributed
3. **Item→source disambiguation** — when two different mobs die on one tile, an item only one can drop is unambiguous
4. **Footprint separation** — large NPCs occupy multiple tiles; non-overlapping tiles are unambiguous
5. **`scene_has_other_players` flag** — when false, all loot in the window is necessarily ours. Clean-room condition, very common for ironmen
6. **Wider tick window with `loot_delay_ticks`** — confidence degrades with delay instead of the drop being lost. Measure how much is actually lost at one tick before widening

## Edge cases

### Context changes the drop table itself

- **Konar** — location-locked drops; same monster, different table by area
- **Superior slayer monsters** — separate table, separate NPC
- **Catacombs** — additional totem/ash drops layered on
- **Wilderness variants** — different tables for the same nominal monster
- **On-task vs off-task** — affects some tertiary drops

**Drop rates must be computed per `npc_id` + region, never per mob name.** Aggregating across contexts produces rates matching no actual table.

### Timing and scene

Delayed loot beyond the tick window, teleport/hop before loot resolves, player death mid-fight, instanced content unloading on completion. All produce `unknown`, never empty.

### Quantity and value

Stackables merging (delta only), noted items, quantity ranges (a drop of 1 and a drop of 200 are the same table entry — don't treat as different items), `ge_value_at_time` from the Wiki's public real-time prices API.

## Unverified assumptions — confirm empirically

- Full delayed-loot monster list beyond The Nightmare
- Exact collection log chat message format (changes with game updates)
- Whether another player's expired-ownership drop can realistically collide with a same-tick death
- Whether loot attribution holds inside instances
- How much loot is actually lost to the one-tick window in normal play
