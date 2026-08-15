# Build order

Ship narrow and correct, widen once verified against real play. **Do not skip ahead.** Each step has acceptance criteria — meet them before moving on.

**Logging:** use `log.debug()` for all per-kill and per-event output, per the imported RuneLite conventions — RuneLite runs at INFO level in production, so high-frequency `log.info` would pollute user logs. `log.info` is only for one-time startup/shutdown messages. Run the dev client with `--debug` to see debug output.

No storage, no upload, no UI until Step 8.

---

## Step 1 — Damage records + ActorDeath

**Build:**
- A per-actor combat record, keyed on the NPC's runtime reference
- Opened on the first `HitsplatApplied` where `isMine()`
- Accumulate `damage_by_player` and `damage_total`
- On `ActorDeath` for an NPC we have a record for → log a kill line
- No record or zero damage → ignore silently
- Drop all open records on `GameState` change

Maintain our own collection keyed by actor, driven by spawn/despawn and hitsplat events. Do not scan the scene each tick.

**Log line should include:** npc_id, npc name, damage_by_player, damage_total, fight duration in ticks.

**Acceptance:** Kill 50 chickens in Lumbridge. Logged count matches hand count exactly. Then stand in a busy area (Al Kharid warriors or hill giants) while other players kill things — **zero** of their kills appear in our log.

---

## Step 2 — isDead despawn fallback

**Build:** On `NpcDespawned` where `isDead()` and no `ActorDeath` was seen for that actor → emit a kill.

**Acceptance:** Determine empirically whether `ActorDeath` and the `isDead` despawn **both** fire for the same kill. Log both paths separately and compare. If both fire, dedupe by actor reference. **Report the finding** — the spec treats this as unknown.

---

## Step 3 — Transform deaths

**Build:** On `NpcDespawned` without `isDead()`, check the transform-death ID list. If matched **and** an item spawned coincident with the despawn → emit a kill graded `inferred`.

Use `net.runelite.api.gameval` NPC ID constants for the list — no magic numbers.

Unknown NPCs despawning at low HP after our damage → log to a review queue, **never auto-count**.

**Acceptance:** Test on **rockslugs (20 Slayer)** and **desert lizards (22 Slayer)** — both accessible on this account. Kill some with the finishing item, and deliberately leave one rockslug at low HP *without* salting it. The unsalted one must **not** be counted. Gargoyles need 75 Slayer and cannot be tested yet.

---

## Step 4 — NpcChanged phase handling

**Known exposure before you start — read `spec-kill-detection.md` edge case B first.** Step 3 found and fixed a bug where `ActorDeath`/`isDead()` fire on transform-death NPCs (rockslugs etc.) the moment health ratio hits zero, well before the NPC is actually dead. Two open RuneLite issues ([#15394](https://github.com/runelite/runelite/issues/15394), [#16479](https://github.com/runelite/runelite/issues/16479)) report the identical symptom on Kalphite Queen and Zalcano — NPCs read as dead mid-fight while still alive, during a phase or invulnerability window. **Do not assume `NpcChanged` carrying the record forward is sufficient protection** against a false `ActorDeath` firing mid-transition on these bosses; that has not been tested, and there's specific reason from the two issues above to expect it isn't. Verify this empirically before trusting the naive implementation below on any boss in the phase-transition list.

**Build:** `NpcChanged` carries the combat record forward to the new NPC ID **without emitting a kill**. A genuinely new spawn opens its own record.

**Acceptance:** Verify one multi-phase fight produces exactly one kill. Superior slayer monsters and Nechryael death spawns must still count as separate kills. Accessible multi-phase bosses are limited on this account — defer live testing if needed and mark as untested. Additionally: for any boss where a phase transition can plausibly drop health to zero without the phase actually ending (Kalphite Queen and Zalcano are the two with an open reference report; treat others as unverified rather than assumed safe), confirm whether `ActorDeath` fires mid-transition and, if it does, that it doesn't produce a false kill.

---

## Step 5 — Derived XP + reconciliation

**Build:** Derive each NPC's XP from its own damage record using the rates in `spec-kill-detection.md`. Compare against `StatChanged` deltas and **log divergences**.

**Requires:** the NPC stat table (Step 0a) for the multiplier.

**Acceptance:** Divergences are read and understood before the model is trusted. Measure the XP settle window (how many ticks until the delta lands) and the residual noise floor from real data. **Report both numbers** — the spec deliberately leaves them unset.

---

## Step 6 — Loot: tile coincidence

**Build:** Mirror `LootManager` — record `ItemSpawned` per tile per tick, handle `ItemQuantityChanged` deltas for stackables, iterate the NPC's `size × size` footprint on death.

**Acceptance:** Loot logged matches what actually dropped, verified by hand on a slayer task.

---

## Step 7 — Loot attribution + guards

**Build:**
- Attach loot to kill records from the state machine
- Contested-tile guard → `loot_unknown`, **never** loot-empty
- Corpse counter (both modes — item counting and Prayer XP)
- `scene_has_other_players` flag — **boolean only, never record who they are**
- Parallel path: `ItemContainerChanged`, collection log message, pet message

**Acceptance:** A deliberate multi-kill on one tile produces `unknown`, not empty. Corpse counting matches hand-counted bones. Test the Prayer XP mode with a bonecrusher equipped and confirm it agrees with the item-count mode when both are available.

---

## Step 8 — Confidence grading, then batching and upload

**Build:** Agreement-vector grading for both kill and loot confidence. Only then: client-side batching (2–5 min interval, floor of 60s) and upload.

**Upload requirements** (from the imported conventions):
- `@Inject OkHttpClient` — never build one, never add OkHttp to `build.gradle`
- `@Inject Gson` for serialisation; `.newBuilder()` if customisation is needed
- All calls via `enqueue()` on the OkHttp threadpool, never on the client thread
- `clientThread.invoke()` to call back into `client` from a response callback
- Config toggle **disabled by default**, carrying the exact required warning text, plus a description listing what data is sent
- Any on-disk buffering goes in `.runelite/everykill-plugin/` via `RuneLite.RUNELITE_DIR`
- Cancel scheduled tasks and shut down executors in `shutDown()` without blocking

---

## Parallel track — no game access needed

### Step 0a — NPC stat table pull script

Standalone script hitting the Wiki Bucket API. Pull `infobox_monster`, cache locally, compute XP multipliers, build the `npc_id` bridge via name + combat level.

Start with common slayer monsters; the table fills in incrementally. **Must never block kill recording.**

**Acceptance addition (2026-08-14, see `FINDINGS.md`):** once the formula-based multiplier table covers a meaningful subset of `InstantDamageCalculator`'s ~150-entry `XP_MODIFIERS` table (`github.com/geeckon/instant-damage-calculator`), diff our computed values against theirs for the overlapping monsters. Agreement raises confidence in both; any mismatch is a bug in one implementation and needs chasing down before Step 5 depends on the result. This is a validation step against independently-read prior art, not a data source — see `docs/LICENSING.md` and the corresponding `FINDINGS.md` entry for why it's not copied directly.

### Step 0b — always_drops[] pull

Same API, drops bucket, filter rarity `Always`. Store `item_id`, `quantity`, `is_stackable`, `is_countable`. Explicitly flag monsters with no guaranteed drop.

### Step 0c — combat formula implementation *(new, from `spec-performance.md` §8)*

No game access needed. Implement the formulas in `spec-performance.md` §3 as a pure function: player levels + equipment bonuses + NPC stats + style → max hit, hit chance, DPS.

**Requires Step 0a** for NPC defence levels and per-style defence bonuses.

**Implement from the published formulas, not from any existing implementation.** Mathematics isn't copyrightable, but do not copy code — see `docs/LICENSING.md`.

**Validation:** compare output against the OSRS Wiki DPS calculator on a handful of known setups. Any disagreement is a bug in ours until proven otherwise.

---

## Deferred — cannot test on this account yet

- Cannon multikill (no cannon owned)
- AoE multikill (Ice Burst needs 70 Magic; currently 66)
- Gargoyle transform deaths (needs 75 Slayer; currently 49)
- Raid scaled-HP exclusion (no raid access)
- Superior slayer monsters — Step 4's separate-spawn behaviour (needs Bigger and Badder, 150 Slayer points; currently 18)
- Nechryael death spawns — same Step 4 behaviour, alternate route (needs 84 Slayer; currently 49)
- **True multi-phase bosses (Step 4's core case) — Kalphite Queen, Zulrah, Vorkath, Alchemical Hydra all inaccessible on this account.** This is the case most likely to misbehave: the KQ and Zalcano `ActorDeath`-fires-mid-transition reports already in `FINDINGS.md`/`spec-kill-detection.md` edge case B are exactly what `onNpcChanged`'s false-ActorDeath handling was built to cover, and none of it has been exercised against a real phase boss. Do not treat Step 4 as verified end-to-end until one of these (or an equivalent) is reachable — the rock/sand crab and Scurrius substitutes cover the *mechanism* (id-change carry-forward, separate-spawn isolation) but not this specific, already-flagged failure mode. **Confirmed 2026-08-14, not just assumed:** the crab wake-and-kill test traced 4 kills end-to-end and found `NpcChanged` always completes before the first hitsplat — a dormant rock can't be hit while dormant — so the record-gated carry-forward branch in `onNpcChanged` never ran once, and structurally cannot be exercised by this substitute at all. Only a monster that transforms *while already being damaged* (a real phase boss) can test that specific code. See `FINDINGS.md`.

Build the handling per spec, mark as untested, revisit when accessible.

---

## Testing protocol

Per the imported conventions: you cannot verify plugin behaviour yourself, and must never automate game input. After each step, offer to launch RuneLite via `./gradlew run`, state exactly what to test, and wait for confirmation before treating the step as complete. A clean JVM start is not a passing test.

Several spec assumptions are explicitly unverified. When testing reveals one is wrong, **say so plainly and propose the correction**.
