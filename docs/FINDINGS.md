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
