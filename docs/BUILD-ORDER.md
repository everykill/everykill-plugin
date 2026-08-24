# Build order

Ship narrow and correct, widen once verified against real play. **Do not skip ahead.** Each step has acceptance criteria — meet them before moving on.

**Logging:** use `log.debug()` for all per-kill and per-event output, per the imported RuneLite conventions — RuneLite runs at INFO level in production, so high-frequency `log.info` would pollute user logs. `log.info` is only for one-time startup/shutdown messages. Run the dev client with `--debug` to see debug output.

No storage, no upload, no UI until Step 8.

---

## Status at a glance

Updated 2026-08-22. **"Built" and "verified" are different things and this project treats
them as different things.** Code that compiles and passes unit tests has not been tested —
every bug found so far was found in a client, not in a test run.

| | Step | Status | Proof |
|---|---|---|---|
| 1 | Damage records + ActorDeath | ✅ **verified** | foreign-kill exclusion re-confirmed 2026-08-21 |
| 2 | isDead despawn fallback | ✅ **verified** | both paths measured, gap is 6 ticks |
| 3 | Transform deaths | ✅ **verified** | all three cases, twice — see the correction in-step |
| 4 | NpcChanged phase handling | 🟡 **partial** | branch executed 9× on Zulrah; no fight completed |
| 5 | Measured XP by damage share | 🟡 **partial** | allocator verified; two numbers still unmeasured |
| 6 | Loot: tile coincidence | ⬜ not started | — |
| 7 | Loot attribution + guards | ⬜ not started | — |
| 8 | Grading, batching, upload | ⬜ not started | — |
| 0a | NPC stat table pull | 🟡 **partial** | 4,124 ids pulled and validated; script committed |
| 0b | always_drops[] pull | 🟡 **partial** | 4,339 rows, 2,798 ids; nothing consumes it yet |
| 0c | Combat formula implementation | ⬜ not started | — |

**The one that matters: Step 4 has never run.** Not "lightly tested" — the
carry-forward branch has executed zero times, and BUILD-ORDER has flagged it as the code
most likely to misbehave since before it was written. It was blocked on account access;
as of 2026-08-21 it is not. See *Test access* below.

**Legend.** ✅ verified in a live client, with a `FINDINGS.md` entry naming what was
measured · 🟡 partially met, outstanding work named in-step · ⚠️ code exists, has never
run · ⬜ not started.

**Rule for this table:** a step moves to ✅ only when its acceptance criteria were met
*in a client* and an entry in `FINDINGS.md` records what was measured. Compiling is not
evidence. Passing unit tests is not evidence. If you cannot point at the entry, it is
not verified.

---

## Step 1 — Damage records + ActorDeath

**✅ Status: verified.** Foreign-kill exclusion re-confirmed on current code 2026-08-21 — another player killed cave crawlers in the same scene and produced zero kill lines. Note the original 50-chicken count test predates the `com.everykill` rewrite; the exclusion half is what has been re-proven since.

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

**✅ Status: verified — and the answer the spec asked for is: both fire.** `ActorDeath` fires at health-ratio-zero, then the despawn follows. Measured gap is **6 ticks**, five times out of five on lesser demons. Dedupe is by actor reference, not npc index. See FINDINGS 2026-08-21, "SOLVED: the INFERRED grade was DEATH_CONFIRM_TICKS missing by one tick".

**Build:** On `NpcDespawned` where `isDead()` and no `ActorDeath` was seen for that actor → emit a kill.

**Acceptance:** Determine empirically whether `ActorDeath` and the `isDead` despawn **both** fire for the same kill. Log both paths separately and compare. If both fire, dedupe by actor reference. **Report the finding** — the spec treats this as unknown.

---

## Step 3 — Transform deaths

**Built differently, and verified in play 2026-08-21.** This originally said to check a hardcoded transform-death ID list and look for an item spawning coincident with the despawn. **There is no list.** The evidence is the player's own targeted action: `WIDGET_TARGET_ON_NPC` on that specific NPC, then that NPC leaving within `FINISH_WINDOW_TICKS`. No monster list, no item list, so it works on whatever ships next with the same mechanic.

Getting it right took three separate corrections, all of them the same underlying mistake — **believing the client when it says something is dead**:

1. `ActorDeath` fires at health-ratio-zero, not at death. Six of eight rockslugs graded `EXACT` off that lie, and one got counted twice, the second time off a single point of damage. Emission moved to despawn.
2. `isDead()` on the despawn reads the *same zero health ratio*. An abandoned slug at 0 hp despawned when the player walked away and we recorded a phantom kill. Now a record whose death signal was revoked doesn't get to use `isDead()` either.
3. `FINISH_WINDOW_TICKS` at 3 was sized while `isDead()` was quietly covering for it. Once that crutch was correctly removed, a salt landing before the health bar emptied produced **no kill at all**. Widened to 5.

**Acceptance — first run.** All three cases run deliberately on rockslugs. **Read the status block below this table before trusting it** — one of these three later failed on different timing:

| case | result |
|---|---|
| Salt after 0 hp | `INFERRED` / `TRANSFORM_FINISH` |
| Salt before 0 hp | `INFERRED` / `TRANSFORM_FINISH` |
| Left unsalted, walked away | **nothing recorded** |

The third is the one that matters. Full detail in `FINDINGS.md`, 2026-08-21.

### ✅ Status: verified — but it took a second round, and here is why

**That table was passed on one timing and failed on another.** Re-tested 2026-08-21
(later the same day) and the abandoned case recorded a **false kill at the top grade** —
a rockslug graded `UNCONTESTED` while standing in front of the player at 0 hp taking
hits.

Both runs are real. The difference is the despawn gap:

| run | item-use → despawn | revoked in time? | outcome |
|---|---|---|---|
| first | `gap=60` (~36s, walked away) | yes | correctly discarded ✅ |
| second | ~6–10 ticks (despawned immediately) | **no** | **false kill** ❌ |

Revocation runs from `tick()`. A despawn arriving before the sweep beats it. The
original test only ever exercised the slow walk-away and the fast despawn was never
tried, so the criterion read as met while a whole timing branch sat untested.

**Fixed properly, not with another window.** The death signal is now gated through
core's `NpcUtil.isDying()`, which returns `false` for monsters that sit at 0 hp — no
timer can separate those from a real death, because a real death and an abandoned slug
both despawn about six ticks after the signal. Retested in a client: salted →
`TRANSFORM_FINISH`, abandoned → nothing recorded, `deathAt=-1`. Three regression tests,
each verified to fail without the gate.

**Lesson worth keeping: "the acceptance case passed" is not the same as "every path
through the acceptance case passed."** Vary the timing, not just the scenario.

**Still untested:** desert lizards, zygomites, and gargoyles. Gargoyles are **now
reachable** (Slayer 81, rock hammer owned) — the mechanism is generic so they should
follow, but "should" is not "did".

---

## Step 4 — NpcChanged phase handling

**🟡 Status: partial — the branch has now executed, but no fight has been completed.** Nine carry-forward executions on Zulrah 2026-08-22, first time in the project's history. See FINDINGS 2026-08-22. **What is still missing is the acceptance criterion itself:** the fight ended in a player death, so it produced *zero* kills. Nine phases producing zero kills is not the same claim as a completed fight producing exactly one. **Do not mark this ✅ until a multi-phase boss is killed and the count is checked.**

What the Zulrah run did establish:
- Zulrah's dive is an `NpcChanged`, **not** a despawn/respawn — `2042 -> 2043 -> 2044` cycling, zero despawn discards mid-fight. This was an open question and is now measured.
- Damage carried forward unbroken across all nine transitions, 66 → 338.
- `deathAt=-1` on every transition — no false `ActorDeath`. **Zulrah-specific**, because its forms never zero their health ratio. Says nothing about KQ or Zalcano, which do.
- Player death produced the correct terminal behaviour: record discarded, no phantom kill.
- Snakelings (2045/2046) kept their own records and never polluted Zulrah's kill count. **But XP leaked** — see the second FINDINGS entry for 2026-08-22, unfixed.

**Now unblocked (2026-08-21).** A second account has Zulrah, Vorkath and the Grotesque Guardians. This is the highest-value outstanding test in the project. See *Test access* below for the recommended order.

**Temporary diagnostic in place (2026-08-22).** `KillStateMachine.composition()` logs both arms at debug: `temp: NpcChanged carry-forward` when a record exists (the branch that has never run) and `temp: NpcChanged, no record open` when it does not. Logged unconditionally and *before* the mutation, so the old npc_id is still readable — a kill count of 1 does not say which path produced it, and silence would not distinguish "never fired" from "fired and did nothing". **Removal trigger: delete both lines once a FINDINGS entry records the carry-forward observed in a client.** Run the dev client with `--debug`.

**Known exposure before you start — read `spec-kill-detection.md` edge case B first.** Step 3 found and fixed a bug where `ActorDeath`/`isDead()` fire on transform-death NPCs (rockslugs etc.) the moment health ratio hits zero, well before the NPC is actually dead. Two open RuneLite issues ([#15394](https://github.com/runelite/runelite/issues/15394), [#16479](https://github.com/runelite/runelite/issues/16479)) report the identical symptom on Kalphite Queen and Zalcano — NPCs read as dead mid-fight while still alive, during a phase or invulnerability window. **Do not assume `NpcChanged` carrying the record forward is sufficient protection** against a false `ActorDeath` firing mid-transition on these bosses; that has not been tested, and there's specific reason from the two issues above to expect it isn't. Verify this empirically before trusting the naive implementation below on any boss in the phase-transition list.

**Build:** `NpcChanged` carries the combat record forward to the new NPC ID **without emitting a kill**. A genuinely new spawn opens its own record.

**Acceptance:** Verify one multi-phase fight produces exactly one kill. Superior slayer monsters and Nechryael death spawns must still count as separate kills. Accessible multi-phase bosses are limited on this account — defer live testing if needed and mark as untested. Additionally: for any boss where a phase transition can plausibly drop health to zero without the phase actually ending (Kalphite Queen and Zalcano are the two with an open reference report; treat others as unverified rather than assumed safe), confirm whether `ActorDeath` fires mid-transition and, if it does, that it doesn't produce a false kill.

---

## Step 5 — Measured XP, allocated by damage share

**🟡 Status: partial.** The allocator is verified in play — 51 consecutive kills 2026-08-21 with **stranded XP at zero throughout**, and per-npc XP reconciling to the point on two of three ids. Two things the step explicitly asks for are still unmeasured: the **XP settle window** (`SETTLE_TICKS = 2` remains desk-chosen) and the **residual noise floor**. Also open: attribution runs ~0.25% high because non-combat XP earned mid-fight is allocated to the monster — FINDINGS 2026-08-21.

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

**⬜ Status: not started.** No code. This is the hardest correctness problem left in the client.

**Build:** Mirror `LootManager` — record `ItemSpawned` per tile per tick, handle `ItemQuantityChanged` deltas for stackables, iterate the NPC's `size × size` footprint on death.

**Acceptance:** Loot logged matches what actually dropped, verified by hand on a slayer task.

---

## Step 7 — Loot attribution + guards

**⬜ Status: not started.** Depends on Step 6.

**Build:**
- Attach loot to kill records from the state machine
- Contested-tile guard → `loot_unknown`, **never** loot-empty
- Corpse counter (both modes — item counting and Prayer XP)
- `scene_has_other_players` flag — **boolean only, never record who they are**
- Parallel path: `ItemContainerChanged`, collection log message, pet message

**Acceptance:** A deliberate multi-kill on one tile produces `unknown`, not empty. Corpse counting matches hand-counted bones. Test the Prayer XP mode with a bonecrusher equipped and confirm it agrees with the item-count mode when both are available.

---

## Step 8 — Confidence grading, then batching and upload

**⬜ Status: not started.** Grading exists in the client already (`Confidence`, ceiling lowered to `UNCONTESTED` 2026-08-21); the batching and upload half needs a backend that does not exist.

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

**🟡 Status: partial — pulled and validated.** `tools/fetch-reference-data.sh` is committed; one API call plus two awk passes, 0.9s, writes gitignored `data/monsters.tsv`. **4,124 npc ids**, one row each. Validated by prediction, not just inspection: it called Watchman max HP correctly on a monster nobody had hand-checked. **Not done:** the npc↔wiki bridge is only as good as the name match, and 102 ids still carry no max HP.

**The multiplier half of this step is cancelled — see FINDINGS 2026-08-22.** Step 5 measures XP from `StatChanged`; nothing in the client computes or reads an XP multiplier, and nothing is planned to. `experience_bonus` is *published* by the Bucket API for 81.5% of ids, so where a multiplier is ever wanted it is read, not derived — `spec-reference-data.md` already said "read it, never compute it" while this step still said "compute XP multipliers". That contradiction is resolved in favour of reading. **The `InstantDamageCalculator` cross-check below is therefore dropped**: it would validate a number no shipped code consumes.

Standalone script hitting the Wiki Bucket API. Pull `infobox_monster`, cache locally, build the `npc_id` bridge via name + combat level.

Start with common slayer monsters; the table fills in incrementally. **Must never block kill recording.**

**~~Acceptance addition (2026-08-14)~~ — dropped 2026-08-22.** The `InstantDamageCalculator` `XP_MODIFIERS` diff was specified when Step 5 derived XP from damage and needed a trustworthy multiplier. Step 5 no longer derives anything. Kept in the record rather than deleted, per the append-only rule — if a published rate on the website ever needs a multiplier, reinstate it *then*, and re-read `docs/LICENSING.md` first.

### Step 0b — always_drops[] pull

**🟡 Status: pulled and validated, not yet consumed.** `tools/fetch-always-drops.py` is committed. **4,339 rows covering 2,798 npc ids with a guaranteed drop, 2,710 of them countable**, in 17 seconds. Nothing in the client reads it yet — it feeds the corpse counter, which arrives with Step 7.

**The Bucket API cannot do this — the spec was wrong.** `bucket('dropsline')` exposes exactly two fields, `page_name` and `item_name`. No rarity, no quantity, ~35 field names probed. Rarity is the whole point, so the puller parses `{{DropsLine}}` out of page wikitext via `action=query&prop=revisions`, batching **50 titles per request** — ~1,350 names in 27 calls, one process. See FINDINGS 2026-08-22.

Python rather than bash, deliberately: nested braces, HTML comments inside parameter values, quantity ranges and duplicate version blocks make this a real parse. `fetch-reference-data.sh` stays awk because a flat field grab suits it.

**Known gaps, all recorded rather than guessed:**
- **56 rows have blank quantities** where a monster's version blocks disagree (Black demon, Lesser demon). Emitted `countable=0`; there is no way to tell from a wiki page which version was killed.
- **Guaranteed ≠ countable.** Clue scrolls, caskets, coins and Zulrah's scales are all `rarity=Always` and all lie about corpse count — they stack or drop one regardless. Flagged `countable=0`.
- **Kalphite Queen has no guaranteed drop**, and neither does Rockslug (independently reproducing FINDINGS 2026-08-14). The corpse counter is simply unavailable for those monsters — not a missing-data case. **KQ is the next Step 4 target, so plan on not having it there.**

### Step 0c — combat formula implementation *(new, from `spec-performance.md` §8)*

**⬜ Status: not started — and blocked on a data gap nobody had checked.** Requires 0a for NPC defence stats. Needs no game access.

**The Bucket API does not expose monster defence bonuses.** Probed ~55 candidate field names 2026-08-22. `infobox_monster` gives levels (`attack_level`, `strength_level`, `defence_level`, `magic_level`, `ranged_level`), `attack_bonus`, `strength_bonus`, `magic_damage_bonus`, `max_hit`, `attack_speed`, `attack_style`, `slayer_level`, `slayer_experience`, `size`, `poisonous`. It does **not** give `dstab`/`dslash`/`dcrush`/`dmagic`/`dlight`/`dstandard`/`dheavy`, which exist in the page wikitext but are not in the bucket. `infobox_bonuses` is the equipment bucket, keyed on `page_name`, not monsters.

So the DPS formula's `AverageDefBonus` term has no structured source. Options, in order of preference, none of them started: parse the infobox out of `action=parse&prop=wikitext` per page (slow, 3,200+ requests, and brittle against template edits); find a bulk source; or scope 0c to the accuracy/max-hit half that needs only levels and offensive bonuses. **Decide before starting, not halfway through.**

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
can reach Zulrah, Vorkath and the Grotesque Guardians — all of which transform *while
already being damaged*.

**Correction (2026-08-22): this section previously claimed Account B "has killed Zulrah,
Vorkath and the Grotesque Guardians." That is false.** The account's collection log read
**Zulrah kills: 0, Obtained 0/10, Personal Best: N/A** when the client was opened on
2026-08-22. The original claim came from a character export read on 2026-08-21; whatever
it was inferred from, it was not kill counts. **Access is not history.** The distinction
matters because the recommended order below was written assuming familiarity with these
fights, and the first Zulrah attempt ended in a death at 338/500 damage. Re-derive
account capability from the collection log, not from quest or gear access.

**Recommended order — revised 2026-08-23 after two failed boss trips.**

**Use Nightmare Zone. Not a dangerous boss.** Core's `NpcUtil` transform list is full of
`NZONE_` entries — the instanced minigame at Yanille, where *"if you die here, you will
not lose any of your items"*. This was available the whole time and two deaths were spent
before anyone read past the gargoyle constants in that file. See FINDINGS 2026-08-23.

1. **Nazastarool, in a Nightmare Zone practice dream.** Shilo Village boss, appears in
   core as `NZONE_ZQ_MAINZOMBIE1`/`2`. **Transforms twice per fight** — zombie, skeleton,
   ghost — so it exercises the carry-forward branch more times per attempt than KQ does,
   in a safe instance, repeatable back to back, at a difficulty scaled for a mid account.
   A practice dream can be set to that boss alone. **This is the cheapest remaining way
   to close the acceptance criterion.**
2. **Damis** (`NZONE_FD_DAMIS_NORMAL`), same dream, as a second transforming case.
3. **Kalphite Queen.** ~~Highest-value target.~~ **Attempted 2026-08-23** — form-1 death
   confirmed to emit no phantom kill, which was the #15394 exposure. Only worth
   revisiting for a completed kill, and it killed the player on the first attempt.
4. **Grotesque Guardians.** **Gated harder than it looks:** needs Slayer 75 *and* an
   active gargoyle task *and* a one-time brittle key at **1/150** from gargoyles on task.
   The account has none of the three as of 2026-08-23. Not the cheap option.
5. **Zulrah.** **Done 2026-08-22** — nine transitions, mechanism confirmed, player died at
   338/500. Hard fight to learn; no reason to return.
6. **Vorkath.** Still an interesting XP-attribution case (+20% measured against a
   published +0%), but that is a Step 5 question, not a Step 4 one.

### Still blocked

- **Nechryael** — needs 84 Slayer; Account B has 81. Three levels.
- **Superior slayer monsters** — needs Bigger and Badder (150 Slayer points). Slayer
  points are not in the character export; ask before planning around it.
- **ToA scaled HP** — "Into the Tombs" is not started on Account B.

### Verified-from-zero — what it is, and what it is not

Both accounts have kill history predating the plugin. Everykill only counts what it
observes from the moment it is running, so this contaminates nothing — but neither
account can produce a *verified-from-zero* count for a monster it had already killed.

**This is not a property of account type.** A brand-new main is verified-from-zero; a
five-year-old ironman is not. It depends on one thing only: whether the plugin was
running before that account's first kill of that monster.

**The plugin behaves identically for every account type and must continue to.** There is
no gate, no per-type branch, and no reason for one. Everykill is for every account.

The distinction is a **label the site applies to a count**, never a restriction in the
client. "Tracked from zero" is a stronger claim than "tracked since install", and mixing
the two silently would be exactly the dirty data this project exists to avoid.
PROJECT.md lists verified-from-zero as one of the four things nobody else does — a
feature to expose honestly, not a limitation to hide.

`firstKillMillis` per npc_id already gives the client the raw material. What the client
cannot know is whether kills happened before install; there is no API that exposes it,
which is the whole reason this project exists. Self-declared, or inferred for genuinely
new accounts. **Open design question, not a solved one.**

---

## Testing protocol

Per the imported conventions: you cannot verify plugin behaviour yourself, and must never automate game input. After each step, offer to launch RuneLite via `./gradlew run`, state exactly what to test, and wait for confirmation before treating the step as complete. A clean JVM start is not a passing test.

Several spec assumptions are explicitly unverified. When testing reveals one is wrong, **say so plainly and propose the correction**.
