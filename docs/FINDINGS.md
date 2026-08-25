# Findings log

Append-only record of everything empirically established during development. Past entries are never edited or deleted — a superseded finding gets a new entry that links back to it. See `WORKING-AGREEMENT.md` for the format and the reasoning failure modes this log exists to catch.

---

## 2026-08-14 — ActorDeath fires at zero health, not at actual death, for transform-death NPCs

**Status:** contradicted-spec
**Method:** Direct observation in the dev client — one salted rockslug kill, then a deliberately unsalted rockslug left to sit at 0 HP and walked away from.
**Finding:** `ActorDeath` and `isDead()` fire when an NPC's health ratio hits zero, not when it actually dies. The unsalted rockslug was confirmed still alive and immobile at 0 HP while `ActorDeath` had already fired for it — a naive implementation would have logged a false kill.
**Consequence:** `EverykillTrackerPlugin` corrected to never trust `ActorDeath`/`isDead()` for `TransformDeathNpcs`, unconditionally rather than as a fallback. Only a despawn with a coincident item spawn counts. `spec-kill-detection.md` edge case A rewritten; top-of-file note added that "health ratio zero" and "dead" are different states.
**Source:** runelite/runelite#12453, #15394, #16479 — corroborating (different plugins reading the same underlying health-ratio-zero state), not direct reports against `ActorDeath` itself.

---

## 2026-08-14 — ActorDeath's timing relative to the finishing item is nondeterministic

**Status:** verified
**Method:** Two consecutive salted rockslug kills: one salted before combat brought it to 0 HP, one salted after it sat at 0 HP for a moment.
**Finding:** `ActorDeath` may or may not fire depending on when the finishing item is used relative to reaching 0 HP. Both orderings, plus the withheld-salt case, resolved correctly under the corrected logic — exactly one kill, or zero.
**Consequence:** No further code change. Confirms the corrected logic handles both real play patterns, not just the ordering the bug was first caught on.
**Source:** none — direct testing.

---

## 2026-08-14 — Lootless kills are a structural limitation of loot-coincidence detection, not an edge case

**Status:** verified (drop-table structure) / unverified-assumption (real-play miss rate)
**Method:** OSRS Wiki drop table for Rockslug.
**Finding:** Rockslug's drop table has a "Nothing" outcome nested inside its gem sub-table (~6/128 reach chance). A salted kill resolving to that outcome despawns with no item and is silently uncounted under item-coincidence detection. Generalises to any transform-death monster whose drop table includes a "nothing" entry.
**Consequence:** `spec-kill-detection.md` reframed from "known gap, not yet mitigated" to "known structural limitation, permanent." A dedicated N-kill hand-count test was proposed then dropped — at this rate even 50 kills can't distinguish the real rate from zero (0/25 observed, exactly the predicted outcome either way). Unconditional `counted=false` logging left in permanently for passive measurement over normal play instead. The fix is justified by drop-table structure alone, independent of whatever rate normal play eventually shows.
**Source:** oldschool.runescape.wiki/w/Rockslug

---

## 2026-08-14 — TransformDeathNpcs.IDS was missing six variant IDs (reported as seven at the time, uncorrected until now)

**Status:** verified
**Method:** Grepped the client's own `NpcID.java` for every `ROCKSLUG`/`LIZARD`/`GARGOYLE`/`ZYGOMITE` constant, cross-referenced against the existing list.
**Finding:** Missing `SLAYER_ROCKSLUG_CRYPT_OF_TONALI` (Varlamore variant), `LEAGUE_SUPERIOR_ROCKSLUG`, `LEAGUE_SUPERIOR_GARGOYLE`, `LEAGUE_SUPERIOR_GARGOYLE_DEAD` (Leagues variants), `SLAYER_LIZARD_LARGE1_GREEN_LOWRANGE`, `SLAYER_LIZARD_SMALL1_GREEN_LOWRANGE` — six IDs. Reported as "seven" in the same-day chat and accepted without either side recounting; corrected here on the first pass through `WORKING-AGREEMENT.md`'s reporting discipline.
**Consequence:** All six added to `TransformDeathNpcs.IDS`. A summarised wiki fetch used earlier in the same investigation was separately found to contain a factual error (mislabelled `SLAYER_ROCKSLUG_BABY` as the superior monster) when cross-checked against this same client source — `NpcID` established as ground truth over wiki summaries for ID/name questions going forward (`WORKING-AGREEMENT.md` 3d).
**Source:** client `NpcID.java` (runelite-api 1.12.35).

---

## 2026-08-14 — The review queue cannot actually catch a new (unlisted) transform-death monster

**Status:** contradicted-spec (corrects same-day reasoning, not a prior spec)
**Method:** Tracing the actual event path, `onActorDeath` → `onNpcDespawned`, for an NPC not on `TransformDeathNpcs.IDS`.
**Finding:** For an unlisted NPC, `ActorDeath` fires early (the same false zero-health signal as the first entry above), a kill is emitted immediately via the normal `ActorDeath` path, and the record is marked `actorDeathSeen`. When the NPC actually despawns later, `onNpcDespawned` takes the "already killed" branch, not the review-queue branch — the review line never fires. `FAMILY_NAME_HINTS` only catches variants of already-known families, not a genuinely novel monster.
**Consequence:** Reasoning corrected in `spec-kill-detection.md` edge case A. This is the worked example now cited in `WORKING-AGREEMENT.md` 3a ("correct-sounding logic that doesn't survive tracing the code path").
**Source:** none — code tracing.

---

## 2026-08-14 — Post-ActorDeath survival check added as a general detector (logging only, not yet calibrated)

**Status:** unverified-assumption (mechanism implemented, not yet calibrated against real data)
**Method:** Proposed as the fix for the review-queue gap above — if `ActorDeath` fired but the actor keeps existing (further hitsplats land on it, or its eventual despawn comes much later than normal), that's suspicious regardless of whether the NPC is on any known list. List-independent by design; expected to also apply to edge case B (Kalphite Queen, Zalcano phase transitions) via the same mechanism, since both produce the identical signature.
**Finding:** Not yet established — implemented as logging only (`CombatRecord.actorDeathTick`, a `Suspected post-ActorDeath hitsplat` line, and `ticksSinceActorDeath` added to both despawn-confirmation log lines) specifically to observe what a normal delay looks like across many ordinary kills before any threshold gets used for a real decision.
**Consequence:** No detection behaviour changed. Outstanding: accumulate enough normal-kill data to establish a baseline `ticksSinceActorDeath` range; not yet tested on a phase-transition boss (account access limited, same constraint as `BUILD-ORDER.md` Step 4).
**Source:** runelite/runelite#15394, #16479 (motivating the edge-case-B applicability).

---

## 2026-08-14 — Zygomite `_CAP` variants excluded from TransformDeathNpcs.IDS — unverified

**Status:** unverified-assumption
**Method:** Reasoning from `NpcID` naming pattern only (`_CAP` suggests a pre-combat capped-mushroom stage) — no test performed.
**Finding:** Assumed non-combat precursor state, not confirmed. If wrong in the specific way where the capped form despawns and a *separate* NPC spawns in its place (rather than transforming in place via `NpcChanged`), the combat record is lost and the kill goes silently unrecorded — not a graceful fallback.
**Consequence:** Documented as unverified in `TransformDeathNpcs.java` and `spec-kill-detection.md` rather than treated as settled. Cannot be tested on this account (needs 57 Slayer). This is the worked example cited in `WORKING-AGREEMENT.md` 3b ("plausibility standing in for verification").
**Source:** none — naming-pattern inference only.

---

## 2026-08-14 — Step 4 (phase transitions) built on the existing post-ActorDeath survival detector, not a separate guard

**Status:** unverified-assumption (mechanism implemented, matches the reasoning that motivated it, not yet tested in-game)
**Method:** Design reasoning, cross-half decision — edge case A2's `actorDeathSeen`/`actorDeathTick` fields were built list-independent specifically because edge case A (transform deaths) and edge case B (phase transitions — Kalphite Queen, Zalcano) share one root cause: `ActorDeath` fires at health-ratio-zero, not at actual death. `onNpcChanged` was written to reuse that same signal rather than add a phase-specific check.
**Finding:** Not yet established by play — implemented as: `NpcChanged` looks up the record by the actor's (stable) object identity, retargets `CombatRecord.npcId`/`npcName` to the new phase via `CombatRecord.retarget()`, and if `actorDeathSeen` was set, clears it and logs the false-ActorDeath-then-phase-change sequence instead of treating the earlier `ActorDeath` as a real kill. No kill is ever emitted from this handler.
**Consequence:** `EverykillTrackerPlugin.onNpcChanged` added. `CombatRecord.npcId`/`npcName` changed from `final` to mutable to support retargeting the same record across a phase change. Superior slayer monsters and Nechryael death spawns need no special handling here — they're separate NPC objects with their own records, not `NpcChanged` on the same actor, so they still count as separate kills by construction. **Known, deliberately unaddressed gap:** the Kalphite Queen/Zalcano reports (`runelite/runelite#15394`, `#16479`) describe health hitting zero and regenerating on the *same* npc_id during an invulnerability window, with no `NpcChanged` at all — this handler has no id-change event to key off in that case, and it remains open per `spec-kill-detection.md` edge case B. Untested in-game (no accessible multi-phase boss on this account); superior/Nechryael separate-kill behaviour is testable and should be checked first.
**Source:** runelite/runelite#15394, #16479 (unchanged from the edge-case-B finding this reuses).

---

## 2026-08-14 — Two parallel, disagreeing kill counters currently exist in the plugin

**Status:** contradicted-spec
**Method:** Code reading while labelling legacy scaffolding in `EverykillTrackerPlugin.java`.
**Finding:** The damage-first `openRecords`/`CombatRecord` pipeline (Steps 1–4) is not the only thing counting kills. `onNpcLootReceived` independently does `killCounts.merge(npcName, 1, Integer::sum)`, feeding `snapshot.json` via `buildSnapshot()`. That handler fires from RuneLite's own `NpcLootReceived`/`LootManager`, which relies on the same item-coincidence heuristic Step 3 found unreliable for transform-death monsters (silently misses lootless kills — see the rockslug "Nothing" drop-table finding above) and keys on `npcName` rather than `npc_id`, directly contradicting the "store raw npc_id forever" rule in `docs/PROJECT.md`.
**Consequence:** Both paths left in place (2026-08-14 decision: leave legacy code, label it rather than remove mid-Step-4), but `EverykillTrackerPlugin.java` now carries an explicit top-of-class LEGACY block and inline markers so neither is mistaken for part of the verified pipeline. `killCounts`/`drops`/`writeSnapshot`/`GistUploader` must be removed or reconciled with the real pipeline before Step 8 (upload) — uploading the legacy counter as-is would ship exactly the silent transform-death undercount Step 3 exists to fix. **Formalised 2026-08-14 in `docs/SUBMISSION-CHECKLIST.md` §6 and §8** as hard submission blockers (`GistUploader` removal, the two-counter reconciliation) — this is now a checklist item with a stated consequence for shipping, not just an internal note. Not acted on yet, per the same instruction that added the checklist: it's a pre-submission gate, not a right-now task.
**Source:** none — code reading.

---

## 2026-08-14 — Prior-art lead: InstantDamageCalculator does our exact derived-XP arithmetic, in production

**Status:** unverified-assumption (lead to investigate, not yet read)
**Method:** Relayed from the other project half's full Plugin Hub survey (2,013 plugins, Aug 2026) — not independently confirmed from this side yet.
**Finding:** InstantDamageCalculator (18k installs) reportedly derives damage dealt from the Hitpoints XP delta — the same derivation `spec-kill-detection.md`'s "XP derivation" section specifies for Step 5 (damage → XP, inverted). If accurate, it will already have hit the same rounding and XP-settle-window problems Step 5's open questions (noise floor, settle window in ticks) are trying to establish from scratch.
**Consequence:** **Read its source before starting Step 5.** Possible shortcut on the settle-window/noise-floor unknowns currently listed as "measure from real data" in `spec-kill-detection.md`. Not yet read — nothing in the spec or code should be treated as confirmed by this entry until it is.
**Source:** Plugin Hub survey (relayed), plugin name InstantDamageCalculator, ~18k installs — not yet cross-checked against the plugin's own source.

---

## 2026-08-14 — Prior-art lead: Monster Stats may shortcut the Step 0a NPC stat table

**Status:** unverified-assumption (lead to investigate, not yet read)
**Method:** Relayed from the other project half's full Plugin Hub survey (2,013 plugins, Aug 2026) — not independently confirmed from this side yet.
**Finding:** Monster Stats (31k installs) reportedly maintains an NPC defensive-stat dataset keyed to the client, which is what `BUILD-ORDER.md` Step 0a currently plans to build from scratch via the OSRS Wiki Bucket API (`infobox_monster`, name + combat-level bridge to `npc_id`).
**Consequence:** **Investigate before starting Step 0a.** If its dataset is usable directly, it may replace or substantially shortcut the Bucket API pull; at minimum it may offer a working name-to-`npc_id` bridge, which is one of Step 0a's harder sub-problems. Per `WORKING-AGREEMENT.md` 3d, the client/plugin source is the thing to verify against directly — this entry is a lead, not a confirmed shortcut, until that's done.
**Source:** Plugin Hub survey (relayed), plugin name Monster Stats, ~31k installs — not yet cross-checked against the plugin's own source.

---

## 2026-08-14 — InstantDamageCalculator source read: confirms the 1.33 divisor, exposes an unhandled 200M-XP edge case, and has a directly-reusable manual multiplier table

**Status:** verified (source read directly, supersedes the "lead" entry above)
**Method:** Cloned `github.com/geeckon/instant-damage-calculator` and read `InstantDamageCalculatorPlugin.java` / `NPCWithXpBoost.java` in full.
**Finding, four parts:**
1. **1.33 divisor confirmed in a second, independent, production implementation.** `hit = diff / 1.33 / modifier` — the same Hitpoints-XP-per-damage constant `spec-kill-detection.md` already had from the wiki, now corroborated by an 18k-install plugin doing the identical arithmetic in the opposite direction (damage-from-XP instead of our XP-from-damage). Same formula, not new information, but real independent corroboration.
2. **New, concrete gap our spec doesn't currently cover:** IDC explicitly handles `FakeXpDrop` alongside `StatChanged`, with the comment *"Need this event for players with 200M hitpoints xp"* — `StatChanged` alone silently stops being a reliable Hitpoints-XP signal once a player is XP-capped at that skill. `spec-kill-detection.md`'s XP derivation section only lists `StatChanged`; nothing accounts for the capped case. Low priority (200M Hitpoints is a small population) but a real, previously-unflagged edge case, not a hypothesis — added to the spec's edge case list.
3. **Directly reusable data: `XP_MODIFIERS`, a hand-maintained `Map<NPCWithXpBoost, Double>` of ~150 monsters with non-1.0 XP multipliers** (bosses, superior slayer monsters, NMZ/CoX/ToB/ToA content), plus `XP_MODIFIERS_WITH_MODES` for phase/mode-dependent multipliers (Tekton enraged, Vanguard styles, ToB room phases) and a full `TOA_NPC_BASE_STATS` table with the raw stats (HP/Attack/Strength/Defence/defensive+offensive bonuses) needed to compute ToA's dynamically-scaled multiplier at runtime. This is exactly `spec-kill-detection.md`'s "manual override column" for monsters whose bonus ignores the formula — this table can seed it directly rather than being derived from scratch, pending a licence/attribution check before copying any of it verbatim.
4. **Nuances edge case G (variable HP) for ToA specifically.** `updateToaModifiers()` reads raid level, path level (per-path widget text) and live party size from varbits/widgets and computes a real per-NPC multiplier for Tumeken's Warden path content, rather than treating scaled content as unrecoverable. `spec-kill-detection.md` currently says to exclude all scaled-HP content (CoX/ToA/self-healing) from HP-derived validation entirely — that blanket exclusion may be more conservative than necessary specifically for ToA, where the scaling is formulaic and observable client-side. Not adopted yet; flagged as a design question for whoever owns `spec-kill-detection.md`'s edge case G, since loosening it is a real decision, not a given.
**Consequence:** `spec-kill-detection.md` XP derivation section updated with the 200M-cap gap (see edge case list) and a pointer to this entry for the multiplier table and ToA nuance. Nothing in our code changed yet — Step 5 hasn't started. **Licence checked 2026-08-14 per `docs/LICENSING.md`: `instant-damage-calculator`'s repo `LICENSE` is BSD 2-Clause (Copyright geeckon, 2021).** Per `LICENSING.md`'s rule 2, literal reuse of `XP_MODIFIERS`/`XP_MODIFIERS_WITH_MODES`/`TOA_NPC_BASE_STATS` at Step 5 is permitted *if* the original copyright notice and licence text are retained and the origin noted in a comment — not done yet, and per the operative rule from this same conversation, not to be done without flagging it as a decision first. Reading and reimplementing from understanding (the rate values themselves, not the Java) remains the default and needs no attribution at all.
**Source:** `github.com/geeckon/instant-damage-calculator`, read 2026-08-14 (commit at clone time, `--depth 1`); `LICENSE` file in the same repo.

---

## 2026-08-14 — Decision: IDC's XP_MODIFIERS is a test oracle for Step 0a's multiplier table, not a source to copy

**Status:** unverified-assumption (decision recorded; nothing computed yet, Step 0a hasn't started)
**Method:** Explicit project decision, given the licence check above already established copying would be legally permitted with attribution.
**Finding:** Legal permission is not the deciding factor here. `XP_MODIFIERS` is a hand-maintained table reflecting one person's judgment calls — copying it would create a silent dependency on decisions we can't independently verify and won't be told about when they're revised (a monster's multiplier changing on a game update, a correction to a past mistake, a monster added late). **Decision: compute our own multipliers from the formula in this spec plus the Wiki Bucket API at Step 0a, independently of IDC, then diff the result against IDC's ~150 entries as a validation step.** Agreement raises confidence in both implementations; any disagreement is a bug in one of us worth chasing down, not a data source to silently prefer. This is independent-implementation-validated-against-prior-art, not inheritance — no attribution required because nothing is copied, per `LICENSING.md`'s default rule.
**Consequence:** Step 0a's acceptance criteria should include this diff as a concrete step once the formula-based multiplier table exists. Not yet actioned — Step 0a is unbuilt.
**Source:** none — project decision, not an external claim.

---

## 2026-08-14 — Monster Stats source read: bundled defensive-stat CSV is a real npc_id bridge, but not a multiplier-table shortcut

**Status:** verified (source read directly, supersedes the "lead" entry above)
**Method:** Cloned `github.com/Koitere/monster-stats` and read `NPCDataLoader.java`/`NPCStats.java` plus the bundled `src/main/resources/monsterdata.csv` (3,109 lines).
**Finding:** The plugin ships a static CSV bundled as a build resource — not a live wiki/Bucket API call at runtime. Per row: name (with `Name#Variant` alt-form syntax, e.g. `Abyssal demon#Wilderness Slayer Cave`), elemental weakness + %, magic/crush/stab/slash/standard/heavy/light **defence only**, one or more comma-separated `npc_id`s, max hits (free-text), attack styles (free-text), flat armour. **It does not contain Attack/Strength/Defence/Hitpoints combat levels or offensive Attack/Strength bonuses.** Our multiplier formula (`spec-kill-detection.md`) needs `AverageLevel` (from Attack/Strength/Defence/HP) and `AverageDefBonus` + `StrengthBonus` + `AttackBonus` — this CSV supplies the `StabDef`/`SlashDef`/`CrushDef` third of `AverageDefBonus` and nothing else the formula needs. **Not the shortcut it looked like from the outside** — it solves a different problem (defensive stats for display) that happens to overlap with ours on three fields.
**What it does still offer:** a working, already-solved pattern for the npc_id ↔ display-name bridge — one canonical name can map to several `npc_id`s (location/phase variants), disambiguated via a `Name#Variant` string convention and a `Map<String, NPCStats>` of alt-forms keyed off the base name, plus a direct `Map<Integer, NPCStats>` for id lookup. That's a real structural reference for Step 0a's own name↔`npc_id` bridge, independent of the wiki being the actual data source. No fetch/generation script is present in this repo (the CSV appears to be maintained directly, not regenerated from a script committed here) — provenance and update cadence of the CSV itself is unverified, so it should be treated as a one-time structural reference, not an ongoing data source to depend on.
**Consequence:** Step 0a should still pull from the Wiki Bucket API for the fields this CSV lacks (combat levels, offensive bonuses, XP multiplier inputs) — no shortcut on the hard part. The `Name#Variant` bridging pattern is worth reusing structurally when Step 0a is built. `spec-data-model.md` not yet updated with this — do that when Step 0a actually starts, per `WORKING-AGREEMENT.md` (don't let a lead harden into an applied decision before it's used). **Licence checked 2026-08-14 per `docs/LICENSING.md`: `monster-stats`'s repo `LICENSE` is BSD 2-Clause (Copyright Liam King, 2024)**, same permitted-with-attribution status as the IDC entry above, though the CSV's own data provenance (whether Liam King has redistribution rights to the underlying wiki-sourced values) is a separate, unchecked question from the code licence — per `LICENSING.md`'s "data and APIs are separate from code" section, this matters only if literal CSV rows are ever copied, which is not proposed here.
**Source:** `github.com/Koitere/monster-stats`, read 2026-08-14 (commit at clone time, `--depth 1`); `LICENSE` file in the same repo.

---

## 2026-08-14 — First real baseline sample for `ticksSinceActorDeath`: 3 ticks, 5/5, rock crabs

**Status:** verified (first sample only — n=5, one monster, one location)
**Method:** Direct observation in the dev client. Five ordinary rock crab kills (Step 1/2 sanity pass ahead of the Step 4 crab wake-up test), debug log inspected afterward.
**Finding:** All 5 kills: `ActorDeath` fired, kill emitted with `damageByPlayer == damageTotal` (no foreign damage), despawn confirmed 3 ticks later every single time — `ticksSinceActorDeath=3`, 5/5, no variance at all. This is the first real data point for the post-ActorDeath survival detector's (edge case A2) outstanding question — "what does a normal delay look like" — which until now had no data behind it at all.
**Consequence:** None yet — n=5 on one low-HP, single-hitsplat monster in one location is nowhere near enough to set a threshold, and the zero variance here is plausibly an artefact of rock crabs' short, simple death animation rather than a general constant. Recorded as the anchor point for accumulating more samples across different monsters (longer death animations, multi-hitsplat kills) before any real threshold is set. `spec-kill-detection.md` edge case A2 still correctly says "outstanding."
**Source:** none — direct testing.

---

## 2026-08-14 — Rock/sand crab wake-up is a genuine NpcChanged on the same actor, not a despawn+spawn pair

**Status:** verified (rock crabs specifically; not evidence about real phase bosses)
**Method:** Temporary unconditional discovery logging on `NpcSpawned`/`NpcChanged`/`NpcDespawned` (added specifically for this test, not part of the normal pipeline), each tagging the NPC's `System.identityHashCode()`. Observed 7 dormant-rock-to-crab wake-ups in the dev client (wake-only pass, none killed).
**Finding:** All 7 wake-ups produced a `NpcChanged` event (dormant "Rocks", npc_id 101/103 → "Rock Crab", npc_id 100/102), and in every case the `identityHashCode` on the `NpcChanged` event matched exactly the `identityHashCode` recorded when that same dormant rock originally spawned. 7/7, no exceptions, no despawn+spawn pair observed for any wake-up. This directly confirms the assumption Step 4's `onNpcChanged` carry-forward logic depends on — "same actor, new id" — for this monster.
**Consequence:** No code change (the existing Step 4 implementation already assumed this and was right to). `spec-kill-detection.md` edge case B updated to note this as the first empirical confirmation of the same-actor assumption, though explicitly scoped to crabs — **KQ/Zulrah/Vorkath/Alchemical Hydra remain untested and stay on `BUILD-ORDER.md`'s deferred list**; a simple wake-up transform is a much lower bar than a real multi-phase boss fight, and the specific failure mode edge case B is worried about (false `ActorDeath` mid-transition) wasn't exercised by this test at all — crabs don't have a health-based phase transition the way KQ/Zalcano do. The temporary discovery logging (unconditional `NpcSpawned`/`NpcChanged`/`NpcDespawned` lines) should be removed now that this test is done.
**Source:** none — direct testing.

---

## 2026-08-14 — Wake-and-kill pass: 4/4 clean kills, correctly retargeted — but Step 4's actual carry-forward branch was never exercised

**Status:** verified (kill correctness for crabs) / structural gap identified (carry-forward branch remains untested by any means available on this account)
**Method:** Direct observation, same session as the wake-only test above. Four rock crabs killed after waking naturally (not pre-woken), traced end-to-end via `identityHashCode` from `NpcChanged` through `Kill:` to the confirming despawn.
**Finding, two parts:**
1. All 4 kills correct: exactly one `Kill:` line per crab, each logged under the **post-transform** `npc_id` (100 or 102, never the dormant 101/103), confirming `CombatRecord.retarget()` updates the record correctly. `ticksSinceActorDeath=3` on all 4, extending the baseline from the earlier 5/5 sample to **9/9 overall, still zero variance**. One neighboring crab's death (a different actor, no open record) was correctly not logged as our kill — confirms edge case C (other players' kills) holds even adjacent to our own fight.
2. **In all 4 cases, `NpcChanged` completed 2–3 ticks *before* the first hitsplat landed** — reconstructed by comparing each `NpcChanged` tick against each record's `openedAtTick` (derived from the kill's `durationTicks` and the despawn tick). A dormant rock isn't attackable, so combat can only start after the wake-up finishes; the record therefore always opens fresh, already on the post-transform id. **This means the record-gated branch in `onNpcChanged`** — the one that carries an *already-open* record through a live transform, and clears a false `actorDeathSeen` — **never executed once, in any of the 4 kills.** Rock/sand crabs cannot exercise it structurally, not just didn't happen to this time: the wake always precedes combat, never interrupts it.
**Consequence:** Step 4's core code path — carrying a mid-fight combat record through a real phase transition, and specifically clearing a false `ActorDeath` fired mid-transition — remains **completely unexercised** by any test available on this account. The crab tests confirm the *foundational* assumption (same actor persists across `NpcChanged`) and confirm ordinary kill correctness around a transform, but they cannot and structurally never could stand in for a real phase-boss test. This sharpens (does not weaken) the existing `BUILD-ORDER.md` deferred-list warning about KQ/Zulrah/Vorkath/Alchemical Hydra — that warning was already correct, and this is the specific mechanism proving why a crab substitute can't close the gap it describes.
**Source:** none — direct testing.

---

## 2026-08-14 — Method note: identityHashCode-tagged discovery logging (used for the crab tests, then removed)

**Status:** verified (as a technique — the two entries above are the results it produced)
**Method:** Documenting the technique itself for reuse, now that the temporary logging that used it has been removed from `EverykillTrackerPlugin.java` per the reasoning in `BUILD-ORDER.md`/`docs/CONVENTIONS.md` (unconditional per-event logging on `NpcSpawned`/`NpcChanged`/`NpcDespawned` fires constantly in a busy scene and must never survive into a shipped build).
**Finding:** The two crab-test findings above were only possible because every `NpcSpawned`/`NpcChanged`/`NpcDespawned` log line tagged the NPC with `System.identityHashCode(npc)` alongside its `npc_id`/name/tick/location. `npc_id` alone can't distinguish "the same actor changed id" from "a different actor happened to spawn with the same id a moment later" — `identityHashCode` (not cryptographically unique, but sufficient across a short, low-cardinality test window) is what let the wake-up sequence be traced end to end across three separate event types and confirmed as one continuous actor, and separately let the wake-and-kill traces line up each `Kill:` line with the exact `NpcChanged`/despawn pair that produced it.
**Consequence:** No code currently uses this technique — it was removed with the rest of the temporary discovery logging. **Reinstate it (unconditional, identity-tagged `NpcSpawned`/`NpcChanged`/`NpcDespawned` logging, scoped to a short deliberate test session, never left running) if a similar same-actor-vs-new-actor question comes up again** — most likely candidates: Nechryael/superior spawn isolation, or if a real phase boss ever becomes accessible and edge case B needs the same treatment this got.
**Source:** none — technique note, not an empirical claim.

---

## 2026-08-14 — Unverified assumption, recorded honestly: ItemSpawned fires before NpcDespawned within the same tick

**Status:** unverified-assumption
**Method:** None on our side — flagged in review (2026-08-14, relayed from the other project half) rather than measured directly.
**Finding:** The transform-death item-coincidence check (`onNpcDespawned`) reads `itemSpawnPointsThisTick`, which `onItemSpawned` populates and which is cleared at the start of the *next* `GameTick` — so the check silently depends on `ItemSpawned` having already fired for the same tick's despawn by the time `NpcDespawned` is handled. We have never traced or measured RuneLite's actual event-dispatch order within a tick to confirm this holds. **The evidence we do have is inherited, not measured**: RuneLite's own `LootManager` relies on the identical ordering assumption and is treated as working in production — decent secondary evidence, but not our own finding, and not a substitute for tracing it directly.
**Consequence:** This assumption is load-bearing for the 2026-08-14 "lootless kills are a structural limitation" finding above. If the ordering assumption is wrong (`NpcDespawned` sometimes fires before the corresponding `ItemSpawned` within the same tick), some of what that finding attributes to genuine lootless kills (the drop-table "Nothing" outcome) could actually be item-arrived-too-late misses instead — a different failure mode with a different fix. No code change from this entry alone; recorded so this dependency is visible and not silently assumed solid. Worth tracing directly (or asking RuneLite's own maintainers/source) before leaning on it for anything beyond the current passive logging.
**Source:** none — inherited confidence from `LootManager`'s behaviour, not direct measurement.

---

## 2026-08-14 — Decision: composite "efficiency score" cut as unfixably prescriptive

**Status:** unverified-assumption (design decision, not an empirical claim — relayed from the other project half via `spec-performance.md`)
**Method:** Design reasoning in `spec-performance.md` §1/§7, consolidating and replacing the earlier gear-tiers-and-efficiency-scoring design.
**Finding:** A single composite "efficiency score" mashes two unrelated things (gear/stat/style performance vs. banking/travel/AFK behaviour) behind a weighting that would necessarily be the project's own opinion presented as measurement — not fixable by picking better weights, since any weighting is still an opinion. Replaced with two independent, single-meaning metrics: **damage efficiency** (observed DPS ÷ theoretical DPS) and **uptime efficiency** (observed kills/hr ÷ theoretical kills/hr *at the player's own observed DPS*), which decompose cleanly instead of conflating.
**Consequence:** No composite score anywhere in the design going forward. Cohorting by absolute gear tiers is also cut in the same pass (see next entry) — both were flagged by the other half as prescriptive in a way this project's "measure, never advise" posture can't tolerate. `docs/spec-performance.md` is now the canonical spec for this area; it supersedes `docs/spec-cohorts-efficiency.md`.
**Source:** `docs/spec-performance.md` §1, §7 (relayed design decision, not independently tested here).

---

## 2026-08-14 — Decision: cohorting by observed DPS band replaces gear tiers, removing the volume dependency for personal metrics

**Status:** unverified-assumption (design decision, not an empirical claim — relayed from the other project half via `spec-performance.md`)
**Method:** Design reasoning in `spec-performance.md` §4.
**Finding:** The earlier plan bucketed players by "relevant equipment bonus," a crude proxy needing a distribution of other players' data before it meant anything. Observed DPS against a specific `npc_id` + region already accounts for the monster's defences, the player's levels, accuracy, style and weapon speed in one number — it needs no distribution to compute, so **the band exists immediately from a player's own kills alone**, before any other player's data exists. Only the *median within* a band needs volume, and that fills in monster-by-monster over time rather than blocking the personal-metrics feature entirely.
**Consequence:** `docs/BUILD-ORDER.md`'s Tier 3 sequencing (per `spec-performance.md` §8) now ships single-player metrics first — observed DPS, damage efficiency, uptime efficiency, all working at N=1 — with cohort medians and the recommender table added later as thresholds are crossed. "Gear tier" language is retired project-wide in favour of "observed DPS band."
**Source:** `docs/spec-performance.md` §4, §7, §8 (relayed design decision, not independently tested here).

---

## 2026-08-14 — our_attacks/our_hits/our_max_hit tracked now; weapon_speed_ticks added as a schema placeholder, genuinely unpopulated

**Status:** unverified-assumption (weaponSpeedTicks specifically — a real, open gap, not a hypothesis)
**Method:** Code change in response to `spec-performance.md` §2's "record it now or lose it permanently" requirement.
**Finding:** `CombatRecord` now tracks `attacksCount`/`hitsCount`/`maxHit` from every `isMine()` hitsplat (zero-damage included), and both fields plus `weaponSpeedTicks` are on every `Kill:` log line. The first three were straightforward - pure counting from data already flowing through `onHitsplatApplied`. `weaponSpeedTicks` is not: this plugin has no weapon-speed data source at all yet. No loadout/equipment tracking exists in the real (non-legacy) pipeline, and RuneLite's `ItemComposition` doesn't expose attack speed directly the way it exposes other item stats - it needs its own small data source, the same shape of problem as the Step 0a NPC stat table but for weapons.
**Consequence:** `CombatRecord.weaponSpeedTicks` is left at `-1` (unknown) rather than guessed or left off the schema. The field exists in code and in `spec-data-model.md`'s `kill_event` shape now, per the "cannot be backfilled" urgency, but populating it is a genuinely open task - not decided here, not silently deferred either. Needs a decision: extend Step 0a/0b's Bucket API pull to cover weapon speed, or a separate small Step 0d.
**Source:** none — code change plus an honest gap, not an external claim.

---

## 2026-08-20 — The com.everykill rewrite compiles and passes its tests against the real client jar

**Status:** verified
**Method:** `javac --release 11` over all 16 main sources against `client-1.12.35.jar` and `runelite-api-1.12.35.jar` from the local Gradle cache, then the two test classes compiled and run under real JUnit 4.12.
**Finding:** The rewrite was delivered having never been compiled — 92 API symbols had been checked by grepping a RuneLite source clone, and the 34 tests had only ever run against a hand-written JUnit shim. Against the real jars it compiles with **zero errors** and the tests pass **34/34**. Two symbols flagged as likely breakages beforehand — `MenuAction.WIDGET_TARGET_ON_NPC` and `MenuEntry.getNpc()` — both exist and resolve. IntelliJ's own compiler agrees.
**Consequence:** `./gradlew build` is now a confirmation step rather than the first real gate. This validates API surface and logic only: Guice wiring, event-bus dispatch and all in-game behaviour remain entirely unproven.
**Source:** none — local toolchain against the pinned client jar.

---

## 2026-08-20 — `MenuAction.ITEM_USE_ON_NPC` is deprecated; `WIDGET_TARGET_ON_NPC` is the live opcode

**Status:** verified
**Method:** Compiler deprecation warning on `KillDetector`, then read `MenuAction.java` in `runelite-api-1.12.35-sources.jar` and grepped the full client source for both constants.
**Finding:** Every `ITEM_*` opcode in `MenuAction` carries `@Deprecated` while its `WIDGET_TARGET_*` twin does not — the inventory is itself a widget, so using an item on an NPC now arrives as opcode 8, not opcode 7. Core RuneLite references `ITEM_USE_ON_NPC` **nowhere outside the enum declaration**; `InteractHighlightOverlay`, `InteractHighlightPlugin` and `WikiPlugin` all switch on `WIDGET_TARGET_ON_NPC` alone.
**Consequence:** The transform-death path was never broken — it matched both, and the live branch carries it. The deprecated branch was removed from `KillDetector.onMenuOptionClicked`, which would otherwise have broken the build whenever RuneLite drops the constant. Still untested in game: whether a rock hammer on a gargoyle actually produces this event is the open half.
**Source:** `net/runelite/api/MenuAction.java` and core plugin sources, RuneLite 1.12.35.

---

## 2026-08-20 — Keying kill state on `npc.getIndex()` silently discards kills when the game recycles a slot

**Status:** verified
**Method:** Direct probe against `KillStateMachine` — resolve a kill on key 5, then damage and kill a different NPC on key 5 at increasing tick gaps, and count emissions.
**Finding:** The second kill is **discarded with no log line** at every gap up to and including `EMITTED_TICKS`, with a clean cliff immediately after:

```
gap= 1,3,5,8,10 ticks -> 1 kill emitted   (second kill lost)
gap=11,15       ticks -> 2 kills emitted
```

The double-fire suppression window cannot tell a recycled index from the actor that previously held it. This is a silent undercount — the failure mode this project ranks worst — and it would be invisible in play except as counts that quietly read low.
**Consequence:** `KillDetector` now mints an opaque per-actor key from an `IdentityHashMap<NPC, Integer>` and the state machine never sees an index. Keys are minted only for NPCs we damage and dropped on despawn. The machine keeps zero RuneLite imports. The repo's pre-rewrite code had used actor identity for exactly this reason; the rewrite lost it, and this restores it.
**Open, deliberately not measured:** how quickly OSRS actually recycles an NPC index. Keying on identity makes the question moot, which is a better answer than a measurement.
**Source:** none — probe against our own state machine.

---

## 2026-08-20 — `weaponSpeedTicks` dropped rather than carried across (supersedes the 2026-08-14 entry above)

**Status:** verified (decision, with the reasoning stated)
**Method:** Review of the field during the `com.everykill` merge.
**Finding:** The 2026-08-14 entry added `weaponSpeedTicks` to `CombatRecord` as a schema placeholder under `spec-performance.md` §2's "record it now or lose it permanently" rule, and recorded honestly that it was never populated. It sat at `-1` for its whole life with twelve lines of comment explaining why.
**Consequence:** Not carried into `KillRecord`. "Record it now or lose it" protects **observations**, and a field that has never held one is not an observation — it is a comment. The other five perf fields were carried: `regionId`, `attacksCount`, `hitsCount` and `maxHit` are stored, and `damageTotalSinceEngaged` became the derived `KillRecord.totalDamage()`, since the machine already tracks our damage and other players' damage separately. The caveat that it proves nothing about damage taken *before* we engaged is carried across verbatim. Reversible; the data source problem it flagged is still open and still needs its own step.
**Source:** supersedes the 2026-08-14 `our_attacks/our_hits/our_max_hit` entry above.

---

## 2026-08-20 — Combat XP is credited one tick BEFORE its hitsplat is dispatched

**Status:** contradicted-spec
**Method:** Diagnostic logging in `XpAttributor.allocate()` during a Kourend Catacombs dagannoth task, six independent samples, arithmetic cross-checked against the published rates.
**Finding:** `XpAttributor` assumed *"experience normally lands on the same tick as the hitsplat or the one after"* and only ever looked **backwards** for a damage pool. The real ordering is the opposite:

```
tick=85   xp=28, xp=9      <- the client pays
tick=86   damage amount=7  <- the hitsplat arrives
```

28 and 9 is a 7-damage hit (4x7=28 to the combat skill, 1.33x7=9.31->9 to Hitpoints), confirming the pairing. With a 4-tick weapon the pool is therefore *always* exactly 3 ticks stale by the time the next drop lands — permanently one tick outside a 2-tick window. **The allocator never allocated anything, ever.** Six of six kills logged `xp=0`; unallocated climbed monotonically.

**Consequence:** The allocator now parks incoming XP and settles it when the damage arrives — own tick, then forward, then backward, with a pool claimable by exactly one XP-arrival tick so a later drop cannot steal the previous one's damage. Two new tests lock the ordering in; a third caught the pool-stealing bug during the fix.

**Do not "fix" this by widening `SETTLE_TICKS`.** Observed: an XP drop at tick 105 whose pool was 3 ticks old at `SETTLE_TICKS = 2`. Bumping it to 3 makes that allocate — to the *previous* hit's damage. With one monster the total survives by luck; with two it silently pays the wrong one, which is strictly worse than the current loud failure.
**Source:** none — measured against the live client, RuneLite 1.12.36.

---

## 2026-08-20 — Kourend Catacombs dagannoths are npc_ids 7257, 7259 and 7260

**Status:** verified
**Method:** `npc_id` on kill and damage lines across two live sessions.
**Finding:** Three distinct "Dagannoth" ids in the Catacombs. Damage totals cluster tightly per id — 7260 consistently 123–124, 7259 consistently 72–73 — which is two different HP pools and independently corroborates that hitsplat accumulation is accurate, since nothing else in the plugin would produce that clustering. The client carries **sixteen** ids named plain "Dagannoth" before counting Mother/Prime/Rex/Supreme, so per-id rows are expected.
**Consequence:** A slayer task shows as multiple panel rows that must be summed before comparing against the in-game task counter. This is `docs/PROJECT.md`'s "store raw npc_id forever, display grouping is a read-time concern" behaving exactly as designed, but it is confusing in the panel and argues for a display-only rollup that leaves the ids intact.
**Source:** `net/runelite/api/NpcID.java`, RuneLite 1.12.36.

---

## 2026-08-20 — `GameStateChanged(LOGGED_IN)` fires on every scene load, not once per login

**Status:** verified
**Method:** Timestamped log lines across a login and several loading zones.
**Finding:** Four `LOGGED_IN` events in 32 seconds during a single login. Two separate bugs rode on the assumption that it means "the player just logged in":

1. `XpService.prime()` ran on it, and the first one arrives **before the client holds skill data**, so `getSkillExperience` returned zero for every skill. A zero baseline is worse than none — the attributor handles a missing baseline correctly by recording it and attributing nothing, while a zero one read the player's entire combat history as one gain: **2,348,432 xp into unallocated on the first kill.**
2. `LocalLedger.load()` ran on it, replacing the in-memory map wholesale and discarding any XP accrued since the last kill saved.

**Consequence:** Seeding moved to the first `GameTick` while logged in, guarded by `isPrimed()` — confirmed in play, one `primed xp baselines` line instead of four, and unallocated now starts near zero. Ledger loading is guarded by a `ledgerLoaded` flag reset on `LOGIN_SCREEN`. The deleted `everykill` code seeded on the first tick and said why in a comment; this is the third regression today where the rewrite lost something the old code had learned in-game.
**Source:** none — measured against the live client.

---

## 2026-08-20 — Zero foreign damage detected in multicombat; the blind spot is pre-engagement, not `isOthers()`

**Status:** unverified-assumption (hypothesis with supporting evidence, test named below)
**Method:** Thirteen live kills in Kourend Catacombs, all graded `EXACT` with `dmg=X/X`, while the game printed *"As an Ironman, you might not receive kill-credit for this monster."* Cross-read against core RuneLite's use of the same API.
**Finding:** `Hitsplat.isOthers()` is not broken. Core's `DpsCounterPlugin` is the only core consumer and it depends on foreign hitsplats arriving — it adds them to the fight total and filters with `actor != player.getInteracting()`. It also broadcasts the local player's own damage over the party service, because `isOthers()` reports *that* someone hit the target, never *who*.

The likely explanation is structural: a combat record opens on the first hitsplat **we witness**, so damage dealt before we engaged is invisible. The ironman warning is pre-emptive — it fires on attacking a target another player already has a claim on, which includes damage that landed before we arrived. So the message and our zero readings are consistent, and the grade was not lying about what it measured; it was measuring "nobody else hit it *after we turned up*" and labelling that `EXACT`.

**Consequence:** `EXACT` currently overstates. Candidate signal is `Actor.getHealthRatio()` / `getHealthScale()` at first contact — an NPC already below full was hurt by someone. Two known obstacles: our own hitsplat may already be reflected by the time `HitsplatApplied` fires, and apportioning the drop needs the monster's max HP, which is the P0 reference data that does not exist. **Diagnostic added** (`First contact:` log line) rather than a guessed fix.

**The test that settles it:** kill in a busy multicombat area and compare `First contact` ratios against `healthScale` and against the ironman messages. If contested targets read below full at first contact and uncontested ones read full or -1, the signal is usable.
**Source:** `DpsCounterPlugin`, `Actor.getHealthRatio()` javadoc, RuneLite 1.12.36.

---

## 2026-08-20 — The ledger silently loaded empty and overwrote 14 kills; a shrink guard now blocks it

**Status:** verified
**Method:** Live session showed `kc=1` for NPCs stored with 11 and 3. Traced through `startUp()` ordering, confirmed against the stored config value before and after client shutdown.
**Finding:** `startUp()` runs **before login**, when no RS profile exists, so `getRSProfileConfiguration` returns null and the ledger loads empty. The `ledgerLoaded` flag added earlier the same day — to stop `load()` clobbering unsaved XP on every scene load — then guaranteed it never reloaded once the profile appeared. The plugin ran the whole session on an empty map and flushed it on shutdown: **7260 went 11 to 1, 7259 went 3 to 1.**

The stored value survived until the client closed, because RuneLite batches config writes. Backed up before the flush, and restored afterwards to 12 and 4 — the pre-loss counts plus the two legitimate kills from that session.

**Consequence:** `startUp()` no longer marks the ledger loaded; the first `LOGGED_IN` does the real load and later scene loads still skip it. Two guards added in `save()`, because *detecting* this was pure luck — nothing in the plugin would have noticed:

- **Refuse to save fewer kills than were loaded.** Within a profile, counts only ever increase; a shrink is a bug every time, and it now logs at `error`.
- **Refuse to save at all if the load threw.** The stored ledger is the only copy, and the old code logged "leave the stored value untouched" without anything enforcing it.

**Wider point:** three regressions in one day came from the rewrite lacking things the deleted code had learned in-game — and this one came from *my own fix* for the previous one. A tracker that quietly writes fewer kills than it read is the exact failure this project exists to avoid, so the guard matters more than the fix.
**Source:** none — traced against the live client and the stored config.

---

## 2026-08-20 — Correction: npc_id 7257 is an Ankou, not a dagannoth (supersedes the id list above)

**Status:** verified (supersedes an earlier entry that was wrong)
**Method:** `First contact: npc_id=7257 name=Ankou` — the name straight off the NPC, in the Catacombs.
**Finding:** The earlier entry "Kourend Catacombs dagannoths are npc_ids 7257, 7259 and 7260" is **wrong on 7257**. That id belongs to an Ankou. 7259 and 7260 remain confirmed dagannoths — both were seen on kill lines carrying `name=Dagannoth`, and their damage totals cluster at 72–73 and 123–124 respectively.

**How the error happened, because the mechanism matters more than the fact:** 7257 was first seen on an `xp damage in:` line, which logs only the id, during a session where every named kill was a dagannoth. I filled in the name from context and recorded it as verified. No name was ever observed. Both Catacombs monsters share a region and the ids sit next to each other, which made the wrong answer look tidy.

**Consequence:** two changes. The id list is corrected. And diagnostic log lines that carry an `npc_id` should carry the name alongside it — an id on its own invites exactly this. The `xp damage in:` line has since been removed, but the rule stands for anything added later.

**Related:** `WORKING-AGREEMENT.md` §3b, "plausibility standing in for verification". This is that failure, committed by the person who had just re-read the section.

---

## 2026-08-20 — XP allocation confirmed working in a live client; Step 5 measurement partially met

**Status:** verified
**Method:** Nine dagannoth kills in Kourend Catacombs with the rewritten allocator, kill lines cross-checked against the published base rate.
**Finding:** After inverting the allocator to park XP and settle it when the damage arrives, **every kill attributed XP to the correct monster and `unallocatedXp` stayed at 0 throughout.** Before the fix it climbed roughly 650 per kill and never allocated anything at all.

Aggregate against a `4 + 1.33 = 5.33` XP-per-damage base:

| npc_id | kills | rolled damage | xp | per point |
|---|---|---|---|---|
| 7259 | 5 | 358 | 1919 | 5.36 |
| 7260 | 3 | 372 | 1939 | 5.21 |

Both straddle base. XP per *rolled* damage should land slightly under, since rolled damage includes overkill the game does not pay for.

**Two reporting artifacts worth knowing before reading a log:**

1. **`xp=` on a kill line is a snapshot taken before the killing blow's XP has landed.** XP arrives a tick before its hitsplat, so the last hit's XP drains after the line is written and shows up in the *next* kill's cumulative total. Per-kill deltas therefore bounce; aggregates are correct. Chasing a per-kill delta as a bug wastes an evening.
2. Early in the session 7259 read 5.52 and 7260 read 5.16, which looked like XP leaking between variants. It was the lag above plus a small sample. The gap closed from 0.36 to 0.15 as N grew. **Recorded because the wrong reading was reached first, twice.**

**Still outstanding for Step 5:** the settle window and noise floor are still unmeasured numbers. `SETTLE_TICKS = 2` has not been varied.
**Source:** none — measured against the live client, RuneLite 1.12.36.

---

## 2026-08-20 — Damage below a monster's max HP is arithmetic proof of unseen damage

**Status:** unverified-assumption (single observation, mechanism is sound, test named below)
**Method:** One kill among nine stood out — `npc_id=7259 dmg=67/67`, where every other kill of that variant took 71–72.
**Finding:** A combat record opens on the first hitsplat **we witness**, so damage dealt before we engaged is invisible and `EXACT` currently means "nobody else hit it after we arrived" rather than "we earned this". Thirteen kills in multicombat produced zero detected foreign damage while the game printed the ironman kill-credit warning at least once.

There may be a way to catch it without ever seeing the other player's hitsplat. **If we deal less total damage than the monster has hitpoints, and it dies, the difference came from somewhere else.** That is conservation, not a heuristic. A 70 HP dagannoth dying to 67 points from us means ~3 points arrived unseen.

**Blockers, both real:**
- It needs each monster's **max HP**, which is exactly the P0 reference table that does not exist. This is the strongest argument yet for building it — it converts contested-kill detection from "witness the splat" to arithmetic.
- Poison, venom and damage from other NPCs are not player hitsplats and would produce the same signature. The signal proves *unseen damage*, not *another player*, so it can only ever justify downgrading to `AMBIGUOUS`, never accusing.

**The test:** with max HP available, compare kills where `totalDamage() < maxHp` against the ironman kill-credit messages. Agreement across a task makes it usable.
**Source:** none — observed in play. The wiki's summarised infobox gave 70 HP for both dagannoth variants, which our own damage contradicts for 7260 (123–124 rolled every kill); prefer the structured Bucket API over page summaries when P0 is built.

---

## 2026-08-21 — Unallocated XP is not a bug signal; teleports produce it constantly

**Status:** verified
**Method:** Two write-offs of exactly 35 XP, both while the player was banking and travelling rather than fighting. Confirmed by the player, then confirmed against the wiki's structured spell data.
**Finding:** `bucket('infobox_spell')` gives Varrock Teleport `"exp":"35"` — an exact match, twice. The allocator was working correctly: Magic XP arrived with no damage on record, and it refused to attribute it to a monster.

**But it invalidates a stated acceptance criterion.** `BUILD-ORDER.md` Step 5 said *"a rising unallocated figure means the allocator is wrong and is the signal to chase"*, and `EverykillPanel` carried the same claim in a comment. Both are false. Every teleport (35), High Alch (65) and Superheat (53) is Magic XP with no monster attached. A player alching at a bank would watch the number climb forever and reasonably conclude the plugin is broken.

**Consequence:** the counter is split. XP that arrives **while damage is on record** and still cannot be placed is `strandedXp` — the real signal, and the only one the panel shows. XP that arrives with no combat in progress goes to `unallocatedXp` and is diagnostics only. The distinction is recorded on arrival, not at write-off, because the damage pools are trimmed by then. Step 5's acceptance criterion is corrected accordingly.

**Wider point:** three of the six combat skills we track are earned outside combat. Any future "this number should be zero" claim needs checking against that first.
**Source:** OSRS Wiki `infobox_spell` bucket, 2026-08-21.

---

## 2026-08-21 — `ActorDeath` is trusted for transform-death monsters again, and it overcounts

**Status:** verified — reproduced eight times in one session
**Method:** Eight rockslug kills in the Fremennik Slayer Dungeon, grades read off the kill log.
**Finding:** The `com.everykill` rewrite deleted `TransformDeathNpcs` and with it the guard that ignored `ActorDeath` for monsters that must be finished with an item. The 2026-08-14 entry established that `ActorDeath` fires for these at health-ratio-zero, well before the NPC is actually dead. Nothing in `KillStateMachine` now knows that.

Eight kills, one graded correctly:

| kc | grade | signal | |
|---|---|---|---|
| 1 | INFERRED | TRANSFORM_FINISH | correct |
| 2,3,5,6,7,8 | EXACT | OBSERVED | `ActorDeath` beat the salt |
| 4 | INFERRED | DESPAWN_WHILE_DEAD | **1 damage on a ~27 HP monster** |

The transform-finish detection itself **works** — kc=1 proves `WIDGET_TARGET_ON_NPC` carries a bag of salt, which had never been tested. It simply loses the race, because we emit the moment `ActorDeath` arrives.

**kc=4 is the serious one.** One point of damage, one attack, no other player's damage seen, on a monster that takes 27. It was already at zero HP when touched. Most likely the same slug as kc=3: emitted once on a false `ActorDeath`, then again on the real despawn nine seconds later — past the ten-tick `EMITTED_TICKS` window. Cannot be proven to be the same actor, but **one damage produced a counted kill either way**.

**The fix, no monster list required.** The 2026-08-14 entry already contains it: *"a real kill means the actor is gone."* Do not emit on `ActorDeath`. Hold the death signal and let the despawn confirm it. A genuine death despawns within a tick or two; a lie leaves the NPC standing, and the flag is dropped.

This collapses two emission points into one, so the double-count becomes structurally impossible rather than something `EMITTED_TICKS` has to be tuned to avoid. It is also generic, so it covers the Kalphite Queen / Zalcano false-`ActorDeath` case that `BUILD-ORDER.md` Step 4 flags as untested and currently unprotected — one mechanism, both problems.

**Second guard, needs P0:** damage far below a monster's max HP should never grade above `AMBIGUOUS`. Third independent argument for the max-HP table in two days.
**Source:** none — reproduced live, RuneLite 1.12.36.

---

## 2026-08-21 — Transform-death detection validated in play, all three cases

**Status:** verified
**Method:** Rockslugs in the Fremennik Slayer Dungeon, three cases run deliberately, outcomes read off the kill log and cross-checked against `First contact` counts.
**Finding:** After moving emission from `ActorDeath` to despawn, adding the revoked-signal guard, and widening `FINISH_WINDOW_TICKS` to 5:

| case | before | after |
|---|---|---|
| Salt after 0 hp | `EXACT` / `OBSERVED` (6 of 8) | `TRANSFORM_FINISH` |
| Salt before 0 hp | **kill lost entirely** | `TRANSFORM_FINISH` |
| Abandoned at 0 hp | **phantom kill recorded** | nothing, correctly |

The abandoned case is the one worth reading twice. The discard log shows why it works:

```
Discarded a finished npc: npc_id=421 itemUsedAt=144 despawnedAt=204
  gap=60 window=5 flaggedDead=true revoked=true
```

**`flaggedDead=true`** — `isDead()` told the same lie it told the previous run, when it produced a phantom kill. `revoked=true` is what stopped it. And `gap=60` is useful calibration: 36 seconds between item-use and despawn is unambiguously "walked away", not "missed the window by two ticks", so 5 is not too tight.

**Objective marker for which case a kill was.** Rockslug max HP is 27 (`infobox_monster`, ids 421/14423). So `dmg=27` means the health bar emptied before the salt landed; `dmg<27` means the salt got there first. Reading this off the log beats asking, and beats guessing — three of the cases run were initially mis-attributed by eye.

**Second monster validating the observed-HP approach.** Wiki says 27; every clean rockslug kill logged 26–27. Same exact agreement as the dagannoths at 70 and 120, from a completely different monster family. `spec-reference-data.md` §4.
**Source:** OSRS Wiki `infobox_monster` bucket; the rest measured live against RuneLite 1.12.36.

---

## 2026-08-21 — `Hitsplat.isOthers()` works; the Catacombs zero was real absence, not blindness

**Status:** verified
**Method:** Six `First contact` lines for cockatrices being killed by another player in the same dungeon, with no kills recorded against them.
**Finding:** `KillDetector.keyFor()` is called for **any** hitsplat, ours or another player's, so a `First contact` line does not mean we hit anything. Those six were a stranger's kills, correctly watched and correctly not counted.

The useful part is what it proves: **we do receive other players' hitsplats.** The earlier Catacombs session logged thirteen kills with zero foreign damage while the game printed the ironman kill-credit warning, and it was not possible to tell whether the `AMBIGUOUS` path was broken or simply unexercised. It is unexercised. The detection fires.

**Consequence:** the remaining gap is confirmed to be the pre-engagement blind spot and nothing else — damage dealt before our record opened, which no hitsplat subscription can ever see. That is what the max-HP arithmetic in `spec-reference-data.md` §1 is for, and it is now the only known route to it.

**Instrumentation fixed:** the log line now prints `by=us` or `by=other`. It read as six missed kills for several minutes before the code path was checked.
**Source:** none — observed live.

---

## 2026-08-21 — P0 reference table pulled: 4,325 npc ids, and it agrees with everything we measured

**Status:** verified
**Method:** `tools/fetch-reference-data.sh` against the wiki's Bucket API, cross-checked against six monsters whose damage we recorded in play.
**Finding:** The whole `infobox_monster` table comes back in **one request** — 3,233 rows expanding to **4,325 distinct npc ids**, 288KB, 0.9 seconds. `offset()` works if paging is ever needed. It isn't.

Coverage: **15 ids missing hitpoints (0.3%)**, 875 missing experience bonus (20%).

**It matches our own data on every monster we have measured**, with overkill always positive and small — 70/71–75, 120/123–126, 27/26–27, 60/60, 22/23, 22/22. Two independent sources, six monsters, no disagreement. That is the max-HP arithmetic in §1 of `spec-reference-data.md` becoming usable rather than theoretical.

**Three things the raw data hides:**

- **`id` carries non-numeric entries** (`beta14278`, `hist3011`) — wiki revision refs, not npc ids. Nothing in a live client matches them.
- **Namespace pages leak into the bucket** (`RuneScape:Templates`), with no hitpoints and no ids.
- **875 monsters have no `experience_bonus` field at all.** `GAME-MECHANICS.md` warned this is editor-entered and may be unfilled; this is that, measured. Treating absent as zero would silently hand 875 monsters a multiplier they were never assigned.

**Consequence:** the puller is committed, the data is not. Wiki content is CC BY-NC-SA — non-commercial, share-alike — and committing it into a BSD repo would be redistributing it. `data/` is gitignored and regenerated on demand. The long-term intent remains deriving max HP from our own kill logs, where no licence applies at all.

**Performance note worth keeping:** the first version ran grep and sed per row and took over five minutes on Windows, where spawning ~19,000 processes was almost the entire runtime. One awk pass does it in under a second. Same output.
**Source:** OSRS Wiki Bucket API, `infobox_monster`, 2026-08-21.

## 2026-08-21 — the client's top grade is `UNCONTESTED`, not `EXACT`

**Status:** decided, shipped.

`Confidence.EXACT` was renamed to `Confidence.UNCONTESTED` across the plugin. Nothing
about detection changed — same conditions, same counts — only the claim we make about
them.

A combat record opens on the first hitsplat *we witness*. Damage dealt before we
engaged is invisible to us, so the strongest honest statement the client can make is
"nobody else hit it after we turned up". `EXACT` read as "we earned this kill", which
is a different and unverifiable claim. Thirteen multicombat kills on 2026-08-20 showed
zero foreign damage while the game printed the ironman kill-credit warning — the grade
was wrong and looked fine.

`EXACT` is now reserved for the server, where max HP makes conservation checkable.

`NpcStat.uncontested` and `NpcStat.DayTally.uncontested` carry
`@SerializedName(value = "uncontested", alternate = {"exact"})`, so ledgers written
before today still load with their counts intact. Verified: 41/41 tests pass, clean
compile against RuneLite 1.12.35.

Decision recorded in `docs/spec-reference-data.md` §5.

## 2026-08-21 — reference table predicted Watchman's max HP correctly

**Status:** verified in a live client.

First live test of the P0 puller against a monster we had *not* previously measured,
so this is a prediction rather than a fit.

`data/monsters.tsv` row: `5420  Watchman  hitpoints=22  combat_level=33  experience_bonus=0`

Two kills, both `grade=UNCONTESTED signal=OBSERVED`, both `dmg=22/22`:

```
21:33:56  kc=1 attacks=3 hits=2 maxHit=14 xp=117
21:35:06  kc=2 attacks=4 hits=3 maxHit=14 xp=234 (cumulative, 117/kill)
```

Total damage to kill matched the table's hitpoints exactly, twice. The npc_id ↔ wiki
bridge resolves correctly for a monster nobody hand-checked.

XP also reconciles against the documented rates (GAME-MECHANICS.md): 22 damage x 4 =
88 to the combat style, 22 x 1.33 = 29.3 Hitpoints, total 117.3 -> 117 measured.

Incidental: `unallocatedXp` sat at 68 across both kills and did not grow per kill,
which is the benign bucket behaving as designed — a login-time XP drop with no combat
to attach it to, not a per-kill allocation leak.

## 2026-08-21 — Lesser demon takes 81 damage, wiki says 79

**Status:** verified in a live client, three samples. Wiki disagrees with the game.

Three kills, three different npc_ids, all in region 5789, all `dmg=81/81` with no
foreign damage:

```
7656  attacks=13  33s  xp=432
7664  attacks=8   20s  xp=431
7657  attacks=11  ~28s xp=432
```

`data/monsters.tsv` lists 79 hitpoints for all three ids (level 82). Fetched
https://oldschool.runescape.wiki/w/Lesser_demon directly on 2026-08-21 — the wiki
itself says 79 for the level-82 variant, so the puller copied it faithfully. The
disagreement is wiki-vs-game, not puller-vs-wiki.

**Measured XP independently corroborates 81, which is the part that settles it.** XP is
paid per damage point, so it is a second channel that does not share a failure mode with
the hitsplat counter:

- 81 damage -> 81x4 + 81x1.33 = 431.7 -> 431/432. **Matches all three kills.**
- 79 damage -> 316 + 105.1 = 421.1 -> 421. Matches none of them.

If our damage counter were over-reading by 2, XP would still have reported 79 damage
worth. It didn't. The game paid for 81.

**Regeneration does not explain the 79 -> 81 gap.** The fastest kill (20s, 8 attacks)
already needed 81, so the base is 81 regardless of regen. See the superseding note
below — regen is real, it just is not the cause of this gap.

Prediction made before the first kill was 79 damage / 421 xp, from the table. It was
wrong, and observation corrected it. This is the first time the observed pipeline has
caught the reference data being wrong, and it is the argument for the provenance
tagging in spec-reference-data.md §2 — a silent merge would have buried this.

Not yet established: whether 79 is stale, whether these ids are mis-mapped on the wiki,
or whether something about region 5789 alters the variant. Recording the observation,
not the explanation.

## 2026-08-21 — DEATH_CONFIRM_TICKS=5 may be downgrading honest kills

**Status:** strong pattern, cause not yet measured. Needs instrumentation before tuning.

Same session, ten kills, a clean split by monster:

| Monster | Kills | Signal | Grade |
|---|---|---|---|
| Watchman (5420) | 5 | `OBSERVED` | `UNCONTESTED` |
| Knight of Ardougne (3297, 11936) | 2 | `OBSERVED` | `UNCONTESTED` |
| Lesser demon (7656, 7657, 7664) | 3 | `DESPAWN_WHILE_DEAD` | `INFERRED` |

All three demon kills were solo with `dmg=81/81` — zero foreign damage. They meet every
condition for `UNCONTESTED` and are being graded down anyway.

`DESPAWN_WHILE_DEAD` is the third branch of `KillStateMachine.despawn()`: no death
signal was held, but `isDead()` was true. So either `ActorDeath` never fired for these,
or it fired more than `DEATH_CONFIRM_TICKS` (5) before the despawn and `tick()` revoked
it as stale.

Plausible cause is a longer death animation on demons than on humanoids, but that is a
hypothesis and has not been measured. **Do not tune the constant off this entry.** The
next step is a temp debug line recording the actual death-signal-to-despawn gap per
kill, gathered across several monster types, and then set the constant from the
distribution.

This is the same failure shape as the FINISH_WINDOW_TICKS episode: a tight window
chosen at a desk, costing real data in the field. Bias the fix toward too generous.

## 2026-08-21 — NPC hitpoint regen is real, and it breaks observed max-HP as an estimator

**Status:** mechanic confirmed against the wiki; rate is undocumented. Supersedes the
"regeneration ruled out" line in the Lesser demon entry above, which was over-stated.

A sixth Lesser demon (7664) took **82** damage, not 81 — and it was by far the longest
fight of the set:

| Attacks | Duration | Damage |
|---|---|---|
| 8 | 20s | 81 |
| 9 | ~20s | 81 |
| 11 | ~28s | 81 |
| 13 | 33s | 81 |
| 20 | 49s | **82** |

Measured XP corroborates 82 independently: this kill paid 1300 - 863 = 437, and
82x4 + 82x1.33 = 437.1. The 81-damage kills paid 431/432.

https://oldschool.runescape.wiki/w/Monster (fetched 2026-08-21) confirms monsters
regenerate hitpoints, describes the rate only as "fairly slowly", and notes some
monsters (Banshees) restore faster. **No numeric rate is published.**

**What this changes:**

1. **Total observed damage is an upper bound on max HP, not a measurement.** It drifts
   up with fight duration, and the bias is worst for monsters players kill slowly —
   exactly the ones where a good number matters most.
2. **Estimate max HP from the MINIMUM total damage across kills, never the mean.** The
   fastest kill leaks the least regen. A mean would bake the bias in permanently.
3. **The integrity check is untouched.** Conservation is used in one direction only:
   *dealt less than max HP, therefore someone else contributed*. Regen only pushes
   observed damage up, so it can never fabricate that accusation. The estimator is
   affected; fraud detection is not.
4. The 79-vs-81 disagreement stands. Minimum observed is 81, still above the table.

Do not attempt to model the regen rate to correct for it. It is undocumented, varies by
monster, and the minimum-based estimator sidesteps it entirely without needing a number.

## 2026-08-21 — XP attribution runs ~0.25% high; mid-fight non-combat XP lands on the monster

**Status:** measured, mechanism plausible but not yet proven. Needs a per-kill XP
diagnostic to confirm.

Nine Lesser demon kills across three npc_ids, damage totals from the kill lines:

| npc_id | Kills | Damage | XP measured | XP expected | Delta |
|---|---|---|---|---|---|
| 7664 | 4 | 325 | 1732 | 1732 | 0 |
| 7657 | 2 | 162 | 863 | 863 | 0 |
| 7656 | 3 | 244 | 1311 | ~1300 | **+11** |

Aggregate: 731 damage, ~3896-3899 expected (the range is whether Hitpoints pays 1.33 or
4/3 — GAME-MECHANICS.md already flags that rounding as undocumented), **3906 measured.
Roughly +7 to +10.**

Two of three ids reconcile exactly over multiple kills, which rules out a systematic
per-damage rate error and rules out backward-allocation smearing between kills of the
same monster. The excess is concentrated and real.

**Likely mechanism.** `XpAttributor.settle()` writes XP off as `unallocatedXp` only when
it arrived *outside* combat (`p.duringCombat == false`). XP that arrives while a fight is
in progress is allocated to the monster being fought. So non-combat XP earned mid-fight —
bones buried, anything incidental — is folded into that monster's XP total.
`unallocatedXp` sat frozen at 129 across all nine kills, meaning nothing was written off,
which is consistent: it all found a home, whether or not that home was correct.

**This is a design characteristic, not obviously a bug.** The alternative — refusing to
allocate XP during combat unless it matches expected damage output — would need an
expected-XP model per style, which is exactly the "derive XP from damage" approach that
was already rejected (see the Step 5 rewrite). Biasing slightly high and being honest
about it may be the right trade.

**What it does NOT affect:** kill counts, grades, damage totals, max-HP estimation. Only
the per-monster XP figure, by a fraction of a percent.

**Next step before acting:** log allocated XP per kill directly rather than inferring it
by subtracting cumulative totals, and log which skill each allocation came from. That
distinguishes "incidental XP folded in" from "combat XP misrouted between concurrently
fought monsters", which have different fixes.

## 2026-08-21 — RuneLite's own HP display is wiki-derived, so it can never corroborate us

**Status:** verified by reading core's source. Important negative result.

The in-game opponent health bar showed `Lesser demon 7/79` during the session where we
measured 81-83 damage per kill. That looks like independent confirmation of the wiki's
79. **It is not.**

`OpponentInfoOverlay.java` line 125:

```java
lastMaxHealth = npcManager.getHealth(((NPC) opponent).getId());
```

Core reads max HP from `npcManager`, RuneLite's bundled NPC stats table, which is
wiki-derived and keyed by npc id — the same lineage `data/monsters.tsv` came from. The
79 on screen and the 79 in our table are one number quoted twice.

**Rule that follows: never treat a RuneLite core display, or any plugin built on
`npcManager`, as an independent check on our reference data.** Anything sourced from
`npcManager` shares our failure mode. This also applies to Monster Stats, listed as a
possible Step 0a shortcut in PROJECT.md — worth knowing before leaning on it.

**A genuinely independent test does exist**, from the server formula core documents at
line 170:

```
healthRatio = 1 + (healthScale - 1) * health / maxHealth     (health > 0; 0 otherwise)
```

`healthScale` is a display scale, **not** max HP — an earlier proposal to "just log
healthScale" was wrong and would have proved nothing. But after we have dealt known
damage `d`, `health = maxHealth - d`, leaving one equation in one unknown. Log
`healthRatio` and `healthScale` next to our cumulative damage at two points mid-fight,
solve for `maxHealth`, and the answer comes from server data with no wiki anywhere in
the chain.

That is the experiment that should settle the 79-vs-81 question. Core's own comment
notes exact health is recoverable when `maxHealth <= healthScale`, so check that
condition holds for the monster under test before trusting a point estimate.

## 2026-08-21 — regen demonstrated: damage-to-kill steps up monotonically with fight length

**Status:** demonstrated, n=17, single monster. The cleanest empirical result of the
session. Confirms the minimum-based max-HP estimator.

Seventeen Lesser demon kills (npc_ids 7656/7657/7664, region 5789), all solo, all with
`dmg=N/N` and no foreign damage. Total damage required, grouped by fight length:

```
attacks   damage observed
   8    : 81
   9    : 81
  11    : 81 81 81 81
  13    : 81 81
  14    : 82 82
  15    : 82
  16    : 82
  17    : 82 83
  18    : 82
  20    : 82
  21    : 83

damage  count
  81      8
  82      7
  83      2
```

**No overlap between the <=13-attack group (always 81) and the >=14-attack group (never
81).** A monotonic step, not scatter. Longer fights require strictly more damage.

**Base max HP is 81, and this is a plateau rather than a ramp.** Damage is flat at 81
across 8, 9, 11 and 13 attacks. If regeneration accrued from the first tick, the
8-attack kills would have needed less than the 13-attack kills. They needed the same.
So 81 is the starting hitpoints and everything above it is regen banked during the
fight.

**Consequences:**

1. **Confirms the minimum estimator empirically**, not just by argument. Mean of this
   set is 81.6 and would drift further up for slower players; minimum is 81 and is
   stable. Averaging would have recorded this monster's max HP wrong in a way that gets
   *worse* as more data arrives.
2. **Strengthens the 79 disagreement.** The fastest kills in the set still needed 81.
   There is no fight short enough in this data to reach 79.
3. **A per-monster max-HP estimate needs fast kills, not many kills.** Sample quality
   beats sample size here. Worth remembering when designing what the site aggregates.

**Not established:** the regen rate. Do not derive one from this table — attack speed,
weapon, and the player's setup are all uncontrolled, and the wiki states some monsters
regenerate faster than others. The minimum estimator does not need the rate.

## 2026-08-21 — CORRECTION: the "no overlap" claim above was over-claimed

**Status:** supersedes the grouping in the previous entry. The conclusions survive; the
phrasing did not.

The entry above claimed "no overlap between the <=13-attack group (always 81) and the
>=14-attack group (never 81)". **The very next kill was 14 attacks and 81 damage.** That
claim was made on n=17 and falsified within two minutes. Recorded here rather than
edited away.

**Attack count was the wrong axis.** Re-running against elapsed wall-clock time (paired
from the First contact and Kill log lines) gives a far cleaner picture:

```
damage : elapsed seconds observed
  81   : 20 23 27 27 28 28 33 33 35
  82   : 35 35 38 40 46 49 [183]
  83   : 42 52
```

Threshold is around **35 seconds** for the first +1, with a single overlap point exactly
at 35s where both values appear — the expected shape at a real boundary. The kill that
falsified the claim took 35s: on the line. So the effect is real and the proxy was bad.

**What still stands, unchanged:**

- Minimum observed damage is **81**, stable across nine kills from 20s to 35s. Base max
  HP is 81.
- Longer fights require strictly more damage. Regeneration is real.
- The minimum estimator is correct, and fast kills are what a good estimate needs.
- The disagreement with the table's 79 is untouched.

**Two caveats, stated rather than buried:**

1. **The 183s / 82-damage point does not fit any steady-regen model.** Most likely an
   artifact of the analysis, not the data: several demons share an npc_id and were
   fought concurrently, so pairing a kill to "the most recent First contact with that
   id" can mismatch. A limitation of the log format. Per-kill start ticks in the kill
   line would remove the ambiguity.
2. **The second step (+2 at 42-52s) does not follow the first (+1 at ~35s) at a
   consistent rate.** If the regen clock free-runs rather than resetting when combat
   starts, catching a tick depends on phase as well as duration, which would explain
   both the boundary overlap and the irregular spacing. **Hypothesis, not a result.**

**Method note for next time:** do not report a clean separation from a first pass on a
convenience sample. Pick the axis with a physical justification (elapsed time, since
regen is a clock) before grouping, not after seeing which one looks tidier.

## 2026-08-21 — CORRECTION 2: minimum observed damage fell to 80; "base is 81" was wrong

**Status:** supersedes the "base max HP is 81 / plateau not a ramp" conclusion in the
regen entries above. The 79-vs-81 disagreement is now in doubt.

A Lesser demon (7657) died on **80** damage after a **21-second** fight. The previous
fastest kill was 20 seconds and required 81. Same duration, different requirement.

XP corroborates 80 independently: the kill paid 2593 - 2167 = **426**, and
80 x 5.333 = 426.7. Not a counting error.

**What this breaks.** The earlier entry argued that damage sitting flat at 81 across
20-35s made 81 a *plateau* rather than the bottom of a ramp, and concluded base max HP
was 81. That reasoning fails: at ~20 seconds we have now seen both 81 and 80. The flat
region was an artifact of too few fast kills, not evidence of a floor.

The free-running-clock hypothesis — recorded as a caveat in the previous correction and
under-weighted — explains it. If the regen timer is not reset by combat, a short fight
banks 0 or 1 ticks depending on **phase**, not just duration. Then even the fastest
kills scatter, and the observed minimum descends slowly toward the true value as samples
accumulate.

**What this means for the estimator.**

1. **The minimum has not converged.** It went 81 -> 80 at n=21 and may fall further.
   An estimator still moving is not an answer. Any max-HP figure must carry its sample
   count and be treated as an upper bound until it stops moving.
2. **"Fast kills, not many kills" was wrong** — it needs both. Phase-dependence means no
   single fast kill is clean; only the minimum over many is.
3. **The 79 disagreement is now in doubt and may evaporate.** Observed minimum is 80,
   one above the table. If it keeps walking down, the wiki was right the whole time and
   every "the table is wrong" entry above was measuring regeneration.

**Do not treat the 79-vs-81 entry as settled.** It is superseded to "unresolved, trending
toward the wiki being correct".

**The lesson, since this is the second correction in an hour on the same data.** Both
errors were the same mistake: reading structure into a convenience sample and committing
it before the sample could contradict it. Damage-to-kill is a *noisy upper bound* on max
HP, and no amount of staring at 20 of them substitutes for the direct measurement — the
healthRatio/healthScale solve recorded earlier, which reads the server's own number and
needs no minimum at all. Prefer the direct measurement. Stop inferring max HP from
damage totals.

## 2026-08-21 — SOLVED: the INFERRED grade was DEATH_CONFIRM_TICKS missing by one tick

**Status:** measured, fixed, covered by a regression test.

Instrumented `ActorDeath` (fired-or-not, keyed-or-not) and the `DESPAWN_WHILE_DEAD`
fallback. Five lesser demon kills:

```
deathAt=78  tick=84   gap=6
deathAt=221 tick=227  gap=6
deathAt=267 tick=273  gap=6
deathAt=332 tick=338  gap=6
deathAt=467 tick=473  gap=6
```

`DEATH_CONFIRM_TICKS` was **5**. A lesser demon's death animation is **6**. Every clean
solo demon kill missed `OBSERVED` by one tick and was written down `INFERRED` — 31 of
them across the previous session.

`revoked=false` in every case, which is why they landed in `DESPAWN_WHILE_DEAD` rather
than being discarded: the despawn is processed *during* the tick, before `onGameTick`
runs the revocation sweep for that same tick.

**Two wrong turns worth recording**, because both were confident and both were wrong:

1. First hypothesis was "window too tight" — correct, but abandoned.
2. Then, reading the code, concluded `ActorDeath` must never fire for these NPCs, since
   a late signal should have been revoked and fallen through to discarded. Reasoned from
   source, stated it, and it was wrong. `ActorDeath` fires with `keyed=true` every time.
   The intra-tick ordering above is what the code-reading missed.

**The instrumentation settled in one kill what two rounds of reasoning got backwards.**
When a question is empirical, measure it.

**Fix:** `DEATH_CONFIRM_TICKS` 5 -> 20. The timer is the right mechanism — the case it
guards against is the rockslug, left at 0 hp and abandoned, despawning when the player
wanders off, which is *hundreds* of ticks. Six versus hundreds is an order of magnitude
of daylight on both sides. It was simply set about three times too tight.

**Regression test added:** `aSixTickDeathAnimationStillGradesObserved`. Verified it
actually bites — fails at the old value of 5, passes at 20. No unit test could have
*found* this bug (the logic was correct; the constant disagreed with the game), but one
can stop it coming back.

**Third time this project has been bitten by a tight timing window** (FINISH_WINDOW_TICKS
cost a whole kill, EMITTED_TICKS was undersized, now this). Treat any hand-picked tick
window in this codebase as suspect until it has been measured in a client, and bias
generous — every one of these failures cost real data, and none of the loosenings has
cost anything yet.

## 2026-08-21 — max-HP solve: works sometimes, unexplained otherwise. Parked.

**Status:** inconclusive. Method is sound, data is not yet good enough. Do not act on
the numbers below.

Instrumented `healthRatio`/`healthScale` per hitsplat and solved the server formula for
`maxHealth`. Tagged by actor key, so each fight is cleanly separated (an earlier
npc_id-tagged version interleaved same-id demons and fitted nonsense — 85 hp for
something we have killed with 80 damage).

Results across four Lesser demon fights:

```
key  npc_id  samples  totaldmg  feasible maxHealth
1    7664    11       81        NONE
2    7656    16       82        81-82
3    7664    14       82        NONE
4    7656    13       81        NONE
```

**One fight solves cleanly (81-82, consistent with an earlier hand-solve of 82). Three
admit no value at all.**

**The failures are not regeneration.** Inspecting key 1 directly:

```
D=17  ratio=24  -> requires M >= 83
D=57  ratio=9   -> requires M <= 82
```

An *early* sample demands a larger max HP and a *late* one demands smaller. Regen
inflates health as a fight proceeds, so it would make late samples demand the larger
value. This is the opposite.

**Two pairing theories tried, both failed:**

1. Ratio pairs with cumulative damage before that hitsplat. (Original. Fits some fights.)
2. Ratio pairs with damage through the end of the previous tick, since health updates
   per tick and several splats share a tick. (No improvement.)

**Leading hypothesis, untested:** `healthRatio` is a client-side value refreshed only
when the server sends an update, and the server may not send one every tick. If the
ratio can be stale by an arbitrary number of ticks, **no fixed pairing offset can ever
work**, which is precisely the pattern observed — some fights fit, others don't, with no
consistent offset between them.

**Next step if this is picked up again:** sample `healthRatio` on every `GameTick`
rather than on hitsplats, and log it even when it does not change. That shows directly
how often the server actually updates it, and whether staleness is the explanation.
Until then the method cannot be trusted and the wiki-vs-observed question stays open.

**Do not use these numbers.** One clean fit is not a result.

## 2026-08-21 — the one max-HP number the solve produced is falsified

**Status:** the solve is not merely inconclusive, it is demonstrably wrong. Strengthens
the parked entry above.

The solve's single clean fit was **82** for npc_id 7656 (16 samples, unique fit, also
reproduced by hand across 12 samples).

At 22:23:17 a **7656 died to 80 total damage** — `grade=UNCONTESTED signal=OBSERVED
dmg=80/80`, a clean 9-attack solo kill.

A monster with 82 hitpoints cannot die to 80 damage. The fit was wrong despite being
unique, internally consistent, and matching a careful hand calculation.

**Worth sitting with.** A model that fits every sample perfectly and admits exactly one
solution still produced a false answer, because the *input pairing* was wrong in a way
none of the samples could reveal. Uniqueness of fit is not evidence of correctness when
the systematic error is in how the data was assembled.

The cheap external check — "is this consistent with the damage we have actually killed
one with?" — caught in one kill what 16 perfectly-fitting samples could not. Keep that
cross-check on any future max-HP estimate.

## 2026-08-21 — use core's NpcUtil.isDying(). Stop tuning windows.

**Status:** fixed, three regression tests, verified to fail without the fix. Confirmed
false positive in a client first.

**The bug.** A rockslug was recorded `grade=UNCONTESTED signal=OBSERVED kc=16` while
standing in front of the player at 0 hp taking hits. Confirmed live — the player went
back and hit it for 5.

**Why no timer could fix it.** A real death and an abandoned rockslug produce the
identical observable sequence: `ActorDeath`, then a despawn ~6 ticks later. Identical in
the only dimension a clock can measure. Earlier the same evening `DEATH_CONFIRM_TICKS`
was tightened (downgrading 31 honest demon kills) and then loosened to 20 (promoting
this fake one from INFERRED to UNCONTESTED). **Both directions were wrong because the
constant was never the right lever.**

**What core does** — found by reading `runelite-client`, which should have happened
before the first constant was touched:

- `LootManager` does **not** subscribe to `ActorDeath` at all. Only two core plugins do,
  and neither for NPC kills.
- `NpcUtil.isDying(npc)` carries a curated switch of NPCs that "hit 0hp but don't
  actually die" — gargoyles, rockslugs, lizards, zygomites — returning **false** for
  them.
- It also reads `RuntimeConfig`: `ignoreDeadNpcs`, `forceDeadNpcs`,
  `healthCheckDeadNpcs`, pushed from RuneLite's servers, so **new content is patched
  without a client release**.

**Fix.** `NpcUtil` is `@Singleton`/`@Inject`, so we just use it:

- `KillStateMachine.death()` takes a `dying` flag and records nothing when false. A
  rockslug can then only ever be counted through a finishing action.
- The despawn passes `npcUtil.isDying(npc)` instead of `npc.isDead()` — `isDead()` *is*
  the zero-health-ratio check, so it tells the same lie.

The gate lives in the state machine, not the adapter, so it is testable without a
client. That is the whole point of the client-free split.

`DEATH_CONFIRM_TICKS` stays at 20. It was the right fix for the demons and was never
implicated here.

**What this deletes from the plan.** `TransformDeathNpcs.IDS`, which
spec-kill-detection.md warns is "silent and dangerous" when it gets stale. **Do not
build it.** Core maintains the list and ships runtime overrides. Rebuilding it would
mean owning a list that goes stale on every content update, to be worse than a
dependency we already have.

**Compliance note:** `NpcUtil` subscribes to `AnimationChanged` internally. Our rule is
that *we* do not subscribe to NPC animations; calling a core utility that does is not
the same thing, and core's own loot tracker depends on it. Flagged so it is a known
decision rather than a submission surprise.

**The lesson, and it cost most of an evening.** Two constants tuned in opposite
directions on the same problem, both wrong, before anyone read how the reference
implementation solves it. **When a fix requires guessing a number, check whether the
problem has a known solution first.** The prior-art table in PROJECT.md exists for this
and was not consulted.

## 2026-08-21 — the game tells ironmen when a kill was contested. Free ground truth.

**Status:** observed, not yet exploited. Recorded so the idea survives whether or not
the temporary logging stays.

On an ironman account the game prints a kill-credit message when another player damaged
the monster you were fighting. That is **the game itself telling us a kill was
contested** — the exact thing `AMBIGUOUS` exists to detect, from an authoritative source
rather than our own hitsplat bookkeeping.

Why it matters: a combat record opens on the first hitsplat *we* witness, so foreign
damage dealt before we engaged is invisible to us. On 2026-08-20 thirteen multicombat
kills produced zero detected foreign damage while the game printed this warning. Our
detection missed every one; the game caught all of them.

Two possible uses, in increasing order of ambition:

1. **Measure our miss rate.** Count how often the game reports a contest that our
   hitsplat tracking did not. That number is a direct, honest measure of how much
   `UNCONTESTED` overclaims, and it is currently unknown.
2. **Use it as a signal.** Downgrade a kill to `AMBIGUOUS` when the game says it was
   contested, regardless of what our own damage tracking saw.

**Constraints if this is built:** ironman-only, so it can never be the primary mechanism
- it would produce systematically different grades for ironmen than for mains, which is
worse than a uniformly conservative rule. Read the message only; never parse or store
the other player's name (PROJECT.md rule 3 - recording *that* foreign damage occurred is
fine, recording *who* is not).

---

## 2026-08-22 — the multiplier table has no consumer: Step 0a's compute-and-cross-check half is cancelled

**Status:** verified (source read + doc audit)
**Method:** Grepped the whole tree for `multiplier`, `experience_bonus`, `xpBonus`, `XP_MODIFIER`, `XpModel`, `divergence`, `checksum` across `src/main` and `src/test`. Then audited every doc mentioning the multiplier.
**Finding:** **Nothing in the client computes, reads, or stores an XP multiplier. Zero matches in src, zero in tests.** `XpModel` — the divergence checksum the specs describe — does not exist as code either. Step 5 measures XP from `StatChanged` and its own text says "**Requires:** nothing. That is the point." Meanwhile Step 0a still carried "compute XP multipliers" plus an acceptance criterion to diff our computed table against `InstantDamageCalculator`'s ~150 entries.

Two documents already disagreed about this and nobody noticed: `spec-reference-data.md` says **"`experience_bonus` exists... read it, never compute it"**, while `BUILD-ORDER.md` Step 0a said compute it. The wiki publishes `experience_bonus` directly for **3,362 of 4,124 ids (81.5%)**, so the formula is only needed for the 18.5% the wiki leaves blank — and no shipped code wants the answer for any of them.

The Vorkath disagreement recorded on 2026-08-16 (formula computes +20%, wiki lists +0%) is a real contradiction but a **moot** one: under measured XP it changes no number the plugin reports.

**Consequence:** Step 0a's multiplier half cancelled and the IDC cross-check dropped, both recorded in place rather than deleted. Step 0a's remaining job is the npc↔wiki bridge and max HP, which is done. **This does not resurrect derivation** — see the 2026-08-16 entry; XP stays measured. If the companion website ever publishes a rate that needs a multiplier, reinstate the cross-check then and re-read `docs/LICENSING.md` first, because the licence position on `XP_MODIFIERS` is unchanged.
**Source:** none — direct source read of this repo.

---

## 2026-08-22 — 87 npc_ids carry two different monsters; the puller was silently emitting both

**Status:** verified
**Method:** Parsed `data/monsters.raw.json` in Python, expanded every `id[]` array, and grouped by npc_id looking for rows whose `(name, hitpoints, combat_level, experience_bonus)` disagreed. Cross-checked the result against a live re-run of the fixed script.
**Finding:** The TSV had **4,957 data rows for 4,124 unique npc_ids** — 742 ids repeated, and **87 of those repeats genuinely disagree**. Same npc_id, different stats, because the wiki stores difficulty and party-size variants as separate infobox entries that share an id. Example: `7566` Vasa Nistirio is both `hp=300, bonus=7.5` and `hp=450, bonus=10`.

Not raids-only, which is what made it worth checking rather than dismissing: the list includes **Cerberus, King Black Dragon, the three Dagannoth Kings, Vardorvis, TzTok-Jad, Scurrius**. All 87 differ in `hitpoints`; 17 also differ in `experience_bonus`.

Any consumer doing a naive `npc_id -> row` map got **whichever row happened to land last** — an arbitrary pick with no warning. Collapsing on "highest hitpoints wins" resolves 86 of 87, but that is a guess dressed as a rule, and `GAME-MECHANICS.md` is explicit that unclear sources degrade rather than guess. The single leftover is `13661` TzTok-Jad, where two entries agree on HP and bonus but list combat levels 1758 and 1527.

**Consequence:** `tools/fetch-reference-data.sh` gained a second awk pass that emits **one row per npc_id** and adds an `ambiguous` column. Exact duplicate rows collapse for free. Where rows disagree the differing fields are **blanked and the row flagged**, per field rather than per row — so Vespula keeps `experience_bonus=0` (both variants agree) while its disputed max HP goes blank. Re-run confirms 4,124 rows, 87 flagged, 0.9s. **The two counts a reader now gets are honest:** 102 ids with unknown max HP (was 15 before the disputed ones stopped resolving arbitrarily) and 762 with unknown XP bonus.

Also corrected: BUILD-ORDER claimed **4,325 npc ids**. The real figure is **4,124**. The old number appears to have counted raw rows before the namespace and non-numeric-id filters, not distinct ids.
**Source:** oldschool.runescape.wiki Bucket API, `infobox_monster`.

---

## 2026-08-22 — the Bucket API has no monster defence bonuses, which blocks Step 0c

**Status:** verified
**Method:** Probed ~55 candidate field names against `bucket('infobox_monster')` one at a time, then read the raw wikitext of `Abyssal demon` via `action=parse&prop=wikitext` to confirm the fields exist on the page but not in the bucket. Also probed `infobox_bonuses`.
**Finding:** `infobox_monster` exposes a **curated subset** of the infobox, not the whole thing. Available and confirmed: `attack_level`, `strength_level`, `defence_level`, `magic_level`, `ranged_level`, `attack_bonus`, `strength_bonus`, `magic_damage_bonus`, `max_hit`, `attack_speed`, `attack_style`, `slayer_level`, `slayer_experience`, `size`, `poisonous`, `examine`.

**Not available:** every defence bonus. `dstab`, `dslash`, `dcrush`, `dmagic`, `dlight`, `dstandard`, `dheavy` — all present in the page wikitext (Abyssal demon: `dstab = 20`, `dslash = 20`, `dcrush = 20`, `dmagic = 0`) and all rejected by the bucket with "Field not found". Neither do the `attbns`/`strbns`/`mbns`/`rngbns` short forms; the bucket renames those to `attack_bonus`/`strength_bonus`/`magic_damage_bonus` and simply drops the rest. `infobox_bonuses` exists but is the **equipment** bucket, keyed on `page_name` — `Helm of Neitiznot`, not monsters. There is no bucket-listing endpoint (`list=bucketbuckets` and `.schema()` both fail), so field discovery is probe-only.

**Consequence:** Step 0c cannot compute `AverageDefBonus`, so the DPS half of the combat formula has no structured input. Recorded in BUILD-ORDER Step 0c with three options and an explicit instruction to choose before starting. **This also independently kills any plan to compute the XP multiplier ourselves** — that formula needs `AverageDefBonus` too, so even for the 762 ids where the wiki leaves `experience_bonus` blank, the fallback was never available. Reading the published value is not just preferred, it is the only option.
**Source:** oldschool.runescape.wiki `api.php`, `action=bucket` and `action=parse`.

---

## 2026-08-22 — Step 4's carry-forward branch executed for the first time, nine times, on Zulrah

**Status:** verified
**Method:** Live dev client, main account, first-ever Zulrah attempt (KC 0 going in). Temporary debug logging on both arms of `KillStateMachine.composition()`, added the same day for exactly this. Ended in a player death at 338 damage dealt.
**Finding:** **The record-gated carry-forward branch had never executed once in this project's history. It ran nine times in one fight.** Zulrah's dive is an `NpcChanged`, not a despawn/respawn — `2042 -> 2043 -> 2044` cycling, with **zero** despawn discards until the fight ended. That question was open and is now measured rather than reasoned about.

Damage carried across every transition on one unbroken record: **66 → 116 → 116 → 129 → 175 → 178 → 270 → 287 → 289**, ending at 338. `othersDamage=0` throughout (solo boss).

**`deathAt=-1` on all nine transitions.** BUILD-ORDER flagged specific exposure here via runelite/runelite#15394 (Kalphite Queen) and #16479 (Zalcano) — NPCs reading as dead mid-fight during a phase window, which would have produced a phantom kill per phase. **It did not happen on Zulrah**, because Zulrah's forms do not zero their health ratio between transitions. That is a real result for Zulrah and **says nothing about KQ or Zalcano**, which zero their HP by design. Do not generalise it.

**Zero kills emitted for npc_id 2042/2043/2044 across the whole fight.** A broken implementation would have banked up to nine phantom kills. The player death then produced the correct terminal behaviour:

```
Discarded a despawn we had damage on: npc_id=2042 name=Zulrah myDamage=338
flaggedDead=false revoked=false deathAt=-1 itemAt=null itemGap=-1 tick=7425
```

Damage on record, no death signal, no finishing item, so the record was binned rather than counted. Dying is the case most likely to fabricate a kill and it did not.

**Snakelings stayed separate and correct.** 14 kills across npc_ids 2045 and 2046, each `dmg=1/1` (ring of recoil one-shots a 1 HP monster), graded UNCONTESTED. They never polluted Zulrah's record and Zulrah's damage never leaked into theirs — *as far as the kill counter is concerned*. XP is a different story, see the next entry.

**What this does NOT establish.** The acceptance criterion is "one multi-phase fight produces exactly one kill." **The fight produced zero kills because the player died.** Nine phases producing zero kills is not the same claim as a completed fight producing one. **Step 4 stays 🟡 partial and the temporary logging stays in.** Kalphite Queen is the better next target: its first form is genuinely killed before the transformation (20 game ticks of transition per the wiki), so it exercises the `ActorDeath`-mid-fight path Zulrah never touched.
**Source:** none — direct observation in a dev client. Wiki consulted for Zulrah/KQ mechanics: oldschool.runescape.wiki/w/Zulrah/Strategies, /w/Kalphite_Queen.

---

## 2026-08-22 — XP from damage dealt to Zulrah was allocated to a snakeling

**Status:** verified (observed) / unverified-assumption (mechanism)
**Method:** Same Zulrah session. Read back the per-kill `Kill:` lines and the final ledger write.
**Finding:** Snakeling npc_id 2045 went from **`kc=7 xp=0` to `kc=8 xp=102`** on a single kill. The ledger's `xp` field is cumulative per npc_id, so **one snakeling kill added 102 experience**. Final ledger: `2045 Snakeling uncontested=9 xp=102 bySkill={RANGED: 76, HITPOINTS: 26}`.

**That is impossible on its face.** A snakeling has **1 hitpoint** and the kill line reports `dmg=1/1`. Working backwards from the rates: 76 Ranged ÷ 4 = **19 damage**, and 26 Hitpoints ÷ 1.33 = **19.5 damage**. Both agree. Roughly **19 damage worth of experience** was credited to a monster that took one point of damage.

Comparison monster 2046 behaved sanely across the same fight — 5, 10, 10, 15 cumulative, about 5 XP per 1-damage kill, which is right.

**Timing.** The jump landed at 19:00:46, **four seconds after** carry-forward transition 9 (tick 7403, Zulrah at `myDamage=289`), and Zulrah's damage reached 338 by the discard at tick 7425. So ~49 damage went into Zulrah in that window while a snakeling died inside it. The offending kill line is also the only one in the fight with **`attacks=2`** rather than `attacks=1`.

**Mechanism is a hypothesis, not a measurement.** The plausible reading is that `XpAttributor` pools damage per tick per npc_id and splits each combat `StatChanged` by damage share, and FINDINGS 2026-08-21 already records that **XP arrives one tick before its hitsplat**. If the experience for a Zulrah hit lands on a tick where the only damage in the pool belongs to the snakeling, the whole delta is allocated to the snakeling. **That is reasoning from source, which this log has twice recorded as insufficient.** It needs instrumentation on the allocator — log the pool contents and the split at allocation time — before anyone writes a fix.

**Why it matters beyond Zulrah.** This is not the known ~0.25% skew from non-combat XP earned mid-fight (FINDINGS 2026-08-21). This is **combat XP from monster A landed on monster B**, which is exactly the dirty per-monster data the project exists to avoid. Anywhere a low-HP add dies next to a boss — snakelings, Nechryael spawns, KQ's kalphite workers — is exposed. `strandedXp` stayed at zero throughout, so the existing guard does not catch it.

**Not fixed. Nothing changed in code.** Logged so it is not rediscovered from scratch.
**Source:** none — direct observation in a dev client.

---

## 2026-08-22 — the snakeling XP theft reproduced offline: own-tick-first allocation loses to a chip hit

**Status:** verified (reproduced in a unit test) — **supersedes the "unverified-assumption (mechanism)" half of the earlier entry today**
**Method:** Replayed the exact shape from the Zulrah log as a unit test against `XpAttributor`, no client involved. A scratch probe printed the resulting per-npc totals, then was deleted.
**Finding:** The hypothesis logged earlier today is **correct**, and it needed no game access to confirm.

```java
xp.damage(SNAKELING, 1, 100);                     // recoil ping on a 1 hp add
xp.xpChanged(CombatSkill.RANGED, 1_000_076, 100); // Zulrah's xp, arrives a tick EARLY
xp.damage(ZULRAH, 19, 101);                       // Zulrah's hitsplat, one tick later
xp.settle(101);
```

Result: **snakeling 76, Zulrah 0, unallocated 0, stranded 0.**

The mechanism is `allocateAt()`'s search order — own tick, then forward, then back:

```java
for (int t = tick; t <= tick + SETTLE_TICKS; t++)   // own tick FIRST
```

XP lands one tick before its own hitsplat (FINDINGS 2026-08-21). So the boss's XP arrives on tick T while the boss's damage does not exist until T+1. If *any* damage sits in T's pool — a single point of recoil on an add is enough — `split()` succeeds there and returns. It never looks forward to T+1 where the damage that actually earned the XP is waiting. **The add takes 100% of it**, because it is 100% of its own tick's pool.

**Why no guard fired.** `strandedXp` and `unallocatedXp` are both zero: 76 XP went in and 76 XP came out. The books balance exactly. **Nothing is lost, so nothing complains** — the allocator cannot distinguish "allocated correctly" from "allocated to the wrong monster". Any future guard for this has to reason about whether a monster *could plausibly* have yielded that XP, not about whether the totals add up.

**Not fixed, deliberately.** The own-tick-first order is load-bearing and the comment above it says so in as many words. Two existing tests depend on it: `heldExperienceGoesToTheMonsterThatWasActuallyHit` and `experienceArrivingOneTickLateStillLands` ("current tick wins when it has damage"). Reordering the search naively trades this bug for the wrong-monster bug those two were written to prevent. The real design question is unanswered: **when XP arrives on a tick with a small pool and the next tick has a large one, which should win?** A candidate discriminator worth investigating is plausibility — a 1 hitpoint monster mathematically cannot pay 76 experience — but that is a desk idea, not a measurement, and this log has twice recorded what happens when a number gets guessed at.

**Committed as a failing test on purpose.** `aChipHitOnAnAddDoesNotStealTheBossesExperience` fails; its control `anAddKeepsTheExperienceItActuallyEarned` passes, so a fix cannot simply deny adds their own experience. Suite is 47 tests, 1 failure, 0 errors, and **no production code was touched** — `./gradlew compileJava` is green and the plugin ships unchanged. A red build that names a real bug beats a green one hiding it.
**Source:** none — reproduced directly from this repo's own code.

---

## 2026-08-22 — the drops bucket exposes no rarity, so Step 0b cannot use the Bucket API

**Status:** verified
**Method:** Probed ~35 candidate field names against the Bucket API. Found the bucket is named `dropsline` (not `drops`), then brute-forced single-field selects against it. Cross-read a real `{{DropsLine}}` call out of page wikitext to see what the template actually carries.
**Finding:** `spec-reference-data.md` and BUILD-ORDER Step 0b both say "same API, drops bucket, filter rarity `Always`". **That is not possible.** `bucket('dropsline')` exposes exactly two fields:

| field | |
|---|---|
| `page_name` | ✅ |
| `item_name` | ✅ |
| `rarity` | ❌ |
| `quantity` | ❌ |
| `item_id` | ❌ |

Every other name probed returns "Field not found" — `rarity`, `quantity`, `drop_rarity`, `item_rarity`, `chance`, `qty`, `item_id` and ~28 more. The template itself carries the data (`{{DropsLine|name=Abyssal ashes|quantity=1|rarity=Always}}`), so the bucket is publishing a curated subset of it, exactly as `infobox_monster` does with defence bonuses (see the Step 0c entry today). **Rarity is the entire point of `always_drops[]`**, so the specced approach is dead.

**Consequence:** Step 0b is implemented as `tools/fetch-always-drops.py`, parsing `{{DropsLine}}` out of page wikitext via `action=query&prop=revisions`. **Titles batch 50 per request**, so ~1,350 distinct monster names cost ~27 calls, not one per page — the 2026-08-14 lesson was about spawning 19,000 Windows subprocesses, and this is one process making 27 requests. Written in Python rather than bash because the parse is genuinely non-trivial: nested braces, HTML comments inside parameter values, quantity ranges, and duplicate version blocks. `awk` would have been a liability here; that is a deliberate departure from `fetch-reference-data.sh`, not an inconsistency.
**Source:** oldschool.runescape.wiki Bucket API and `action=query`.

---

## 2026-08-22 — three things the wikitext drop parse has to handle that a naive filter would get wrong

**Status:** verified
**Method:** Ran the parse against Abyssal demon, Rockslug, Zulrah, Kalphite Queen and Bloodveld before writing the puller.
**Finding:** A naive "grep for rarity=Always" produces wrong data in three distinct ways, all found in the first five pages tested.

1. **Duplicate rows per page.** Abyssal demon returns `Abyssal ashes` **twice** — pages carry one drop table per version (standard, catacombs, wilderness slayer cave) and the guaranteed drop is restated in each. Same item at the same quantity is one drop written down repeatedly; deduped. **Different quantities across versions is a different table**, and from a wiki page there is no way to know which version was killed, so those rows are emitted with blank quantities and `countable=0` rather than picking one.
2. **Quantity is not a number.** Zulrah's guaranteed drop reads `100-299<!--note: the 500 scale drop is separate and intentionally excluded here-->`. Editors leave HTML comments, `<ref>` tags and wiki links inside parameter values. Parsed into `quantity_min`/`quantity_max` after stripping, with unparseable values left blank rather than defaulted to 1.
3. **Guaranteed does not mean countable.** `spec-data-model.md` already says only non-stackable guaranteed drops can count corpses, but the drop tables show *why* it matters: Bloodveld's "always" rows include `Clue scroll (elite)` and `Reward casket (elite)`, which drop one regardless of how many corpses, and Zulrah's are scales, which stack. Both would silently under-count. Flagged `countable=0` for coins, clue scrolls, caskets and scales.

**Also corroborated:** Rockslug returns **no guaranteed drop at all**, independently reproducing FINDINGS 2026-08-14, which recorded the same thing from a hand-read of the drop table. Kalphite Queen likewise has none — worth knowing before the corpse counter is relied on at a boss.
**Source:** oldschool.runescape.wiki drop tables for the five pages named.

---

## 2026-08-22 — the drop puller's brace matcher was off by one, and the summary counts looked plausible enough to ship

**Status:** verified
**Method:** First full run wrote 273 rows. Spot-checked two monsters whose guaranteed drops had already been confirmed by hand an hour earlier — Abyssal demon and Bloodveld — and both were **missing from the output**.
**Finding:** The template-body scanner initialised its brace depth to 2 instead of 1. `{{DropsLine|` leaves **one** unclosed pair open, not two, so `}}` never brought the depth to zero. Every match ran off the end of its own template and consumed the remainder of the page — one 14 KB body instead of a 45-character one. Only drops whose page had a single trailing template survived, which is why anything came out at all.

**The failure was quiet, and that is the point worth recording.** The run reported `273 rows, 273 npc ids with a guaranteed drop, 1248 pages with no guaranteed drop` and exited zero. Nothing errored. The numbers were *plausible* — plenty of monsters genuinely have no guaranteed drop, so a low count reads as a real-world fact rather than a parser fault. **The tell was 273 rows across exactly 273 ids: not one monster with two guaranteed drops, which Bloodveld demonstrably has.** The bug was found by checking output against a hand-verified example, not by reading the summary.

After the fix: **4,339 rows, 2,798 npc ids with a guaranteed drop, 2,710 countable, 17 seconds.** A tenfold difference from a single character.

**Validated against known-good data rather than eyeballed:** Abyssal demon returns exactly one `Abyssal ashes` row (deduped from the two version blocks); Bloodveld returns three, with clue scroll and casket correctly `countable=0`; Zulrah's `100-299<!--comment-->` parses to min 100 / max 299 and is `countable=0` because scales stack; **Rockslug returns nothing**, independently reproducing FINDINGS 2026-08-14. Kalphite Queen also has no guaranteed drop, which matters because it is the next Step 4 test target — **the corpse counter will not be available there.**

56 rows carry blank quantities where version blocks disagree (Black demon, Lesser demon and similar carry different ash quantities per variant); those are emitted `countable=0` rather than guessed.
**Source:** none — direct testing.

---

## 2026-08-23 — Kalphite Queen's form-1 death produced no phantom kill; the isDying() gate holds on the monster #15394 was filed against

**Status:** verified
**Method:** Live dev client, main account, first-ever KQ attempt (KC 0 going in). Same temporary carry-forward logging as the Zulrah run. Ended in a player death during form 2.
**Finding:** One transition, and it is the one that mattered:

```
temp: NpcChanged carry-forward. 963 -> 965 name=Kalphite Queen -> Kalphite Queen
myDamage=258 othersDamage=0 deathAt=-1 revoked=false tick=2043
```

**KQ's first form genuinely dies before transforming** — unlike Zulrah, whose forms never zero their health ratio. This is the exact case `BUILD-ORDER` Step 4 flagged as unverified exposure, citing runelite/runelite#15394, which was reported against KQ specifically. **Zero kills were emitted across the whole fight.** A naive implementation would have banked one here and another on the real death.

`deathAt=-1` is why. Core's `NpcUtil` lists `KALPHITE_QUEEN` under *"These NPCs die, but transform into forms which are attackable or interactable, so it would be jarring for them to be considered dead when reaching 0hp"*, so `isDying()` returns false and `KillStateMachine.death()` discards the signal at its first guard before `deathSignalledAt` is ever set. **The gate added 2026-08-21 is doing exactly the job it was added for, now demonstrated on a boss rather than a rockslug.**

**One thing this run cannot distinguish, and it should not be claimed either way.** `deathAt=-1` is consistent with two different stories: `ActorDeath` fired and the gate swallowed it, or `ActorDeath` never fired at all. `KillDetector.onActorDeath` does not log, so the two are indistinguishable from the outside. The *outcome* is correct regardless — no phantom kill — but **"ActorDeath fires mid-transition on KQ" remains unmeasured.** A one-line debug log in `onActorDeath` would settle it on the next run.

Player death then produced the correct terminal behaviour again, on both the boss and an add:

```
Discarded a despawn we had damage on: npc_id=965 name=Kalphite Queen myDamage=270 flaggedDead=false ...
Discarded a despawn we had damage on: npc_id=961 name=Kalphite Worker myDamage=2 flaggedDead=false ...
```

**Step 4 remains 🟡 partial.** Two of three questions are now answered — the branch executes (Zulrah, nine times) and a mid-fight form death does not fabricate a kill (KQ). The outstanding one is still the plain acceptance criterion: **one completed fight producing exactly one kill.** Both attempts ended in a player death.
**Source:** none — direct observation. Wiki consulted for KQ mechanics: oldschool.runescape.wiki/w/Kalphite_Queen/Strategies.

---

## 2026-08-23 — transforming bosses exist inside a safe instance, and core's own source said so all along

**Status:** verified (source + wiki), untested in play
**Method:** Read the full "dies but transforms" switch in `rlsrc/net/runelite/client/game/NpcUtil.java` after two failed boss trips, then cross-checked the account's quest completions.
**Finding:** The list is not all dangerous bosses. A large block of it carries an **`NZONE_`** prefix — Nightmare Zone, the instanced minigame north-west of Yanille:

```
NZONE_SHAPESHIFTERGLOB_NORMAL / _HARD
NZONE_SHAPESHIFTERSPIDER_NORMAL / _HARD
NZONE_SHAPESHIFTERBEAR_NORMAL / _HARD
NZONE_ZQ_MAINZOMBIE1 / 2 _NORMAL / _HARD     <- Nazastarool, Shilo Village
NZONE_FD_DAMIS_NORMAL / _HARD                <- Damis, Desert Treasure I
```

The wiki is unambiguous about the risk: *"This is a safe activity in an instanced area. If you die here, you will not lose any of your items."*

**Nazastarool transforms twice in one fight** — zombie to skeleton to ghost, three forms — which is **more transitions per fight than Kalphite Queen's single one**, in a fight scaled for a mid-level account, repeatable back to back, with a Practice dream that can be configured to that boss alone. Every prerequisite quest on this account is finished (Shilo Village, Desert Treasure I, Fremennik Isles, Contact!, 204 total).

**Why this was missed, and it is the same failure this log keeps recording.** `rlsrc` was unpacked earlier the same day *specifically* so core could be read instead of guessed at. `NpcUtil` was then opened and grepped for gargoyle constants — and the `NZONE_` block sat directly underneath, unread. Two player deaths (Zulrah 338/500, KQ during form 2) were spent on the assumption that mid-fight phase transitions require a dangerous boss. **They do not.** Grepping a file for the thing you already expect is not reading it.
**Source:** rlsrc `net/runelite/client/game/NpcUtil.java`; oldschool.runescape.wiki/w/Nightmare_Zone.

---

## 2026-08-23 — Step 4 acceptance criterion met: Nazastarool, three deaths, two transforms, exactly one kill

**Status:** verified
**Method:** Nightmare Zone practice dream, Nazastarool only, main account. Both temporary diagnostics active — the `NpcChanged` carry-forward pair and the `ActorDeath` arrival line added earlier today.

```
19:05:02  temp: ActorDeath. npc_id=6398 name=Nazastarool isDying=false tracked=true tick=2009
19:05:05  temp: NpcChanged carry-forward. 6398 -> 6399 myDamage=70  deathAt=-1 tick=2014
19:05:25  temp: ActorDeath. npc_id=6399 name=Nazastarool isDying=false tracked=true tick=2047
19:05:28  temp: NpcChanged carry-forward. 6399 -> 6400 myDamage=140 deathAt=-1 tick=2052
19:05:48  temp: ActorDeath. npc_id=6400 name=Nazastarool isDying=true  tracked=true tick=2085
19:05:51  Kill: npc_id=6400 name=Nazastarool grade=UNCONTESTED signal=OBSERVED
          dmg=221/221 attacks=18 hits=18 maxHit=24 kc=1 sessionKills=1
```

**Three `ActorDeath` events, two carry-forwards, one kill emitted.** Damage accumulated unbroken across all three forms — 70, 140, 221 — and the final record carried `dmg=221/221`, no foreign damage, graded `UNCONTESTED`. **This is the acceptance criterion, met in a client.**

**It also answers what KQ could not.** The `isDying` column reads **false, false, true**. `ActorDeath` *does* fire on every intermediate phase — the event is present and correct, and `NpcUtil.isDying()` rejects the first two before `KillStateMachine.death()` can set `deathSignalledAt`. Earlier today the KQ run produced `deathAt=-1` and that was explicitly logged as an unmeasured outcome, because it reads identically whether the gate swallowed the signal or the signal never arrived. **It arrives. The gate is what stops it.** The one-line `onActorDeath` diagnostic added a few hours earlier is what made the difference; the ambiguity was real and is now closed.

**Nightmare Zone is the right venue for this class of test and should have been used first.** No travel, no risk, no supplies, repeatable immediately, and Nazastarool packs two transforms into a ~50-second fight. Both boss attempts (Zulrah 338/500, KQ during form 2) ended in a player death and neither produced a completed kill; the safe instance produced one on the first attempt.

**`xp=0`, and that is expected.** NMZ practice mode grants no combat experience — noticed by the user before the log was read. It does not affect this result: Step 4 counts kills from damage, not experience. It is arguably cleaner, since no XP means no possibility of the allocator mis-attribution recorded on 2026-08-22 contaminating the observation. **Step 5's unmeasured settle window still needs a venue that actually grants XP.**

**Step 4 moves to ✅ verified.** Both diagnostics have now met their recorded removal trigger and come out.
**Source:** none — direct observation.

---

## 2026-08-24 — the XP settle window, measured: 108 of 109 splits land at offset +1

**Status:** verified
**Method:** Temporary instrumentation in `XpAttributor.split()` logging every allocation, collected to a file over ~10 minutes of ordinary combat (cannon plus melee, goblins/ducks/spiders in Lumbridge). 109 splits, 90 kills. All monsters killed carry `experience_bonus=0` in the reference table, so the textbook rate applies.

**BUILD-ORDER Step 5 asks for this number and it has been a desk guess since the class was written.**

| offset | count | share |
|---|---|---|
| **+1** | 108 | 99.1% |
| **+2** | 1 | 0.9% |
| 0 or negative | **0** | — |

`offset` is `poolTick - xpTick`. **Every single allocation searched *forward*.** Not one landed on its own tick, and not one searched backward. This confirms the 2026-08-21 finding — XP arrives one tick before the hitsplat that earned it — and puts a number on how reliably: essentially always.

**`SETTLE_TICKS = 2` is justified, but only barely, and by one sample.** The single `offset=+2` case (xpTick 781, poolTick 783) followed a **124-tick idle gap** — it is the first hit after a long pause, not a mid-fight event. Lowering the constant to 1 would have discarded it. Raising it above 2 has no support in this data at all: nothing needed a wider window, and the backward arm of `allocateAt` was never used once in 109 allocations. **Leave it at 2.** Worth re-measuring at a boss, where tick pressure differs from single-target grinding.

**The pool always held exactly one monster — 109 of 109.** So this session did *not* exercise the multi-monster path where the 2026-08-22 snakeling mis-attribution lives. That case remains reproduced only in a unit test. A busier venue is still needed.

**Zero ceiling violations.** Every ratio sat well inside the bound: base combat XP is 4 per damage and the largest `experience_bonus` in the whole wiki table is +145%, so nothing can legitimately exceed ~9.8 per damage to a style skill. The ceiling check works and found nothing wrong here.
**Source:** none — direct measurement.

---

## 2026-08-24 — two false alarms called during that session, both from misreading fields

**Status:** verified (both retracted)
**Method:** Both were called mid-session from the live log, then falsified — one by replaying the exact sequence offline against `XpAttributor`, one by reading the event order properly.
**Finding:** Neither was a bug. Recording them because both were stated confidently before being checked.

**1. "The XP rate is half what it should be."** RANGED splits read `ratio=2.0` — 10 XP for 5 damage — against a textbook 4 per damage. **Wrong, because a single hit produces several splits, one per skill.** The full picture at pool tick 452 is `ATTACK xp=20`, `HITPOINTS xp=6`, `RANGED xp=10` — all against the same 5 damage. Reading one skill's share and comparing it to the whole-hit rate is the error. The rate is correct.

**2. "Identical goblin kills bank wildly different XP — 12, 36, 10, 0, 46."** **Wrong: `xp=` in the kill line is the ledger's cumulative all-time total for that `npc_id`, not the XP from that kill.** Goblins occupy ten separate npc_ids with independent histories, so different totals are exactly what should appear. The `xp=0` case is a first kill on an id whose XP had not been allocated at emit time.

**3. The off-by-one hypothesis was also wrong.** Given `offset=+1` on every split, the theory was that each tick's pool gets claimed by the previous tick's XP, systematically pairing damage with the wrong monster. **Falsified in a unit test:** four sequential goblins, each with XP arriving a tick before its hitsplat, allocated **20 XP each, exactly correct**, with zero stranded and zero unallocated. `allocateAt`'s forward search handles the early arrival properly — which is what the comment above it already claimed.

**The pattern is worth naming, because it is the same one as the placeholder bank data on 2026-08-22 and the 273-row drop pull on 2026-08-23:** a field was read without checking what it actually contained, and a confident conclusion followed. The fix each time was the same — replay it, or read the neighbouring records, before saying anything. Instrumentation output is evidence about the *shape* of events; it is not self-explanatory about their *meaning*.
**Source:** none — direct testing.

---

## 2026-08-24 — the settle-window measurement bears on the snakeling bug: every test defending own-tick-first exercises an offset never seen in play

> **SUPERSEDED the same day.** A second venue (dagannoths, 283 allocations) produced **offset 0 eight times and offset -1 three times**. The premise below — that offset 0 never occurs — held only for single-target goblin grinding. The reorder hypothesis is dead and the current search order must be left alone. See the later entry. Kept because the caution it recorded about sample breadth turned out to be the important part.

**Status:** verified (the offsets), **hypothesis** (the implication)
**Method:** Hand-traced `allocateAt` for each existing test and for the reproduced snakeling case, then compared the offsets each one exercises against the 109 measured allocations.

| case | xpTick | poolTick | offset |
|---|---|---|---|
| `heldExperienceGoesToTheMonsterThatWasActuallyHit`, first half | 99 | 99 | **0** |
| `heldExperienceGoesToTheMonsterThatWasActuallyHit`, second half | 100 | 101 | +1 |
| `experienceArrivingOneTickLateStillLands` | 101 | 101 | **0** |
| `aChipHitOnAnAddDoesNotStealTheBossesExperience` (the bug) | 100 | 100 | **0** |
| **measured in play, 109 allocations** | — | — | **+1 ×108, +2 ×1, 0 ×0** |

**Both tests that protect the own-tick-first search order depend on offset 0, and offset 0 did not occur once in 109 real allocations.** The snakeling theft is also an offset-0 event. That is not coincidence: XP arrives a tick *before* the hitsplat that earned it, so any pool sitting on the XP's own tick is, by construction, **not** the pool that produced it — it is the previous tick's damage, still in the map.

**The implication, and it is only a hypothesis:** searching forward *before* own-tick may be closer to what the game actually does than the current order. That would fix the snakeling case without a magnitude heuristic or a plausibility check.

**Not implemented, for three reasons.**
1. The comment above `allocateAt` says plainly: *"flip it, or crank SETTLE_TICKS till something sticks, and you pay the wrong monster and never hear about it. don't."* That was written deliberately and the reason is not visible from here.
2. `heldExperience...` first half would break. It encodes XP landing on the same tick as its own damage — a real scenario even if this session never produced one.
3. **109 samples is one venue, one account, one combat style, and every pool held exactly one monster.** Offset 0 may be ordinary at a boss, in multicombat, or with a different weapon speed, and simply absent from goblin grinding. Reordering the most safety-critical logic in the allocator on the strength of one Lumbridge session is exactly the mistake `DEATH_CONFIRM_TICKS` recorded twice.

**What would settle it:** the same instrumentation run at a boss and in a crowded multi-monster spot. If offset 0 stays at zero occurrences across venues, the case for reordering is strong and the two tests should be re-examined rather than the code bent around them. If offset 0 appears anywhere, the current order is protecting something real and the snakeling fix has to come from a different direction.
**Source:** none — direct measurement plus code reading.

---

## 2026-08-24 — a damage pool can never hold two monsters in practice, because settle() runs on every hitsplat

**Status:** verified (reproduced in a unit test, corroborated by 283 live allocations)
**Method:** Cannoned dagannoths at Waterbirth with the round-two instrumentation, which logs pool contents and flags multi-npc pools. Then replayed the two possible event orderings offline against `XpAttributor`.

**283 allocations. Every single one had exactly one npc in the pool** — the same result as the 109 goblin allocations, at a completely different venue, with a cannon clipping several dagannoths and six distinct npc ids appearing across the session. Pool damage ran as high as 30 per tick. A different dagannoth appears on nearly every consecutive tick — 304, 305, 306, 307 — each alone.

**The multi-monster path is not rare. It is unreachable.** The offline replay shows why:

```java
xp.damage(970, 10, 101);                              // both hitsplats first
xp.damage(971,  6, 101);
xp.xpChanged(CombatSkill.RANGED, 1_000_064, 100);
  -> 970=40  971=24   correct, split by damage share

xp.xpChanged(CombatSkill.RANGED, 1_000_064, 100);     // xp first, as it always is
xp.damage(970, 10, 101); xp.settle(101);
xp.damage(971,  6, 101); xp.settle(101);
  -> 970=64  971=0     the first hitsplat takes everything
```

Same two monsters, same 64 XP, different answer. **`damage()` calls `settle()` on every hitsplat.** When XP is already pending — which it always is, since it arrives a tick early — the very first hitsplat of the next tick triggers allocation against a pool that contains only that one monster. The second hitsplat lands microseconds later into a pool whose XP has already been spent.

**118 adjacent-tick pairs with different monsters** were observed. Those are hits that would have shared a pool if allocation had waited for the tick to finish.

**This is the same root cause as the snakeling theft on 2026-08-22.** That was framed as "a chip hit on an add steals the boss's XP". The general statement is: **whichever hitsplat is processed first claims the entire XP drop for its tick.** The snakeling case is just the most visible instance, because a 1 hitpoint add taking 76 XP is obviously absurd, whereas one dagannoth taking another dagannoth's share looks like ordinary data.

**Consequence for the design.** The damage-share split in `split()` — largest-remainder, carefully summing to the whole — is real code that has, as far as these 392 measured allocations show, **never once run with more than one monster in the pool.** It is correct and untested in play.

**Not fixed.** The obvious move is to defer allocation until the tick boundary rather than settling on each hitsplat, but that changes when XP is attributed for every kill in the plugin and touches the `EMITTED_TICKS`/stranded accounting. It needs its own careful pass, not a quick edit at the end of a session.
**Source:** none — direct measurement and testing.

---

## 2026-08-24 — offset 0 and offset -1 both occur; the reorder hypothesis is falsified

**Status:** verified — **supersedes the hypothesis logged earlier today**
**Method:** Same dagannoth session, 283 allocations, compared against the 109 from goblin grinding.

| offset | goblins (109) | dagannoths (283) |
|---|---|---|
| +1 | 108 | 272 |
| +2 | 1 | 0 |
| **0** | **0** | **8** |
| **-1** | **0** | **3** |

Earlier today it was recorded that offset 0 never occurred in 109 allocations, that every test defending `allocateAt`'s own-tick-first search order depends on offset 0, and therefore that searching forward before own-tick *might* be closer to what the game does. **That hypothesis is dead.** Offset 0 happens, offset -1 happens, and the backward arm of the search — which had fired zero times in the first session — fires in ordinary combat at a second venue. The current search order is protecting real cases and must not be reordered.

**The correction matters more than the conclusion.** The first session's 109 samples looked decisive: a 108/1 split with a clean zero in the column that would have justified a change. It was one account, one venue, one weapon, single-target, and it was wrong about the shape of the distribution. The caution recorded at the time — *"109 samples is one venue, one account, one weapon; offset 0 could be perfectly normal at a boss and just absent from goblins"* — was exactly right, and a second venue cost one session to confirm it.

**The snakeling fix therefore has to come from the settle-timing direction**, not from reordering the search.
**Source:** none — direct measurement.

---

## 2026-08-24 — cannon XP confirmed in play, and it is a clean demonstration of why XP is measured rather than derived

**Status:** verified
**Method:** Cannoned dagannoths at Waterbirth with the fixed allocator. Compared per-npc ledger totals against both possible bounds, using hitpoints and `experience_bonus` from the reference table.
**Finding:** The wiki states it directly:

> *"Damage with the cannon yields **2 Ranged experience per damage** rather than the standard 4, and **does not yield any Hitpoints experience**."*

That was already recorded in `GAME-MECHANICS.md`. It is now confirmed against live measurement:

| npc | hp | measured XP/kill | all-melee | all-cannon |
|---|---|---|---|---|
| 970 | 70 | **224** | 373 | 140 |
| 971 | 70 | **224** | 373 | 140 |
| 973 | 120 | **322** | 640 | 240 |
| 974 | 120 | **466** | 640 | 240 |
| 972 | 70 | **311** | 373 | 140 |

**Every value falls between the two bounds**, which is exactly what a kill split between cannon and melee must produce. The implied cannon share ranges from 27% to 80% across ids — just how much the cannon happened to contribute on those particular kills.

**This is the strongest argument yet for the measured-XP decision** (Step 5, `GAME-MECHANICS.md` 2026-08-16). To *derive* these numbers, the client would have to know what fraction of each kill's damage came from the cannon versus the whip, apply 2/damage to one and 4 + 1.33/damage to the other, and get the split exactly right — per kill, per skill. **The game already did that arithmetic. Measuring reads the answer.** Overkill, per-monster bonuses and rounding are the other three reasons; this is a fourth, and it appears on any Slayer task where a cannon is used, which is most of them.

**Process note.** This was flagged mid-session as a possible allocator bug — "measured XP is about half what the formula predicts". It was not a bug, and **the answer was already sitting in `GAME-MECHANICS.md`, which exists for precisely this purpose.** The register worked; it was not consulted before raising the alarm. Third occurrence this week of concluding before checking (placeholder bank data 2026-08-22, the 273-row drop pull and the cumulative-ledger misread 2026-08-23/24).
**Source:** [Dwarf multicannon](https://oldschool.runescape.wiki/w/Dwarf_multicannon), Damage and experience section, read 2026-08-24.

---

## 2026-08-24 — the server tells the client exactly what dropped: `ServerNpcLoot` supersedes tile coincidence

**Status:** verified (present in the 1.12.36 jars we compile against)
**Method:** Read `rlsrc/net/runelite/client/game/LootManager.java` and `plugins/loottracker/LootTrackerPlugin.java`, then confirmed both symbols exist in the resolved dependency jars with `javap`.

**`spec-drop-attribution.md` is built entirely on tile coincidence** — watch `ItemSpawned`, remember what appeared on which tile on which tick, and when an NPC dies check its footprint. That is what `LootManager` has always done, and the spec's core mechanic section describes it faithfully.

**It is no longer how core does it.** `LootManager` also subscribes to `ScriptPreFired` for `ScriptID.LOOTTRACKER_ADD_LOOT` (**7192**), which the server fires with explicit arguments:

```java
int npcId  = (int) scriptEvent.getArguments()[1];
int eventId = (int) scriptEvent.getArguments()[2];
int itemId = (int) scriptEvent.getArguments()[3];
int qty    = (int) scriptEvent.getArguments()[4];
```

**The server names the NPC and the item.** No tile maths, no footprint iteration, no coincidence. Items accumulate under an `eventId`, and a change of `eventId` flushes the previous group as one `ServerNpcLoot` event carrying an `NPCComposition` and its `ItemStack`s.

**Core's own loot tracker has moved over.** `LootTrackerPlugin` subscribes to `onServerNpcLoot`. **`NpcLootReceived` — the tile-coincidence event — has zero subscribers anywhere in `rlsrc/net/runelite/client/plugins/`.** The old path still runs and still posts, but nothing in core listens to it.

**Both are available to us**, verified against the resolved jars, not just the source:

| symbol | status |
|---|---|
| `net.runelite.client.events.ServerNpcLoot` | in `client-1.12.36.jar` |
| `net.runelite.client.events.NpcLootReceived` | in `client-1.12.36.jar` |
| `ScriptID.LOOTTRACKER_ADD_LOOT` = **7192** | in `runelite-api-1.12.36.jar` |

**Why this matters more for us than for a loot tracker.** Everykill's whole claim is per-monster data that can be trusted. Tile coincidence is inference — *items appeared where something died, so they are probably its drop* — and the spec's own guards (contested tiles, multiple corpses on one tile, a stack merging into existing ground items) exist to manage the error in that inference. **A server-sourced npcId and itemId is not an inference.** It removes the error rather than bounding it.

**What it does not remove.** The npcId in the script arguments is the NPC's *id*, not the record we opened when we damaged it — the join back to a specific kill is still ours to make, and that is still where a wrong answer would hide. It also cannot cover anything the server does not fire the script for; the delayed path (The Nightmare) and the Kraken/Zulrah special cases still exist in `LootManager` for a reason.

**Consequence for Step 6.** The step as written implements the mechanism core has moved away from. It should be re-planned around `ServerNpcLoot` as the primary source, with tile coincidence kept only as a documented fallback for cases the script does not cover — and the fallback should be built second, once the primary is proven, rather than first.
**Source:** `rlsrc` RuneLite 1.12.36, `LootManager.java` lines 282-349, `LootTrackerPlugin.java` line 773.

---

## 2026-08-24 — design research: how other systems show players that a record is real but not rankable

**Status:** research, no code written
**Method:** Read the public rules of four systems that face the same problem — a record that is genuine but cannot go in a ranking. Everykill's `Confidence` grades (UNCONTESTED / INFERRED / AMBIGUOUS) already exist in code with the rule *"ranks read the top grade only, totals read the lot"*, but nothing in `PRODUCT-DIRECTION.md` explains grading to a player, and the concern raised was that it would confuse an ordinary user.

**The four systems, and what each does about it:**

| System | The distinction | How it is shown |
|---|---|---|
| **Warcraft Logs** | ranked vs flagged/unranked parse | parse still exists and is viewable; a flagged rank gets a **gold background**. Exploited ranks are flagged first and removed only once a code fix lands |
| **Chess (Glicko)** | provisional vs established rating | a single **`?`** after the number when rating deviation > 110. No explanation shown inline; the mark is the whole UI |
| **MLB** | qualified vs unqualified for a rate title | **3.1 plate appearances per team game.** Unqualified players simply do not appear on rate leaderboards, but their hits still count in every total |
| **speedrun.com** | `new` / `verified` / `rejected` | pending runs are held off the board and visible only to the runner; **rejection carries a required `reason` string** |

**Four independent domains converged on the same structure**, which is the strongest signal in this research:

1. **The record is never destroyed.** WCL keeps the parse, MLB counts the hits, speedrun.com keeps rejected runs in the API. Everykill's rule already matches: AMBIGUOUS *"counts in totals, never in a denominator"*.
2. **The mark is tiny.** A `?`, a gold background, an absence from a list. Not one of these systems explains itself inline — the mark is a hook for a player who cares, and invisible to one who does not.
3. **The rule is stated publicly and precisely.** *3.1 per game*, *RD > 110*. Not "we filter low-quality data".
4. **Exclusion is from the leaderboard, not from the player's own numbers.** MLB is the sharpest case: an unqualified batter's average is still his average, it just cannot win a title.

**On the confusion concern — it is real, and MLB is the closest fit.** Chess's `?` is understood because every chess site has used it for decades; Everykill would carry no such convention. WCL's gold background is understood only by players who already care about parses. **The MLB pattern needs no explanation at all**: a player never sees "you are unqualified", they simply see their own count and, separately, a leaderboard they are or are not on. The disclosure only has to appear at the point where the two numbers differ.

**What this suggests for Everykill**, subject to the user's decision:
- **In the plugin:** show the total by default. The existing `showGradeSplit()` overlay breakdown stays opt-in, and the current behaviour of hiding INFERRED and AMBIGUOUS lines when they are zero is already the right instinct — a clean session shows one number.
- **On the site:** the player's own kill count is the total, all grades. Leaderboards and drop-rate denominators are UNCONTESTED-only. The difference is disclosed **only where it bites** — a rate page saying *"based on N of your M kills"* — rather than as a persistent badge on every row.
- **State the rule in one public sentence**, the way MLB states 3.1. Something a player can read once and never think about again.

**What none of these systems justify** is a per-row grade badge everywhere, which is the design the code's colour-per-grade enum could tempt someone into building. Colours already exist on `Confidence` and are marked *"colours match the site exactly, don't touch them"*, so the palette is settled; where it is *shown* is not.

**Not decided, not built.** This is prior art for a product decision that belongs to the user.
**Source:** [Warcraft Logs ranks guide](https://www.warcraftlogs.com/help/ranks/), [Glicko system](https://www.glicko.net/glicko/glicko.pdf) and Lichess provisional-rating convention, [MLB rate stat qualifiers](https://www.mlb.com/glossary/standard-stats/rate-stats-qualifiers) (Official Baseball Rule 9.22), [speedrun.com API run statuses](https://github.com/speedruncomorg/api/blob/master/version1/runs.md), all read 2026-08-24.

---

## 2026-08-24 — ironman contested kills are lootless by rule, which makes the ironman drop-rate filter simpler than the main one

**Status:** verified (the rule), **UNVERIFIED** (one detectability gap, below)
**Method:** Read [Ironman Mode](https://oldschool.runescape.wiki/w/Ironman_Mode) restrictions section after the question "can someone hit my bloodveld mid-fight and steal the kill and drop".

**The answer is harsher than "they take the drop".** The wiki:

> *"No loot will drop whatsoever if another player has attacked that monster. For some monsters, **even zero points of damage** dealt by another player will prevent the Ironman from getting any loot. This does not apply if that monster was weakened by other monsters."*
>
> *"In addition, if the monster attacks other players too much, the Ironman will not get loot to prevent tanking."*
>
> *"No loot will drop whatsoever if another player has used a goading potion to draw aggressiveness from monsters."*

So the drop **never spawns**. It is not awarded to the other player and it is not sitting on the ground unpickable — it does not exist. A single hit from a passer-by, or on some monsters an attack dealing **zero damage**, voids it. Assisted kills also void Achievement Diary monster tasks.

**Why this matters to us: for an ironman, "contested" and "lootless" are the same event.** On a main a contested kill still rolls and the question is who wins it, which is why the encounter class matters — most-damage for ordinary monsters and world bosses, a minimum threshold at team bosses like Nex, no contest at all when instanced. **None of that applies to an ironman.** Another player's involvement is binary.

**Consequence for the site's ironman filter, which is being built:**

| | Main | Ironman |
|---|---|---|
| Kill count | all grades | all grades — **unaffected**, you killed it |
| Contested kill, drop-rate denominator | depends on the encounter's own rule | **always excluded** |

For ironman accounts the rule needs no threshold and no per-boss lookup: **any kill with `othersDamage > 0` is ineligible for a drop-rate denominator.** Simpler than the main-account case and absolute. Including those kills would make every published ironman rate read *too rare* — the same one-directional bias `spec-drop-attribution.md` already warns about for `unknown` grades.

**The data supports it as of `eaa8443`.** `NpcStat` and `DayTally` now carry `myDamageTotal`, `othersDamageTotal` and `killsWithDamage`, so "kills where nobody else touched it" is directly answerable per npc and per day.

**THE GAP, and it is a real hole rather than a nicety.** The wiki says *"for some monsters, even zero points of damage"* without naming which monsters, and without saying whether such an attack is observable client-side. **A splash or a blocked hit may produce no `HitsplatApplied` event at all**, in which case `othersDamage` stays 0, the kill looks clean to us, and the game has already voided the drop. If that is real, an ironman filter built on `othersDamage > 0` will silently over-count eligible kills — precisely the failure mode this project keeps finding: books balance, nothing errors, the number is wrong.

**Do not build the ironman filter on `othersDamage > 0` until this is tested in a client.** The test needs a second account splashing a monster an ironman is killing, and a check of whether any hitsplat reaches the plugin.
**Source:** [Ironman Mode](https://oldschool.runescape.wiki/w/Ironman_Mode), Restrictions section, read 2026-08-24.

---

## 2026-08-24 — SUPERSEDED: a foreign splash arrives with isOthers() FALSE, so the ironman filter cannot be built on damage

> **WRONG, corrected the same hour by more data.** This entry generalised from a single
> `type=1` line before the test had produced a `BLOCK_OTHER`. A foreign splash **is**
> visible and **is** flagged `others=true` — see the entry below. The `type=1` line is
> real but is something else. Kept because the error is instructive: one observation was
> treated as the mechanism.

**Status:** verified — measured live, two accounts, Edgeville Dungeon multicombat
**Method:** the ironman account attacked giant rats while a second player attacked the same rat, first landing real hits and then deliberately splashing. Temporary instrumentation in `KillDetector.onHitsplatApplied` logged **every** hitsplat on an NPC including the ones the existing filter discards, because "no event arrived" and "event arrived and we binned it" are indistinguishable from outside.

**The setup was proven good before the result was taken.** The other player's real hits appear on the exact npc the ironman was fighting:

```
npc=Giant rat id=2864 amount=2 mine=false others=true  type=17 tick=896
npc=Giant rat id=2864 amount=6 mine=false others=true  type=17 tick=1018
```

Same `id=2864`, `others=true`. So: correct target, genuine multicombat, foreign hitsplats reaching the plugin normally. That rules out the two boring explanations for a null result.

**The splash, one tick after his real hit and on the same tick as one of ours:**

```
npc=Giant rat id=2864 amount=0 mine=false others=FALSE type=1 tick=906
```

**`isOthers()` is false.** The event does arrive — it is not missing — but RuneLite does not flag it as another player's, so it dies at the first guard in `onHitsplatApplied`:

```java
if (!mine && !others) { return; }   // foreign splash discarded here
```

**This is worse than the bug predicted from the unit test.** `ForeignSplashTest` reproduced the problem as *"amount is 0, so `othersDamage += 0` does nothing"* — implying a fix inside `KillStateMachine` counting foreign attacks. That fix would never fire: the event is thrown away one layer earlier, in the detector, before the state machine is called at all.

**Hitsplat types observed**, against `HitsplatID` in `runelite-api-1.12.36.jar`:

| type | constant | who |
|---|---|---|
| 12 | `BLOCK_ME` | **our** splash — correctly attributed to us |
| 16 | `DAMAGE_ME` | our hits |
| 17 | `DAMAGE_OTHER` | their hits |
| **1** | **not a named constant in 1.12.36** | **their splash** |

Our own splash is attributed to us (`BLOCK_ME`, `mine=true`, logged at tick 673) — but the foreign equivalent arrives as an unnamed type 1 with **both** ownership flags false, indistinguishable at that guard from a heal or someone else's poison tick, which is exactly what the guard's comment says it is there to reject.

**Consequence for the ironman drop-rate filter, which was about to be built on `othersDamage > 0`:** that predicate is unsound. The wiki rule is that *"even zero points of damage"* from another player voids an ironman's drop entirely, and the zero-damage case is precisely the one the client currently cannot see. An ironman kill that the game has already made lootless reads to us as a clean solo kill. Every published ironman drop rate would be biased **too rare**, one-directionally, and nothing would error.

**What is still unknown, and must not be guessed:**
- **What type 1 actually is.** It is not `BLOCK_ME` (12). It may be a generic block/miss splat with no owner, but that is inference. It needs looking up in the cache or a newer API version before any code keys on it.
- **Whether type 1 is reliably a foreign miss**, or whether NPC-on-NPC and other ownerless events share it. Keying the ironman filter on "type 1 on a monster we are fighting" could catch a monster being weakened by *other monsters* — which the wiki explicitly says does **not** void the drop.

**Do not implement the ironman filter yet.** The mechanism is now understood well enough to know the obvious implementation is wrong, which is the point of having run the test.
**Source:** live measurement 2026-08-24; [Ironman Mode](https://oldschool.runescape.wiki/w/Ironman_Mode) for the drop-void rule.

---

## 2026-08-24 — a foreign splash IS visible: BLOCK_OTHER, others=true, amount=0. The filter is buildable

**Status:** verified — measured live, two accounts, Edgeville Dungeon multicombat
**Method:** As the superseded entry above. The correction came from letting the test keep running instead of writing up the first zero-damage line that appeared.

**All four zero-damage hitsplats captured in the session:**

```
npc=Giant rat id=2864 amount=0 mine=true  others=false type=12  tick=673    BLOCK_ME
npc=Giant rat id=2864 amount=0 mine=false others=false type=1   tick=906    (unnamed)
npc=Giant rat id=2863 amount=0 mine=false others=true  type=13  tick=1236   BLOCK_OTHER
npc=Giant rat id=2863 amount=0 mine=false others=false type=1   tick=1245   (unnamed)
```

Against `HitsplatID` in `runelite-api-1.12.36.jar`:

| type | constant | flags | meaning |
|---|---|---|---|
| 12 | `BLOCK_ME` | `mine=true` | **our** splash — already visible |
| 13 | **`BLOCK_OTHER`** | **`others=true`** | **their splash — visible, correctly attributed** |
| 16 | `DAMAGE_ME` | `mine=true` | our hit |
| 17 | `DAMAGE_OTHER` | `others=true` | their hit |
| 1 | **not in `HitsplatID` at all** | both false | unknown, see below |

**The important correction: `BLOCK_OTHER` (13) passes the existing `!mine && !others` guard.** The event reaches `KillStateMachine.damage()` with `mine=false`, so it lands in the `else` branch — and there the bug reproduced in `ForeignSplashTest` is exactly as the unit test described:

```java
else { r.othersDamage += amount; }   // amount is 0, so this records nothing
```

So the ironman problem is real but **shallower than the superseded entry claimed**. The event is not being discarded by the detector's ownership guard; it arrives and is then silently lost because only its *amount* is recorded and its amount is zero. The fix is the one the unit test already implies: count foreign **attacks**, not just foreign **damage**, mirroring what `attacksCount` has always done for our own zero splats — *"a zero splat is still an attempt"*.

**`type=1` remains unexplained and must not be assumed.** It is not a constant in `HitsplatID` for 1.12.36, it carries both ownership flags false, and it appeared twice — each time within a few ticks of a foreign action on a rat the ironman was fighting. It may be the other player's *attack* rendered on a target they do not own, or something unrelated. **Do not key any filter on type 1** until it is identified; the guard's own comment says ownerless splats are heals and other players' poison ticks, and a monster weakened by *other monsters* explicitly does **not** void an ironman drop.

**Corroborating the drop rule from the same session** — perfect correlation across 8 kills, `loottracker_add_loot` present or absent:

| kill | damage | server loot event |
|---|---|---|
| 2864 UNCONTESTED | 10/10 | **yes** |
| 2856 UNCONTESTED | 5/5 | **yes** |
| 2856 UNCONTESTED | 5/5 | **yes** |
| **2864 AMBIGUOUS** | **9/11** | **NO** |
| **2864 AMBIGUOUS** | **5/11** | **NO** |
| 2856 UNCONTESTED | 5/5 | **yes** |
| **2856 AMBIGUOUS** | **1/5** | **NO** |
| 2863 UNCONTESTED | 11/11 | **yes** |

**Every clean kill produced loot. Every contested kill produced none.** The `9/11` row is the one that matters: **82% of the damage and still nothing**, which confirms the ironman rule is absolute rather than majority-based. On a main that kill wins the drop comfortably.

**Still not tested: a kill where the other player ONLY splashed and never landed a hit.** Every AMBIGUOUS kill above contains real foreign damage. The wiki's *"even zero points of damage"* clause is therefore still unconfirmed in play, and it is the case that decides whether `othersDamage > 0` alone is unsound or merely incomplete.
**Source:** live measurement 2026-08-24; `HitsplatID` from `runelite-api-1.12.36.jar`; [Ironman Mode](https://oldschool.runescape.wiki/w/Ironman_Mode).

---

## 2026-08-24 — measured: an ironman gets NOTHING from a contested kill, even at 90% of the damage

**Status:** verified — 17 kills, live, two accounts, Edgeville Dungeon multicombat
**Method:** the ironman account killed giant rats while a second player attacked the same rats. Kills and `loottracker_add_loot` script events matched **by timestamp**, not by proximity in the log (see the method warning below).

| kill | my damage | share | server loot event |
|---|---|---|---|
| UNCONTESTED × 9 | full | 100% | **all nine** |
| AMBIGUOUS | 9/11 | 82% | none |
| AMBIGUOUS | 5/11 | 45% | none |
| AMBIGUOUS | 1/5 | 20% | none |
| AMBIGUOUS | 4/10 | 40% | none |
| AMBIGUOUS | 8/10 | 80% | none |
| AMBIGUOUS | 1/5 | 20% | none |
| **AMBIGUOUS** | **9/10** | **90%** | **none** |
| AMBIGUOUS | 4/5 | 80% | none |

**9 clean kills produced 9 loot events. 8 contested kills produced zero.** Every loot event carries the same timestamp as its kill, so nothing is lost to timing.

**The 9/10 row is the finding.** Ninety per cent of the damage and no drop at all. This confirms the wiki rule as **absolute, not proportional and not majority-based**: any other player's involvement voids an ironman's drop entirely, however small their contribution and however large ours. On a main that kill wins the drop comfortably — `Drops` says the most-damage player receives it.

**Consequence: `othersDamage > 0` is the correct predicate for the ironman drop-rate filter**, and it is now measured rather than assumed. Contested kills must be excluded from ironman drop-rate denominators entirely — no threshold, no share calculation, no per-boss encounter rule. Kill counts are unaffected; the kill happened.

**Still not covered:** every contested kill here contains real foreign *damage*. A kill where the other player **only splashed** was attempted twice and both times they connected before the kill landed. The wiki's *"even zero points of damage"* clause therefore remains unconfirmed in play. Since `BLOCK_OTHER` is visible to us (previous entry), a fix counting foreign *attacks* rather than foreign *damage* would cover that case if it is real — but whether it is real is still unmeasured.

**METHOD WARNING, recorded because it produced two wrong answers in a row on this exact question.**
1. The first pass eyeballed a `tail` window and reported "perfect correlation, every AMBIGUOUS got nothing". The conclusion happened to be correct, but the evidence did not support it — the loot lines for several kills sat outside the window being read.
2. The second pass scripted it as *"did a loot event appear within the next few lines?"* and reported the **opposite** — that contested kills did get loot. That was wrong too: proximity in a log is not association, and it was matching each contested kill against the **following** kill's loot.

Only the third pass, joining on timestamp, is evidence. **Do not answer a correlation question by reading a log window or by counting nearby lines.** Extract both event types with their timestamps and join them.
**Source:** live measurement 2026-08-24; [Ironman Mode](https://oldschool.runescape.wiki/w/Ironman_Mode).

---

## 2026-08-24 — the game ANNOUNCES the ironman contest in chat, and the plugin already receives it

**Status:** verified — live, correlated across 17 kills
**Method:** A screenshot of the client during the rat test showed two chat messages nobody had thought to look for. Confirmed present in the log and correlated by timestamp against every kill in the session.

**The game says it outright, as `SPAM` chat messages:**

> *"As an Ironman, you might not receive kill-credit for this monster."*
> *"As an Ironman, you don't get loot if players outside your group helped you kill the monster."*

**This is a direct server statement about the exact condition the ironman filter needs**, and it had been approached all day by inference — first from `othersDamage`, then from hitsplat ownership flags. The signal was in the chat box the whole time.

**Correlation across the session:**

| | warning fired |
|---|---|
| 9 UNCONTESTED kills | **0** |
| 8 AMBIGUOUS kills | **6** |

**Zero false positives.** No warning ever appeared on a clean kill. That matters more than the recall gap: a signal that never fires wrongly can safely *add* to a filter.

**Timing, read off the log:**
- **`don't get loot`** fires on the **same second as the kill** — 15:13:48, 15:15:02, 15:16:23 each match their kill exactly. It is a statement about the kill that just happened.
- **`might not receive kill-credit`** fires **4–9 seconds before** the kill (15:09:19 → kill 15:09:23; 15:14:27 → kill 15:14:33; 15:16:17 → kill 15:16:23). It appears to trigger when the contest *begins*, not when the monster dies.

**Two contested kills produced no warning** — the `5/11` at 15:10:30 and the `1/5` at 15:11:35. **The cause is unknown and must not be guessed.** Candidates: the client de-duplicating repeated `SPAM` messages, a cooldown on the warning, or a genuine difference in those kills. Both of those kills also produced no loot, so the underlying rule held; only the announcement was absent.

**Design consequence.** The chat message is a *better* signal than `othersDamage > 0` in one respect — it is the server's own statement rather than our reconstruction — but it is **not a replacement**, for three reasons:
1. Recall was 6/8 in this sample and the gap is unexplained.
2. `SPAM` messages can be filtered or suppressed client-side by the user, so their absence is not evidence.
3. It says *"might not"* for kill-credit, which is not a determination.

**The robust filter is the union: `othersDamage > 0` OR the loot warning seen for that kill.** Damage covers the announcement gap, the announcement covers the zero-damage splash case that `othersDamage` structurally cannot see (see the `BLOCK_OTHER` entry above). Neither alone is sufficient; together they cover both known failure modes.

**Not implemented.** Recorded so the filter is built on the union rather than on whichever half was investigated most recently.

**Process note.** This was found by the user glancing at his own screen, after a day spent inferring the same fact from hitsplat internals. **The client's own chat output is a first-class data source and was never checked.** Worth remembering before the next round of inference: read what the game is already saying before reconstructing it from events.
**Source:** live measurement 2026-08-24, screenshot plus `client.log` correlation.

---

## 2026-08-24 — every ironman finding today is account-type gated, and the plugin currently has no idea what account type it is on

**Status:** verified (the gap, and the mechanism), **UNVERIFIED** (the non-zero varbit values)
**Method:** Grepped `src/main` for any notion of account type, then read how core gates ironman-specific behaviour.

**`grep -rn "AccountType\|accountType\|IRONMAN" src/main` returns nothing.** The plugin has never checked what kind of account it is running on. Everything measured today — contested kills producing no loot, the chat warnings, the `othersDamage > 0` filter predicate — **applies to ironman accounts only** and is flatly wrong for a main:

| situation | main | ironman |
|---|---|---|
| contested kill, we dealt 90% | **receives the drop** (most damage wins) | **receives nothing** |
| contested kill, we dealt 20% | receives nothing (someone else had more) | receives nothing |
| drop-rate denominator | depends on the encounter's own rule | **always excluded** |

Applying the ironman rule to a main would discard legitimate kills where the player won the drop outright. Applying the main rule to an ironman would count lootless kills as dry ones. **Both directions are silent data corruption**, which is the failure mode this project exists to prevent.

**The mechanism core uses:** `client.getVarbitValue(VarbitID.IRONMAN)` — varbit **1777** in `runelite-api-1.12.36.jar`. `GroundItemsPlugin` reads exactly this to decide whether ground items are takeable, with the comment:

```java
return ownership != OWNERSHIP_OTHER || accountType == 0; // Mains can always take items
```

**So `0` means main.** That is confirmed by core's own comment and is the only value this project can currently assert.

**The varbit-to-mode mapping, read from core `HiscorePlugin.java:277-286`:**

| value | mode |
|---|---|
| **0** | main — *"Mains can always take items"* |
| **1** | Ironman |
| **2** | Ultimate Ironman |
| **3** | Hardcore Ironman |

Corroborated independently by `DailyTasksPlugin.java:212`, which comments `!= 2 /* UIM */`.

**GROUP IRONMAN IS NOT IN THIS VARBIT.** Core's switch has no case for it and falls through to `NORMAL`. Group status is detected a completely different way — `client.getClanSettings(ClanID.GROUP_IRONMAN)` (`NameAutocompleter.java:241`), i.e. via the group's clan channel, not an account varbit. `HiscoreEndpoint` likewise has no group entry.

**This is the trap.** A Group Ironman reading the varbit alone looks like **either** a plain Ironman **or**, if the varbit is 0 for them, a main — and neither is right. That must be resolved before the filter ships, because the game's own message says *"players outside **your group**"*: a groupmate's damage does **not** void a GIM's drop, so `othersDamage > 0` would wrongly exclude their ordinary group play. It matters, because **Group Ironman is a genuine exception**: the game's own message says *"you don't get loot if players outside **your group** helped you kill the monster"* — so for a GIM, a groupmate's damage does **not** void the drop, and `othersDamage > 0` would wrongly exclude their normal group play.

**Related unread mechanism:** `GroundItem.getOwnership()` and the `OWNERSHIP_SELF` / `OWNERSHIP_GROUP` / `OWNERSHIP_OTHER` constants. Core already distinguishes *group* ownership from *self* and *other*, which is very likely the same distinction the group-ironman loot rule needs. Read this before implementing anything for GIM.

**Required before the ironman filter ships:**
1. Read `VarbitID.IRONMAN` once the player is logged in and store the account type on the session.
2. Gate every rule from today's findings behind it. A main must keep the encounter-class rules from `reference-boss-encounter-classes.md`.
3. Resolve the six-mode mapping, and treat **group** ironman separately — a groupmate is not an outsider.
4. Do not assume the varbit is available at login; core reads it live at the point of use rather than caching it at startup.
**Source:** `rlsrc` RuneLite 1.12.36 `GroundItemsPlugin.java:275,768`; `VarbitID` from `runelite-api-1.12.36.jar`; [Ironman Mode](https://oldschool.runescape.wiki/w/Ironman_Mode).

---

## 2026-08-24 — step 6 first light: the server loot pipeline works, and the kill is emitted before its loot arrives

**Status:** verified — live, first `ServerNpcLoot` capture
**Method:** `LootDetector` wired in capture-only (commit `5e050e6`), ordinary killing in a client. No attribution, nothing branching on the events.

**The full chain verified itself against three independent sources.** One cyclops kill:

```
15:38:56  Kill: npc_id=7271 name=Cyclops grade=UNCONTESTED dmg=75/75
15:38:56  loottracker_add_loot npc=7271 event=77265 item=532  qty=1
15:38:56  loottracker_add_loot npc=7271 event=77265 item=1295 qty=1
15:38:56  (loot expired unclaimed)
```

| source | claim |
|---|---|
| `data/always_drops.tsv` row `7271` | Cyclops **always drops Big bones** |
| server event | `item=532` |
| `ItemID` in `runelite-api-1.12.36.jar` | **532 = `BIG_BONES`** |

They agree. `1295` is `STEEL_LONGSWORD`, an ordinary random drop. **This is the `always_drops` cross-check working for the first time** — the table predicted the guaranteed drop and the server delivered exactly it. That is the falsifiable, two-directional check `spec-drop-attribution.md` wants and no other tracker has.

**The negative case held too.** Two Ghost kills (7263, 7264) produced **zero** loot events, and `always_drops.tsv` has **no rows for Ghost**. Reference data says "guaranteed drop: none", live behaviour says "no event". Those kills are correctly *loot-empty*, not *loot_unknown* — and without the table there would be no way to tell those two apart.

**THE ORDERING PROBLEM, now measured rather than predicted.** Everything above lands in the same second, in this order:

1. the kill is emitted
2. the server's loot events arrive
3. the loot expires with nobody to give it to

**`resolve()` emits the kill before its loot exists.** This is exactly what the deferred "hold the kill" change (option 1, "try 1") was for: park the record, emit after loot resolves on the tick boundary. It was deferred because it touches all 29 `KillStateMachineTest` cases for zero behaviour change while no loot code existed. Loot code now exists, and this log is the evidence that the hold is required rather than merely tidy.

It still needs no invented constant — the hold is *"finish the tick"*, because that is when `LootManager` flushes pending script loot (`processScriptLoot` on `GameTick`).

**Sample size warning.** One cyclops, two ghosts. This confirms the pipeline is wired correctly and the ordering is wrong; it does **not** establish how often the server reports, whether the `npc_id` join survives a crowd, or what happens when two identical monsters die on one tick. Those are still the step-2 questions in `plan-step6-loot.md` and remain unmeasured.
**Source:** live measurement 2026-08-24; `ItemID`/`always_drops.tsv` cross-check.

---

## 2026-08-24 — decision: record every drop, not just rares. The corpse counter cannot work without the junk

**Status:** decision, grounded in the existing spec and today's measurement

**Question raised:** should the plugin track all drops or only rare ones?

**Answer: all, and this is not a convenience call.** `spec-data-model.md` already specifies `drops[]` on the kill event, and the corpse counter — the mechanism that proves a loot pile contains exactly our kills — **is built on guaranteed drops**, which are almost entirely junk:

> *"Uses guaranteed drops to prove a loot pile contains exactly our kills."*

`data/always_drops.tsv` holds **4,339 rows across 2,798 npcs**, of which **4,104 are countable** (non-stackable, so a pile of them can be counted). Bones, ashes, scales. Discard commons and that verification is gone, and with it the ability to tell *"this pile is exactly our three kills"* from *"someone else's kill is mixed in"*.

Today's cyclops session is the proof it already works: `always_drops` predicted Big bones (532) for npc 7271, and the server delivered 532 on **4 of 4** kills. That cross-check is only possible because the junk is recorded.

**Rare-only is not a lighter option. It is a broken one.**

**On the leaderboard idea it enables.** Rarity does not have to be computed or curated — the wiki publishes it in the same drops bucket `always_drops.tsv` is built from (`tools/fetch-always-drops.py`, bucket `dropsline`). So a drop can be scored automatically against its published rate, and a 1/5000 landing at 200 KC is identifiable without anyone maintaining a hand-written list of "items that count".

That composes with a rule already in `spec-data-model.md` for competitive boards:

> *"a batched drop is recorded at the **highest** possible KC in the batch. Nobody gains rank from uncertainty."*

**The cost, and whose problem it is.** Every bone and every 3-coin drop gets recorded and uploaded. Locally that is nothing. For the site it is an ingest and storage question, which is Gage's lane per `PRODUCT-DIRECTION.md` line 7 — worth telling him the volume decision is settled and why, because it shapes his schema.

**What this does not decide:** whether every drop is *displayed*. Recording all and showing all are different questions, and the panel showing a scrolling list of bones would be its own problem. Display is unspecified.

---

## 2026-08-24 — confirmed: the full drop is captured, guaranteed and random alike

**Status:** verified — four cyclops kills, 2026-08-24

Answering "were the other drops recorded, or just the bones": **all of them.**

| kill | eventId | guaranteed | rolled |
|---|---|---|---|
| 1 | 77265 | Big bones (532) | Steel longsword (1295) |
| 2 | 77266 | Big bones (532) | Coins ×99 (995) |
| 3 | 77267 | Big bones (532) | Black longsword (1297) |
| 4 | 77268 | Big bones (532) | Black knife ×8 (869) |

Item ids resolved against `ItemID` in `runelite-api-1.12.36.jar`, not guessed.

The loot script fires **once per item**, and `LootDetector.record` merges fires sharing an `eventId` into one entry, so a kill's whole drop stays together instead of arriving as separate pseudo-kills. **Quantities are the server's** — the ×99 and ×8 came from the script's `qty` argument, not inferred from a ground pile.

**The cross-check runs in both directions.** `always_drops.tsv` predicted Big bones on all four and got 4/4; the second item differed every time. That is what separates *guaranteed* from *rolled* with no hardcoding: an item the table names is expected, an item it doesn't is a roll.

---

### Decisions taken (user, 2026-08-24)

1. **Store all drops locally.** Confirmed — see the corpse-counter entry above for why rare-only is structurally broken.
2. **Filter by mob in the UI.** A per-monster filter, so "all drops" stays usable without a scrolling wall of bones. This is the *display* answer the previous entry deliberately left open.
3. **Rare drops get their own leaderboard**, designed later with Gage. Rarity comes from the wiki's drops bucket that `always_drops.tsv` is already built from, so nothing needs hand-curating.

**Client-side consequence of (2), the only part in this repo's lane:** filtering by mob needs drops stored *per npc_id*, which `NpcStat` already keys on. No new structure — but the drop list must hang off the npc row rather than a flat session log, or the filter has nothing to filter on.

---

## 2026-08-24 — Step 6 verified in a client: loot attaches to the kill that produced it

**Status:** verified — live, three cyclops kills, 2026-08-24 16:08
**Method:** `LootDetector` + hold + `attachLoot` all in place (commits `5e050e6`, `b6dbd7e`, `5f9b77c`, `008d2c8`). Ordinary killing, reading the kill log's new `loot=` / `drops=` fields.

```
Cyclops  UNCONTESTED  CONFIRMED  532x1,995x41
Cyclops  UNCONTESTED  CONFIRMED  532x1,869x10
Cyclops  UNCONTESTED  CONFIRMED  532x1,1623x1
```

Item ids resolved against `ItemID` in `runelite-api-1.12.36.jar`: **Big bones**, **Coins ×41**, **Black knife ×10**, **Uncut sapphire**.

**What this proves, each part independently:**

1. **The hold works.** Every one of those kills was parked at death and met its loot on the tick boundary. Three commits ago the kill was emitted *before* its loot existed and this line would have read `loot=NONE drops=-` forever.
2. **The join works.** `drainFor(npcId, tick)` found the right drop for the right kill, three times.
3. **Grading works.** All three `CONFIRMED` — one loot event, kill uncontested, so rate-eligible.
4. **`always_drops` predicted Big bones 3/3**, and the second item differed every time. That separates *guaranteed* from *rolled* with nothing hardcoded, in both directions.
5. **Quantities are the server's own.** `995x41` is one entry with quantity 41, not 41 items — so nothing downstream has to reason about piles merging.

**Still unverified, and the sample cannot speak to it:** every kill here was sequential and uncontested. **Two of the same monster dying on one tick has still never happened in a client**, so the `UNKNOWN` path — the reason `attachLoot` exists in the shape it does — is covered by unit tests only. Same for `PROBABLE`, which needs a contested or deduced kill with loot.

**Note on the ghosts.** Two Ghost kills earlier in the same session predate the `loot=` field being added, so they are not evidence of the `NONE` path either way. The earlier finding that ghosts produce no loot event still stands on its own.

---

## 2026-08-24 — the always_drops cross-check does NOT belong in the client, and the spec already said so

**Status:** correction, caught before writing code

Having verified Step 6's attribution live, the obvious next step looked like wiring `data/always_drops.tsv` into the plugin so `LootConfidence.NONE` could distinguish three cases it currently cannot:

| kill | table says | verdict |
|---|---|---|
| Ghost, no loot event | nothing guaranteed | genuinely empty |
| Cyclops, no loot event | **always Big bones** | **we missed it** |

That reasoning is sound. **The placement was wrong**, and `spec-reference-data.md:45` says so in one line:

> *"the reference table stays server-side and is never shipped to the client."*

Both routes to getting it client-side are named and rejected at `:240-241` — *"Shipping the table in the jar"* and *"Fetching it from the client at runtime"*. Four reasons, in the spec's own order of harm:

1. **`PRODUCT-DIRECTION.md`: first Hub submission is local-only, no upload.** A runtime fetch drags third-party disclosure and a server dependency back into the first review — the exact surface that decision removed.
2. **`LICENSING.md:54` — wiki content is CC BY-NC-SA 3.0, non-commercial**, with share-alike sitting badly against a BSD plugin. Keeping wiki-derived data off the client keeps that problem on our own infrastructure.
3. **Bundling goes stale.** A jar-shipped table needs a plugin release for new content, against `PROJECT.md`'s *"new content works on release day"*.
4. **Read-time interpretation is reversible.** A wiki correction retroactively fixes every historical kill; baked into the client it is frozen at whatever we believed that day.

**Where the check actually goes: the server, at ingest.** The client already uploads everything it needs — `npc_id`, the drop list, and `lootConfidence`. The table lives next to the ingest, so *"cyclops with no loot event"* is a flag raised there and fixed there.

**What that means for `LootConfidence.NONE`:** it stays deliberately ambiguous in the client, and that is correct rather than incomplete. Its javadoc already says the cross-check *"isn't wired up yet"* — that wording should read *"is a server-side concern"*, because the client is never going to resolve it.

**Process note.** `EverykillPlugin`'s own header says the plugin **records and does not analyse**. The plan I wrote this morning (`plan-step6-loot.md`, step 6 of the order of work) lists "always_drops cross-check" without saying where it runs, and I read that as client-side work. Checking the spec before writing took under a minute and would have cost an afternoon of building something that had to be torn out — and worse, something that would have failed Hub review for a network call.

---

## 2026-08-24 — account type verified live: GROUP_IRONMAN resolves through the clan channel, not the varbit

**Status:** verified — live, 2026-08-24 16:25, account the group-ironman account

```
Kill: npc_id=7263 name=Ghost grade=UNCONTESTED ... account=GROUP_IRONMAN loot=NONE drops=-
```

One line, three things proven:

1. **Group ironman resolves.** `client.getClanSettings(ClanID.GROUP_IRONMAN)` returns non-null for a GIM, which is the only route to that answer — `VarbitID.IRONMAN` has no group value and core's own switch falls through to normal.
2. **The varbit-only path was right to refuse.** Before the clan check existed, the same account reported `GROUP_UNRESOLVED` rather than guessing at `MAIN` or `IRONMAN`. Both guesses would have been wrong and neither would have looked wrong.
3. **`loot=NONE drops=-` on a ghost is correct, not a miss.** `always_drops.tsv` has no rows for Ghost, and the server reported nothing. Reference data and live behaviour agree.

**Process note, and it cost time.** `account=GROUP_UNRESOLVED` appeared on a kill, and it was assumed to be a main account — a main — which made it look like a mapping bug. Temporary diagnostics went in to chase it. The user corrected it in three words: that was the ironman account, a group ironman, and the plugin had been reporting the truth the whole time.

**The account in use is part of the evidence.** A log line saying something surprising about account type is not evidence of a bug until you know which account produced it. Same shape as reading a cumulative field as a per-kill one: the number was right, the assumption about what it described was not.

---

## 2026-08-24 — UI audit: what the panel shows vs what the spec says, and the answer to "uncontested on the overlay?"

**Status:** audit, no code changed

Prompted by a look at the live panel and overlay after Step 6/7 landed.

### The panel's worst problem is a known bug, not a style issue

The screenshot shows **four separate "Lesser demon (82)" rows** (41, 31, 30, 17 kills) and **three "Giant rat" rows** (11, 4, 3). Those are different `npc_id`s wearing the same name — `data/monsters.tsv` lists **eight or more ids** named "Lesser demon".

This is already written up at `FINDINGS.md:307`:

> *"A slayer task shows as multiple panel rows that must be summed before comparing against the in-game task counter. This is PROJECT.md's 'store raw npc_id forever, display grouping is a read-time concern' behaving exactly as designed, but it is confusing in the panel and argues for a display-only rollup that leaves the ids intact."*

**The storage is correct and must not change** (`PROJECT.md:57`). The fix is a **display-only rollup**: group rows by `(name, combatLevel)`, sum them, keep the ids underneath so expanding a row can still show the split. A player killing 119 lesser demons should see one row saying 119.

### What the spec has that the client doesn't

`spec-plugin-ux.md` specifies **four tabs**; roughly one exists.

| Spec | Built |
|---|---|
| **1a. Kill Log** — every monster in the game, killed or not; completion header; grouped views; search; per-row detail incl. **drops received** and **dryness position** | partial — a flat list of what you have killed, no search, no completion, no drops |
| **1b. Session** — kills/hr, xp/hr, time elapsed, supplies, deaths, slayer task from varbits | partial — session kill count and grade split only |
| **1c. Goals** — set a kill goal, progress bars, auto-suggest from slayer task | **none** |
| **1d. Records** — personal bests, milestones, **luckiest drop, longest dry streak** | **none** |

So the two things named in the question — **viewing drops** and **dry streaks** — are both specified and both unbuilt. Drops now exist in `KillRecord` as of today (`008d2c8`) but nothing renders them, and nothing persists them per npc yet.

### The overlay question: keep the grade split, but it should not be the default

`spec-plugin-ux.md:69-77` is explicit about the overlay:

> *"Off by default. When on, one compact box."*
> Contents: **current target counter** (name, kills this session, lifetime KC) and **active goal progress bar**.
> *"If a proposed overlay element would change what a player does in the next tick, it doesn't ship."*

The grade split is **not** in that list. It also fails the spec's own test in spirit: seeing `ambiguous 1` appear mid-fight tells a player someone else is on their target, which is exactly the kind of in-fight signal the section exists to keep out.

**Recommendation:** keep `showGradeSplit()` as an opt-in diagnostic — it has earned its place during development and it is genuinely useful for verifying grading against the game — but the overlay's default should be the spec's: target name, session kills, lifetime KC. The grade split belongs in the **Session tab**, where it is a summary rather than a live prompt.

The one thing the overlay currently gets right and should keep: hiding `inferred`/`ambiguous` when they are zero, so a clean session stays quiet.

### Ordering, if this becomes the next arc

1. **Display rollup** — one row per monster. Fixes the loudest confusion and is display-only, so it cannot corrupt stored data.
2. **Drops in the panel** — the data now exists; per-npc persistence does not. Needs a `drops` field on `NpcStat` first.
3. **Overlay default** back to the spec's contents; grade split moves to a Session tab.
4. **Dry streaks** — needs drop history per npc plus rarity, and rarity is server-side by `spec-reference-data.md`. Client can show *"kills since last X"*; the *"you are 2.4x dry"* framing is a site feature.

---

## 2026-08-24 — why the side panel looks unfinished, from reading core's own panels

**Status:** research, no code changed yet
**Method:** read `LootTrackerBox`, `GrandExchangeItemPanel` and `ColorScheme` in `rlsrc` (RuneLite 1.12.36) rather than guessing at taste.

Our panel and core's panels use the same widgets and the same colours. The difference is structural, and it comes down to four things core does that we don't.

### 1. Rows are boxes, not lines

`LootTrackerBox` gives every entry a **title strip on its own background** — `DARKER_GRAY_COLOR.darker()` — sitting above the content, with `EmptyBorder(7,7,7,7)` inside it and `EmptyBorder(5,0,0,0)` separating one box from the next.

Ours draws every row on the same flat `DARKER_GRAY_COLOR` with 2px of vertical padding and nothing between them. That is why the screenshot reads as a wall: **there is no visual unit**. A monster, its xp/kill line, its grade bar and its drops are four unrelated lines that happen to be adjacent.

Core's actual values, for reference:

| | core | ours |
|---|---|---|
| Row separation | `EmptyBorder(5, 0, 0, 0)` | none |
| Title inner padding | `EmptyBorder(7, 7, 7, 7)` | `EmptyBorder(2, 0, 2, 0)` |
| Title background | `DARKER_GRAY_COLOR.darker()` | same as body |

### 2. Hover feedback

`GrandExchangeItemPanel` attaches a `MouseAdapter` that swaps the whole panel's background to `DARK_GRAY_HOVER_COLOR` (35,35,35) on enter and back on exit, recursing into children so the row highlights as one thing. `LootTrackerBox` uses `DARKER_GRAY_HOVER_COLOR` (60,60,60) the same way.

We set a hand cursor on expandable rows and nothing else. **The screenshot shows a cursor mid-row with no indication of what it is over.**

### 3. A three-level type hierarchy, by colour not size

Core never changes font size in these panels — everything is `FontManager.getRunescapeSmallFont()`. Emphasis comes from colour alone:

- `Color.WHITE` — the thing itself (item name, monster name)
- `LIGHT_GRAY_COLOR` (165,165,165) — supporting numbers (price, subtitle)
- `MEDIUM_GRAY_COLOR` (77,77,77) — inactive/absent

Ours uses `TEXT_COLOR` for names and one custom `SUBTLE` for everything secondary, so xp/kill, drop counts and the drops header all sit at the same weight. Three kinds of information, one voice.

### 4. Indentation carries meaning

Expanded content in `LootTrackerBox` is a separate container with its own background, so nesting is obvious. Our expanded skill and drop lines are indented 8px on the same background — visible in the screenshot as `Strength 3.9k` floating under `Cyclops (56)` with no tie to it.

### What this suggests, in order of effect per line changed

1. **Give each monster row a container** with `EmptyBorder(5,0,0,0)` outside and a title strip at `DARKER_GRAY_COLOR.darker()`. Biggest single change; makes the list read as rows.
2. **Add hover highlight** on the whole row via `MouseAdapter`, matching `GrandExchangeItemPanel.matchComponentBackground`.
3. **Adopt core's three-colour hierarchy** — white for names, `LIGHT_GRAY_COLOR` for numbers, `MEDIUM_GRAY_COLOR` for absent.
4. **Put expanded content on its own background** rather than indenting it.

**What not to do:** invent a new palette or font sizes. Every colour above already exists in `ColorScheme`, and a plugin that looks like RuneLite is the point — `spec-plugin-ux.md` §Design principles says the panel should feel native, and matching core's construction is how that is achieved rather than asserted.

---

## 2026-08-24 — icons: items yes, monsters no, and the reason is the same one that moved always_drops server-side

**Status:** research, answering "could we have pngs for mobs and drops?"

### Drops: yes, and core hands it to us

`ItemManager.getImage(itemId, quantity, stackable)` returns an `AsyncBufferedImage`. `LootTrackerBox:292` is the exact pattern:

```java
AsyncBufferedImage itemImage = itemManager.getImage(item.getId(), item.getQuantity(), item.getQuantity() > 1);
itemImage.addTo(imageLabel);
```

`addTo` is the important part — the image loads asynchronously and repaints the label when ready, so nothing blocks the Swing thread. Passing the quantity also gets the **stack number rendered into the icon** for free, which is how core shows "99" on a coin pile.

Cost to us: one `@Inject ItemManager` in the panel and a label per drop row. **This is worth doing.**

### Monsters: there is no API for it

`NPCComposition` exposes `getModels()` and `getChatheadModels()` — **3D model ids, not images**. Nothing in `rlsrc` calls `getChatheadModels` at all, and core's Slayer panel shows no monster icons either. There is no `getNpcImage` anywhere in `net.runelite.client.game`.

So a monster icon has to come from outside the client, and both routes are already closed by decisions this project has made:

1. **Bundle PNGs in the jar.** Thousands of monsters, and the images would come from the OSRS Wiki — `LICENSING.md:54` puts wiki content at **CC BY-NC-SA 3.0**, non-commercial with share-alike, against a BSD plugin. Same conflict that keeps `always_drops.tsv` off the client.
2. **Fetch at runtime.** A network call in the first Hub submission, which `PRODUCT-DIRECTION.md`'s local-only decision explicitly removed. Identical reasoning to the `always_drops` entry earlier today.

**Recommendation: item icons yes, monster icons no.** Not "not yet" — the licensing half doesn't improve with time. If monster imagery is ever wanted it belongs on the site, where wiki attribution is a page footer rather than a jar full of redistributed assets, and that is Gage's lane.

**A cheaper substitute exists if the rows need visual anchoring:** the combat level is already on every row, and grade colour is already established. Neither needs an asset pipeline.

---

## 2026-08-24 — the Hub blocker list was stale: every item on it is already gone

**Status:** verified by grep against `src/main`, not by reading the doc

Asked what was next, I went to `SUBMISSION-CHECKLIST.md` §8 expecting a session of cleanup. Checked each item against the code first:

| Blocker | Reality |
|---|---|
| `GistUploader` | file does not exist |
| `EverykillTrackerPlugin` | does not exist |
| `XpSession`, `writeSnapshot`, `killCounts` | zero references in `src/main` |
| `onNpcLootReceived` name-keyed counter | zero references |
| `Files.createDirectories` / `Files.write` on client thread | zero references |
| `new GsonBuilder()` | zero references |
| `getWorldArea()` null check | no call sites at all |
| `onAnimationChanged` temp logging | no such handler |

All eight were carried in my own context as "remaining work" across several sessions. They were removed during the rewrite and the checklist was never reconciled.

**Method note:** the boxes are ticked with the date and the evidence ("verified absent"), not just ticked. A checklist that says done without saying how it was checked is how this drifted in the first place.

**What this means for sequencing:** Step 8 is not gated behind a cleanup pass. The real remaining Hub items are ecosystem ones — stating why this isn't a duplicate of Collection Log Luck / Bossing Info / Dry Rate Tracker, and hosting under the `everykill` org — plus whatever the upload path itself introduces.

---

## 2026-08-24 — the snakeling XP theft is fixed, and the wiki explains why it happens at all

**Status:** fixed, 117 tests green — the first fully green suite since the test was written
**Sources:** [Hit delay](https://oldschool.runescape.wiki/w/Hit_delay) · [Combat](https://oldschool.runescape.wiki/w/Combat) · project `GAME-MECHANICS.md:60`

### Why the ticks disagree in the first place

I had this logged as "xp lands a tick before its hitsplat" — observed, unexplained. The wiki names the mechanic, under **Processing order delay**:

> *"Attacks will hit on different ticks depending on whether the attacking or defending entity is processed first in a tick. If the entity receiving the hit is processed earlier than the entity dealing the hit, then the hit will be delayed by an additional one tick."*
>
> *"**NPCs are processed earlier than players each tick**, so this effect will make all hits on NPCs delayed by an additional one tick."*

So the offset is not a client quirk or a race. It is the engine's turn order, it applies to **every** hit on **every** NPC, and it is therefore permanent. Any attribution built on "the xp and the damage share a tick" was always going to be wrong.

### The fix: search by fit, not by nearness

`allocateAt` walked outward from the xp tick and took the first pool that had any damage in it. In the snakeling case tick 100 held a 1-damage recoil ping, so it won — and banked 76 xp on a monster that took one point of chip damage.

The discriminator was already in our own reference file, `GAME-MECHANICS.md:60`: **4 xp per point of damage for melee and ranged, 2 for magic.** The xp amount *is* a measurement of the damage that earned it. 76 ranged xp means 19 damage. A pool holding 1 damage cannot have produced it, whatever tick it sits on.

So the search now looks for a pool whose total damage **exactly** matches `xp / rate` first, and only falls back to nearness when no pool fits.

### What is deliberately excluded

**Hitpoints and Defence return no expected damage.** Hitpoints pays 1.33 per damage — stored in tenths and rounded, so an exact integer match is not reliable — and Controlled melee pays 1.33 to three skills at once, so a Defence drop has no single rate. Those fall through to the old nearest-pool path, which is tested.

Only an **exact multiple** counts as evidence. A near-match would be a guess, and this is the module where guessing invents xp.

### Method note

Three tests, one of which exists purely to stop this passing by accident: `theFitIsWhatPicksThePoolNotTheOrderOfSearch` runs the same shape with the wrong pool placed *after* the xp instead of before. If nearness were still deciding, it would pass anyway. A test that cannot fail is not evidence.

---

## 2026-08-24 — Step 8 is client-complete and blocked on one thing: an endpoint

**Status:** the half that does not need a server is done and tested; the half that does is not started, deliberately.

**Done:** `PendingKills` (queue, batching, overflow policy, 8 tests), the config toggle with the hub's mandated warning string and a description that now lists every field sent, and `spec-kill-contract.md` brought level with the code.

**Not done, and not startable:** transport. `@Inject OkHttpClient` + `enqueue()` is twenty lines; what is missing is what to send it to. Writing a client against an imagined response shape means rewriting the retry logic once the real one lands.

**Sent to Gage 2026-08-24 22:18** (cron `67f3e1c1b5b1` → `bot-chat:gage`) asking for five things, in priority order: endpoint URL and method; response shape on partial failure (per-record or all-or-nothing — this decides whether the queue acks per batch or per kill); the auth/identity envelope; batch size and rate limits; and confirmation that dedupe is on `(account, eventId)`.

**Delivery caveat, recorded rather than assumed:** the three earlier Tyler→Gage cron messages today (`2196ac2284fe`, `946fb39286cf`, `123320ea2d8d`) all show `last_status: error` in the job table. Whether they reached him is unverified. Do not assume Gage has seen the damage-share correction, the Nex threshold correction, or the site briefing until he says so.

**Sequencing consequence:** with the snakeling fixed and the Hub blocker list found to be already clear, there is no longer any local work gating the plugin. Everything remaining either needs Gage (transport) or is a feature rather than a fix (Session tab: kills/hr, xp/hr, supplies, task — spec-plugin-ux 1b).

---

## 2026-08-24 — cron delivery to Gage is 0 for 4; stop using it for agent-to-agent messages

**Status:** verified failure, four times, same day. Method changed.

Every Tyler→Gage message today went out as a one-shot cron to `bot-chat:gage`, and every
one of them failed:

| Job | Subject | Outcome |
|---|---|---|
| `2196ac2284fe` | site direction briefing | `last_status: error` |
| `946fb39286cf` | correction #1, damage share | `last_status: error` |
| `123320ea2d8d` | correction #2, Nex threshold | `last_status: error` |
| `67f3e1c1b5b1` | ingest contract request | dispatch claimed, run never completed, job auto-removed |

The last one is the informative failure: *"dispatch was claimed, but the run never
completed (`last_run_at` was never written) — the scheduler process was most likely
killed or restarted mid-execution."* Cron fires inside the RuneLite dev-client sessions
we keep starting and killing, and a one-shot job whose scheduler dies mid-run is simply
lost.

**The trap:** the create call returns `success: true` and the job appears in the table.
Nothing about scheduling a cron tells you the message will arrive. I reported "sent to
Gage" three times today on that basis. It was never sent.

**New method:** agent-to-agent content goes in a **file in the repo** — here,
`docs/for-gage-ingest-contract.md` — and the human carries the pointer. A file cannot
half-deliver, it survives a killed process, and Gage can read it whenever he starts.
Cron stays fine for scheduled *work*; it is not a message bus.

**Consequence:** assume Gage has seen none of today's plugin-side corrections. The
damage-share correction and the Nex threshold correction are repeated in the file for
that reason, not because they're new.

---

## 2026-08-24 — monster icons are possible after all: draw an ITEM, not the NPC

**Status:** shipped. Corrects my own FINDINGS entry from earlier today.

Earlier today I recorded that monster images were impossible in the plugin, permanently,
for two reasons: no NPC image API (`NPCComposition.getModels()` returns 3D model ids and
nothing in core renders one to a panel), and wiki PNGs being CC BY-NC-SA against a BSD
plugin.

**Both facts are still true. The conclusion was wrong.** Delk shipped monster icons in
the Slayer Alternatives plugin and showed me the screenshot.

**The trick:** don't render the NPC. Render an **item** that reads as the monster — an
ensouled head, its unique drop, a slayer item. `ItemManager.getImage(id, 1, false)` is
the same call already used for drop icons, so it costs nothing new and stays inside the
licence.

I had framed the question as "can we get a picture of this monster" and answered that
correctly. The right question was "can we put a recognisable image next to this monster's
name", which has a completely different answer.

**Carried across:** `icons.json`, 238 hand-mapped names, from `slayer-alternatives` —
same author, same BSD-2-Clause licence, so reuse is clean. Landed as
`src/main/resources/com/everykill/npc-icons.json` + `ui/NpcIcons.java`.

Missing entries are the normal case (238 names against thousands of npc ids) and must
stay cheap: `forName` returns -1, the row draws no icon and starts at the name. A
singular fallback handles "Giant rats" → "Giant rat".

**Method note:** when a teammate demonstrates something you recorded as impossible, read
their code before re-arguing the point. The answer was 78 lines and one field name away.

---

## 2026-08-25 — transport landed, and the live server caught a bug review would not have

**Status:** verified against the running reference server, not just compiled. 139 tests green.

**Built:** `UploadIdentity` (client id + token on disk under `.runelite/everykill-plugin/`),
`UploadClient` (OkHttp, `enqueue()` only), `UploadService` (scheduled flush, registration,
queue ownership), `UploadGson`, and the `uploadUrl` config item.

### The bug

First real request to `POST /v1/kills` came back:

```
"status": "rejected", "reason": "grade 'UNCONTESTED' is not a known grade"
```

Java enum constants serialise as `UNCONTESTED`. `spec-kill-contract.md:35` reads
`uncontested` — and has since it was written. **Every kill this plugin ever uploaded
would have been rejected**, silently, because a rejection is a per-record verdict inside
a `200`. The queue would have drained happily and stored nothing.

Nothing about this is visible in a compile, a unit test written against my own
assumptions, or a code review. It took one real request.

`UploadGson` derives from the **injected** Gson via `newBuilder()` and lowercases
`Confidence` and `LootConfidence` — but **not** `DeathSignal`, which the contract prints
upper case. One blanket rule would have looked tidier and broken the other field.

### Gage's correction to the auth design, adopted

I had specified a salted hash of the account computed client-side. He rejected it
correctly: the plugin is open source, so the salt ships in a public jar, RSNs are not
secret and there are few of them — anyone could hash the hiscores and reverse the whole
database. *"A public salt is not a salt."*

Built his way instead: random 128-bit client id, traded for a bearer token, **the RSN
never leaves the client.** `thereIsNoPlayerFieldOnTheWire` is a test rather than a
comment.

The cost is real and the panel must state it: with no RSN on file, losing
`identity.properties` orphans that history permanently, and the recovery code is minted
exactly once.

### Verified live, not assumed

- register returns `stored: true` (the real server, not the Worker stub)
- second register: `returning: true`, `recoveryCode: null` — idempotent, code minted once
- a correctly-shaped kill: `accepted: 1`, with `dryness.reason: "confirmed_loot"`
- the same `eventId` resent: `duplicate: 1`, nothing written twice

### Design notes worth keeping

**A first run writes nothing to disk.** The client id is only persisted once a token
comes back, so a failed registration cannot burn an id and strand a history that never
existed.

**`clearToken()` keeps the client id.** Register is idempotent on the id, so a dead token
re-registers into the *same* history. Losing the id instead is unrecoverable.

**`offer()` drops kills when upload is off**, rather than queueing them. Queueing would
mean toggling upload on uploads a backlog the user never agreed to send.

**The whole batch is always acked**, including rejections — confirmed by Gage against my
actual `PendingKills` code. Holding a rejected record back parks it at the head of the
queue forever and wedges every future batch behind it.

---

## 2026-08-25 — Step 8 verified: real kills reached the server with nobody driving

**Status:** ✅ verified live. Three kills went detector → ledger → queue → HTTP → SQLite
with no curl, no pasted JSON, and no hand-holding.

Evidence, read out of `everykill-site/api/everykill.db` rather than from a log line:

```
event_id                              npc      grade         myDmg  fightTicks  loot
5d6bbbf0-95d6-4e31-8725-6a10171ddaad  Cyclops  uncontested   76     79          confirmed
b1c8c90c-4e5b-4bba-a390-cc65e0b7f0a7  Cyclops  uncontested   76     47          confirmed
9d30d905-db27-4da9-bfc4-2d3c7196cc13  Cyclops  uncontested   76     39          confirmed
```

**How we know these are real and not another curl test.** My two test records carry
hand-typed ids (`tyler-fixed-1`, `live-plugin-token-1`) and identical values — 75 damage,
12 ticks, every time, because I typed them once and reused the string. These three carry
**client-generated UUIDs**, and their fight lengths differ: 79, 47 and 39 ticks. Nothing
I could paste produces varying fight lengths, because `fightTicks` is measured from our
first damage to the kill resolving. The variation *is* the proof.

Drops arrived intact and correctly attributed per kill:

| kill | drops |
|---|---|
| 5d6bbbf0 | Big bones ×1, Coins ×47 |
| b1c8c90c | Big bones ×1, Black longsword ×1 |
| 9d30d905 | Big bones ×1, Uncut sapphire ×1 |

Every cyclops dropped Big bones plus one rolled item — exactly the pattern measured
during Step 6 live verification, now surviving the whole pipeline.

Dryness resolved server-side on all three: `counts_as_dry=0`, `in_denominator=1`,
`reason=confirmed_loot`.

**What this closes.** Every link is now proven end to end: kill detection, damage
attribution, loot capture, the account-type gate, queue and batching, registration,
bearer auth, the wire format, and ingest. The chain that was theoretical at midnight is
measured.

**Method note.** The verification came from querying the server's own database, not from
reading `"accepted": 1` in a response. A response says the server replied; a row says the
data is there. Those differ whenever anything between them is lying, which is exactly
when you need to know.
