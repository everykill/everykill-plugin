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

**Built differently, and verified in play 2026-08-21.** This originally said to check a hardcoded transform-death ID list and look for an item spawning coincident with the despawn. **There is no list.** The evidence is the player's own targeted action: `WIDGET_TARGET_ON_NPC` on that specific NPC, then that NPC leaving within `FINISH_WINDOW_TICKS`. No monster list, no item list, so it works on whatever ships next with the same mechanic.

Getting it right took three separate corrections, all of them the same underlying mistake — **believing the client when it says something is dead**:

1. `ActorDeath` fires at health-ratio-zero, not at death. Six of eight rockslugs graded `EXACT` off that lie, and one got counted twice, the second time off a single point of damage. Emission moved to despawn.
2. `isDead()` on the despawn reads the *same zero health ratio*. An abandoned slug at 0 hp despawned when the player walked away and we recorded a phantom kill. Now a record whose death signal was revoked doesn't get to use `isDead()` either.
3. `FINISH_WINDOW_TICKS` at 3 was sized while `isDead()` was quietly covering for it. Once that crutch was correctly removed, a salt landing before the health bar emptied produced **no kill at all**. Widened to 5.

**Acceptance — met.** All three cases run deliberately on rockslugs:

| case | result |
|---|---|
| Salt after 0 hp | `INFERRED` / `TRANSFORM_FINISH` |
| Salt before 0 hp | `INFERRED` / `TRANSFORM_FINISH` |
| Left unsalted, walked away | **nothing recorded** |

The third is the one that matters. Full detail in `FINDINGS.md`, 2026-08-21.

**Still untested:** desert lizards, zygomites, and gargoyles (75 Slayer). The mechanism is generic so they should follow, but "should" is not "did".

---

## Step 4 — NpcChanged phase handling

**Known exposure before you start — read `spec-kill-detection.md` edge case B first.** Step 3 found and fixed a bug where `ActorDeath`/`isDead()` fire on transform-death NPCs (rockslugs etc.) the moment health ratio hits zero, well before the NPC is actually dead. Two open RuneLite issues ([#15394](https://github.com/runelite/runelite/issues/15394), [#16479](https://github.com/runelite/runelite/issues/16479)) report the identical symptom on Kalphite Queen and Zalcano — NPCs read as dead mid-fight while still alive, during a phase or invulnerability window. **Do not assume `NpcChanged` carrying the record forward is sufficient protection** against a false `ActorDeath` firing mid-transition on these bosses; that has not been tested, and there's specific reason from the two issues above to expect it isn't. Verify this empirically before trusting the naive implementation below on any boss in the phase-transition list.

**Build:** `NpcChanged` carries the combat record forward to the new NPC ID **without emitting a kill**. A genuinely new spawn opens its own record.

**Acceptance:** Verify one multi-phase fight produces exactly one kill. Superior slayer monsters and Nechryael death spawns must still count as separate kills. Accessible multi-phase bosses are limited on this account — defer live testing if needed and mark as untested. Additionally: for any boss where a phase transition can plausibly drop health to zero without the phase actually ending (Kalphite Queen and Zalcano are the two with an open reference report; treat others as unverified rather than assumed safe), confirm whether `ActorDeath` fires mid-transition and, if it does, that it doesn't produce a false kill.

---

## Step 5 — Measured XP, allocated by damage share

**This step was called "Derived XP + reconciliation" and had the roles the wrong way round.** It said to derive each NPC's XP from its damage record and demote `StatChanged` to a checksum. XP *is* paid per point of damage, so the reframe was half right — that part dissolves the merged-tick problem. But derivation cannot be the source of truth, for three reasons verified against the wiki on 2026-08-16 and recorded in `GAME-MECHANICS.md`:

- **Overkill grants no XP.** It is paid on damage *applied*, capped at the target's remaining hitpoints, while hitsplats report damage *rolled*. Every killing blow overstates, and the bias is one-directional — it does not average out.
- **The per-monster bonus cannot be computed.** Manual overrides ignore the published formula: Vorkath computes to +20% against a listed +0%. It has to be read from the P0 reference table, which does not exist yet.
- **Rounding is undocumented.** XP is stored in tenths, and 1.33 per damage is not representable in tenths.

**So the roles swap.** The client's XP updates are the **measurement** — already correct for overkill, per-monster bonuses and rounding, because the game did the arithmetic. Damage is only the **allocator**, answering which monster the experience came from when several were being hit. This needs no multiplier table, carries no overkill error and accumulates no rounding drift, so it is strictly better than the original plan.

**Build:** *(done — `XpAttributor`, merged 2026-08-20)* Pool our damage per tick per `npc_id`. On each combat-skill `StatChanged`, split the delta across that pool by damage share, using largest-remainder so the parts sum exactly to the whole. XP that arrives with no damage on record is **never** forced onto the nearest monster — it accumulates as unallocated and is surfaced on the panel.

**Requires:** nothing. That is the point — the old Step 5 was blocked on Step 0a for the multiplier, and this is not.

**Still to do, and it needs real play:**

- Measure the **XP settle window** — how many ticks after the hitsplat the delta actually lands. `SETTLE_TICKS = 2` is desk-chosen.
- Measure the **residual noise floor**.
- **Report both numbers.** The spec deliberately leaves them unset.

**Acceptance:** Per-monster XP on a hand-counted task is within the measured noise floor of the skill totals the client reports, and **stranded** XP stays near zero during ordinary combat.

**Corrected 2026-08-21.** This previously said "a rising *unallocated* figure means the allocator is wrong". It doesn't. Three of the six combat skills we track are earned outside combat — every teleport (35 Magic), High Alch (65) and Superheat (53) is XP with no monster attached, and it landed in the same bucket. Verified in play: two write-offs of exactly 35 were both Varrock Teleport.

The counter is now split. **Stranded** XP arrived while damage was on record and still could not be placed — that is the signal worth chasing, and the only one the panel shows. **Unallocated** XP arrived with no combat in progress and is expected. See `FINDINGS.md`, 2026-08-21.

**Note on the checksum.** A derived figure is still worth having as a check on the allocator, but it needs the player's attack style, which nothing reads yet. `XpModel` was delivered for this and deleted in the merge: it had no caller, and every rate it encoded is in `GAME-MECHANICS.md`. Rebuild it from the doc when this step needs it rather than resurrecting untested code.

**Slayer XP must never go through this allocator.** It is granted per kill, equal to the monster's hitpoints — not per damage. `CombatSkill` excludes it deliberately.

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

## Test access — two accounts

Everything below is about **capability only**. No account names, no identifiers.
Verified 2026-08-21 from a character export, not assumed.

**Account A (primary dev account, used for all testing so far).** Slayer 49, Magic 66,
no cannon. This is the account every FINDINGS entry to date was measured on.

**Account B (second account, available for testing).** Slayer 81, Magic 79, combat
90/89/85/90/85, 204 quests finished, owns a cannon and a rock hammer.

### Now testable — was deferred, no longer blocked

| Was blocked on | Now |
|---|---|
| **Cannon multikill** — "no cannon owned" | Account B owns cannon parts and ~530 cannonballs |
| **AoE multikill** — Ice Burst needs 70 Magic | Magic 79, Desert Treasure I finished, so Ancient Magicks available |
| **Gargoyle transform deaths** — needs 75 Slayer | Slayer 81, rock hammer in bank |
| **True multi-phase bosses** — "all inaccessible" | **All four routes open.** See below |
| **Raid scaled-HP exclusion** | CoX has no quest gate; ToB unlocked (A Night at the Theatre finished, with Theatre combat achievements completed) |

**The phase-boss blocker is fully cleared, and this was the most important gap in the
project.** BUILD-ORDER previously called `onNpcChanged`'s carry-forward "the case most
likely to misbehave" and noted it had never run once, because a rock crab can't be
damaged while dormant so the branch is structurally unreachable on Account A. Account B
has killed Zulrah, Vorkath and the Grotesque Guardians — all of which transform *while
already being damaged*.

**Recommended order when this is picked up:**

1. **Zulrah.** Changes form and npc_id repeatedly mid-fight, every fight. The purest and
   most repeatable test of record-gated carry-forward that exists.
2. **Grotesque Guardians (Dusk).** Doubly valuable: a phase boss *and* a transform-death
   monster. Core's `NpcUtil` lists `GARGBOSS_DUSK_PHASE4` and `GARGBOSS_DUSK_DEATH` by
   name, so this directly exercises the `isDying()` gate added 2026-08-21 on a boss
   rather than a rockslug.
3. **Vorkath.** Also an XP-attribution case — GAME-MECHANICS records that Vorkath applies
   a +20% experience bonus against a published +0%, so measured XP should disagree with
   the naive expectation in a *specific, predicted* direction. A good falsifiable test.
4. **Kalphite Queen.** No quest gate at all. The original `ActorDeath`-fires-mid-transition
   report in FINDINGS came from KQ.

### Still blocked

- **Nechryael** — needs 84 Slayer; Account B has 81. Three levels.
- **Superior slayer monsters** — needs Bigger and Badder (150 Slayer points). Slayer
  points are not in the character export; ask before planning around it.
- **ToA scaled HP** — "Into the Tombs" is not started on Account B.

### Caveat that matters for data integrity

Account B is an established account with existing kill history. Everykill only counts
what it observes from the moment it is running, so this does **not** contaminate
anything — but it does mean **verified-from-zero kill counts are only possible on
Account A**. Keep that distinction if both accounts ever upload.

---

## Testing protocol

Per the imported conventions: you cannot verify plugin behaviour yourself, and must never automate game input. After each step, offer to launch RuneLite via `./gradlew run`, state exactly what to test, and wait for confirmation before treating the step as complete. A clean JVM start is not a passing test.

Several spec assumptions are explicitly unverified. When testing reveals one is wrong, **say so plainly and propose the correction**.
