# Spec — Kill detection

How the plugin decides a kill happened. Everything else depends on this being right.

**"Health ratio zero" and "dead" are not the same state in the RuneLite API.** `ActorDeath` and `NPC.isDead()` both key off health ratio hitting zero, but the underlying game can put an NPC at 0 HP while it is still alive and interactable — transform-death monsters (edge case A) and phase/invulnerability transitions on bosses (edge case B) both do this. Three sections of this spec originally assumed health-ratio-zero and dead were interchangeable; they are not, and every rule below that touches `ActorDeath` or `isDead()` needs to be read with that distinction in mind. See edge cases A and B for the verified findings and the corrected detection rules.

## Available signals

| Signal | What it gives | Caveat |
|---|---|---|
| `ActorDeath` | An actor died | Fires for **every** NPC in the loaded scene, including other players' kills. Never a trigger alone. |
| `NPC.isDead()` | RuneLite flags dead when health ratio hits 0 | RuneLite core's XP Tracker uses `onNpcDespawned → if (!npc.isDead()) return;` — same problem, same solution |
| `HitsplatApplied` | Per-hitsplat damage with an `isMine()` flag | **The attribution signal.** This is what separates our kills from strangers' |
| `NpcDespawned` | NPC left the scene | Fires on death, walking off-scene, region change, hop, logout. Not a kill signal |
| `NpcChanged` | Same actor, new NPC ID | Phase transitions |
| `NpcManager.getHealth()` | Max HP by name + combat level | Health only — does **not** expose combat stats |
| `StatChanged` | XP deltas per skill | Merged across simultaneous kills. Checksum only. **Stops firing informatively once a skill is XP-capped at 200M** — see note below |
| `FakeXpDrop` | Same shape as a real XP gain, without changing `getSkillExperience()` | The signal a 200M-XP-capped skill still emits. Verified in `InstantDamageCalculator`'s source (`FINDINGS.md` 2026-08-14) — it subscribes to both this and `StatChanged` for exactly this reason |

## The algorithm — damage-first

Open a record when we hit something; close it when that thing dies.

1. **Open a combat record** keyed on the NPC's runtime reference, on the first hitsplat of ours. Store spawn tick, `npc_id`, region, open a damage counter.
2. **On `HitsplatApplied`** where `isMine()`, add to `damage_by_player`. Track `damage_total` separately including foreign hitsplats.
3. **On `ActorDeath`** for an NPC we have a record for → emit a kill. No record, or zero damage → ignore entirely.
4. **On `NpcDespawned` with `isDead()`** and no `ActorDeath` already seen → emit a kill (fallback path).
5. **On `NpcDespawned` without `isDead()`** → transform-death check (below). Otherwise discard the record silently.
6. **On `NpcChanged`** → carry the record forward to the new ID **without emitting**. Phase transition, not a kill.
7. **On `GameState` change** (hop, loading, login screen) → drop every open record without emitting. Never guess through a scene reload.

### Confidence grading

Written to every event.

| Grade | Conditions | Used for |
|---|---|---|
| `exact` | `ActorDeath` fired · we dealt 100% of damage · single kill that tick · no cannon/thrall · derived XP reconciles | Benchmarks, drop rates |
| `inferred` | Transform-death fallback · multi-kill tick · plugin loaded mid-fight · partial damage but clearly the killer | Personal totals only |
| `ambiguous` | Other players dealt damage · cannon/thrall contributed · AoE · scaled-HP instance | Excluded from published rates |

## XP derivation

Combat XP is paid **per point of damage**, not per corpse. Since damage is tracked per NPC, derive each NPC's XP from its own damage record. The merged-tick problem never arises.

### Confirmed rates (OSRS Wiki, Aug 2026)

| Style | Per point of damage |
|---|---|
| Melee / Ranged, standard | 4 to style skill · 1.33 Hitpoints |
| Magic, standard | 2 Magic · 1.33 Hitpoints · **plus flat cast XP, paid even on a splash** |
| Magic, defensive | 1.33 Magic · 1 Defence · plus cast XP |
| Ranged, long range | 2 Ranged · 2 Defence |
| Melee, shared (whip, spear) | 1.33 each to Attack, Strength, Defence |
| Slayer, on task | monster Hitpoints × its XP bonus multiplier |

### The PvM bonus multiplier

Some monsters pay increased XP. The multiplier derives from the monster's own stats and applies to Attack, Strength, Defence, Ranged, Magic, Hitpoints **and Slayer**.

```
AverageLevel     = floor((Attack + Strength + Defence + min(Hitpoints, 2000)) / 4)
AverageDefBonus  = floor((StabDef + SlashDef + CrushDef) / 3)
multiplier       = 1 + (1/40) * floor(39 * AverageLevel *
                   (AverageDefBonus + StrengthBonus + AttackBonus) / 200000)
```

A handful of monsters have manually defined bonuses that ignore the formula (Great Olm gives none) — hence the manual override column in the stat table.

**This requires the NPC stat table.** `NpcManager` exposes health only. Nothing multiplier-dependent can run for a monster missing from that table.

## Non-XP damage — observe first, deduct second

Most non-XP damage is **directly observable** and must never be inferred:

| Source | How it's observed |
|---|---|
| Poison, venom, burn | Distinct hitsplat types |
| Ring of recoil | Equipment state (loadout snapshot) |
| Retribution | Prayer bitmask |
| Thralls, cannon | Visible entities in the scene |
| Other players | `isMine()` is false |

The XP subtraction is a **residual check on that labelling**, not a source of truth:

```
xp_paying_damage = hp_xp_delta / (1.333 * npc_multiplier)
residual         = our_hitsplat_total - xp_paying_damage - labelled_non_xp_damage
```

- Residual near zero → everything accounted for → `exact`
- Residual matches a labelled source → `inferred`
- Residual unexplained → `ambiguous`, and store the residual as its own field

Use **Hitpoints XP** as the instrument — it's style-independent and has no flat cast component. Magic XP includes cast XP paid on splashes and is unusable for this.

**The deduction never rewrites a stored value. It classifies, it never corrects.**

### Gating — all conditions must hold before the deduction runs

- Exactly **one** of our NPCs took damage in the window (merged HP XP can't be split)
- The NPC has **verified stats** in the table (multiplier known, not assumed)
- Not scaled-HP content (no CoX, no ToA, no self-healing monsters)
- No foreign hitsplats on the target
- Normal world only
- If Magic was used, cast count is known so flat cast XP can be subtracted first
- No sequence gaps in the session
- XP settle window elapsed (XP can land a tick after the damage)

Fail any condition → **skip the deduction**, don't approximate. A skipped deduction costs one confidence grade. A wrong one poisons a benchmark.

### Noise floor

1.33 is 4/3 and XP is stored at higher precision than it displays. Rounding accumulates across a kill, so small residuals are arithmetic noise. Set a floor below which residuals are ignored — **measure it from real data**, don't pick a number up front.

## Edge case audit

### A. Transform deaths — die without being flagged dead

These monsters must be finished with a specific item. **Corrected 2026-08-14 (was wrong):** the original assumption here was that these NPCs "despawn without ever being flagged dead" — i.e. that `ActorDeath`/`isDead()` simply never fire for them. Live testing on rockslugs contradicted this directly: `ActorDeath` **does** fire for these NPCs, but at the moment their health ratio hits zero, not when they actually die. A rockslug at 0 HP without salt is confirmed (by direct observation, not inference) to still be alive and immobile, requiring the item to actually finish it off — yet the client fired `ActorDeath` for it anyway, and a naive implementation would have logged a false kill.

**The corrected rule: for NPCs on this list, `ActorDeath` and `isDead()` must never be trusted as death signals, under any circumstance** — not even as a preferred path with this as a fallback. The only valid signal is an actual despawn with an item spawning on the NPC's tile(s) that same tick. This is checked unconditionally for every NPC on the list, regardless of whether `ActorDeath` fired earlier for that actor.

**Verified 2026-08-14: whether `ActorDeath` fires at all is nondeterministic relative to when the finishing item is used**, on two consecutive salted rockslug kills:
- Salt used *before* combat brought it to 0 HP → no `ActorDeath` at all, straight to the despawn+item-coincidence kill.
- Salt used *after* it sat at 0 HP for a moment → `ActorDeath` fired first (correctly ignored), then the same despawn+item-coincidence kill.

Both resolved to exactly one kill either way, and a third test (salt withheld entirely) despawned with no coincident item spawn and correctly produced zero kills despite `ActorDeath` having fired. This confirms the corrected rule handles both orderings and the true-negative case, not just the ordering the bug was first caught on.

**Known structural limitation (was "known gap, not yet mitigated" — corrected 2026-08-14): loot-coincidence detection cannot be the sole signal for this category, permanently, not just until we get around to a fix.** Confirmed against the live wiki drop table: Rockslug's drop table has a **"Nothing" outcome** (removed only if a Ring of Wealth is equipped) — nested inside a gem sub-table that's itself only reached on roughly a 6/128 chance, so the true lootless rate is low but structurally nonzero, not zero. A salted kill that resolves to that outcome despawns with no item on its tile and is silently uncounted — logged as `counted=false`, but not counted. This generalises past rockslugs: **any transform-death monster whose drop table includes a "nothing" entry has the identical hole**, so item coincidence alone can never be a complete signal for this category — it needs a second, loot-independent signal (death animation, or another mechanism) to actually close.

First empirical read: 0/25 salted rockslug kills came back `counted=false` in the first real-play sample. At a rate this low (nested behind a ~6/128 sub-table roll), **a dedicated N-kill test can't distinguish a real rate from zero** — even 50 kills expects roughly one miss, so 0/25 was the predicted outcome either way, not evidence for or against the gap existing. A dedicated test was considered and dropped for this reason.

**Measurement approach: passive, not a dedicated test.** The unconditional `counted=false` logging added for this investigation is being left in permanently, and the sample accumulates from normal play over weeks rather than a single planned session. This is a deliberate choice, not a placeholder for "get around to it later" — **the fix is justified by the drop table alone, independent of whatever rate normal play eventually shows.** A structural hole that produces a false negative doesn't need a measured rate to justify closing; the passive log just tells us how urgently, and gives real data instead of trusting the wiki fraction to transfer cleanly to observed behaviour.

Two candidate fixes, in the order they're being pursued:

1. **Death animation (in progress).** The approach RuneLite itself uses for Kraken/Huey (see below). Requires discovering the actual death-animation ID for each transform-death NPC first — not available anywhere in RuneLite's own source for this list, but cheap to do now with a temporary `AnimationChanged` debug subscriber logging animation id + health ratio + tick for every NPC on the list, rather than eyeballing Developer Tools' live panel and risking missing a brief transition.

   **Acceptance criterion — this needs a negative control, not just a candidate ID.** A salted kill will show *some* animation id on the despawn tick, but that alone doesn't prove it's a death animation rather than a low-health/idle one. **An animation id only qualifies as the real death signal if it appears on finished (salted) kills AND is absent on a deliberate unsalted wander-off of the same monster.** If the same candidate id shows up in both cases, it's useless as a death signal — the wander-off case is a plain low-health animation, not a death one, and the candidate needs to be discarded rather than wired in on a false positive match. Test plan: salt several, leave at least one unsalted deliberately, compare the animation ids logged in each case.
2. **Item consumption + coincident despawn (deferred, not rejected).** The finishing item (bag of salt, ice cooler, etc.) being consumed via `ItemContainerChanged`, combined with the NPC despawning that same tick, is a real and item-independent signal — consumption alone isn't sufficient (the item can be used above 4 HP with no effect and still be consumed), but consumption plus a coincident despawn narrows that considerably. **Deliberately held back rather than implemented alongside the animation work**, so we don't end up stacking two half-verified mitigations and losing the ability to tell which one is doing the work. Known holes to resolve before implementing: (a) false-positive risk if salt is wasted above 4 HP the same tick a *different* rockslug happens to despawn nearby; (b) with the Slug salter perk and a stacked bag of salt, the inventory change surfaces as a quantity delta rather than a clean single-consumption event, so "consumed" itself needs a more careful definition than "count went down by 1." Revisit once the animation approach has been tried and measured — only add this if the animation ID turns out to be unreliable or unavailable for some monster in the category.

### A1. ID-list audit — confirming the transform-death list has no gaps

A gap in `TransformDeathNpcs.IDS` is silent and dangerous: any monster variant missing from the list falls straight through to the normal `ActorDeath` path and reproduces the exact false-positive bug this edge case was written to fix, with no warning.

**Verified 2026-08-14, against the client's own `NpcID` source, not the wiki.** An initial attempt to verify the list via a summarised wiki fetch produced concrete factual errors when cross-checked against the client source already extracted earlier in this investigation — it mislabelled `SLAYER_ROCKSLUG_BABY` (422) as "Giant rockslug (superior slayer monster)," which is actually a different NPC entirely (`SUPERIOR_ROCKSLUG`, 7392). **`NpcID` in the actual client jar is the ground truth for "does this ID exist and share the naming family"; a summarised wiki fetch is not reliable enough to trust for a list this load-bearing.** `npc.getName()` in the live kill/despawn logs remains the authority on what a given ID's real in-game display name is.

Grepping `NpcID` for every `ROCKSLUG`, `LIZARD`, `GARGOYLE`, and `ZYGOMITE` constant and cross-referencing against the existing list found four rockslug/lizard gaps and three gargoyle gaps, all now added:

- `SLAYER_ROCKSLUG_CRYPT_OF_TONALI` (14423) — a Varlamore location variant, missing entirely.
- `LEAGUE_SUPERIOR_ROCKSLUG` (12561), `LEAGUE_SUPERIOR_GARGOYLE` (12578), `LEAGUE_SUPERIOR_GARGOYLE_DEAD` (12579) — Leagues-mode variants. Still worth correct detection even though Leagues periods are excluded from published rates (edge case J / `world_flags[]`) — personal totals and the review queue still matter on those worlds.
- `SLAYER_LIZARD_LARGE1_GREEN_LOWRANGE` (12003), `SLAYER_LIZARD_SMALL1_GREEN_LOWRANGE` (12004) — a "lowrange" variant, purpose unconfirmed (likely a reduced-aggro-range instance clone), added on the strength of the naming pattern match rather than confirmed content knowledge.

**Deliberately excluded, but genuinely unverified rather than safely assumed:** the zygomite `_CAP` variants (`FOSSIL_ZYGOMITE_CAP`, `SLAYER_MUTATED_ZYGOMITE_ADOLESCENT_CAP*`, `SLAYER_MUTATED_ZYGOMITE_ADULT_CAP*`). These look like the pre-combat capped-mushroom stage you interact with to spawn the real creature. Two outcomes are possible and only one is harmless: if the capped form is attackable and transforms into the real zygomite via `NpcChanged`, the combat record carries forward and this exclusion is fine. If instead it despawns and a separate NPC spawns in its place, the record is lost and the kill goes unrecorded entirely — a silent miss, not a graceful fallback. **Cannot be tested on this account (needs 57 Slayer).** Flagged as unverified, not assumed safe, until someone can.

**Correction 2026-08-14 — the review queue cannot actually catch a new transform-death monster, and the name-hint flag doesn't fix that.** Originally reasoned that an unlisted monster's despawn would hit the review-queue log line. It doesn't: for an unlisted NPC, `ActorDeath` fires early (same bug as rockslugs), a kill gets emitted immediately via the normal `ActorDeath` path, and the record is marked `actorDeathSeen`. When the NPC *actually* despawns later, `onNpcDespawned` sees `actorDeathSeen == true` and takes the "already killed" branch — the review-queue branch is never reached. `possibleTransformDeathGap` only helps for variants of *already-known* families anyway; a genuinely novel monster with an unfamiliar name matches no hint at all. The review queue only catches the (rarer) case where a monster despawns with damage on it and *no* death signal ever fired — it was never actually capable of catching the "false `ActorDeath` fired" case, which is the one that matters.

### A2. Post-ActorDeath survival check — a general, list-independent detector

Since the review queue can't catch this, the real fix is checking whether the actor a `ActorDeath` fired for is actually gone afterward — a real kill means it's gone; a false one means it isn't. This is list-independent (works on monsters never seen before) and applies to both edge case A (transform deaths) and edge case B (phase transitions — Kalphite Queen, Zalcano) with the same one mechanism, since both produce the identical signature: `ActorDeath` fires, but the actor keeps existing.

**Implemented 2026-08-14 as logging only, no behaviour change**, specifically to see what "normal" delayed-despawn timing looks like across many ordinary kills before any threshold gets used for a real decision:

- `CombatRecord.actorDeathTick` records the tick `ActorDeath` fired, for every NPC, not just transform-death-list ones.
- Any further hitsplat landing on an actor after `ActorDeath` already fired for it logs `Suspected post-ActorDeath hitsplat`, with `ticksSinceActorDeath`, the hitsplat amount, and whether it's ours.
- Every despawn confirmation (`Despawn also fired for an already-killed npc`, and the transform-list `Transform despawn` line) now includes `ticksSinceActorDeath`, building a baseline distribution for what a normal death-animation-then-despawn delay looks like, so an outlier on an unlisted NPC later becomes recognisable rather than needing a guessed threshold.

**Outstanding:** accumulate enough normal-kill data to know what a normal `ticksSinceActorDeath` range actually is before this influences any real detection or grading decision. Not yet tested on a phase-transition boss (accessible bosses limited on this account, same constraint as `BUILD-ORDER.md` Step 4).

**External corroboration (checked 2026-08-14):** this is a known, long-open gap in the RuneLite ecosystem, not something specific to our plugin. [runelite/runelite#12453](https://github.com/runelite/runelite/issues/12453) reports NPC Indicators' "ignore dead NPCs" option hiding gargoyles before they're actually smashed, because they read 0 HP without being dead — the reporter explicitly notes this likely affects the whole Grotesque Guardians fight too. That issue is about a different plugin (NPC Indicators/Entity Hider) reading the same underlying health-ratio-zero state we observed `ActorDeath` fire on, not a direct report against `ActorDeath` itself — but it's the same root cause: the client does not distinguish "health ratio zero" from "actually dead" for these NPCs, and nothing in RuneLite core has fixed it.

- Gargoyle, Marble gargoyle (rock hammer)
- Rockslug, Giant rockslug (bag of salt)
- Small lizard, Desert lizard, Lizard (ice cooler)
- Zygomite, Ancient zygomite (fungicide)
- Dusk (Grotesque Guardians)

**Detection: coincident item spawn with the despawn.** This is RuneLite's approach and it's better than checking health, because a rockslug reduced to 1 HP without salt *stops retaliating and wanders off* — dropping nothing, so it self-filters. A "low HP + despawn = kill" heuristic would produce false positives.

Secondary confirmation: the finishing action (`MenuOptionClicked` item use, or `AnimationChanged`). Note both have auto-perks — Gargoyle Smasher (120 slayer points) and Slug salter (10 points). A brine sabre kills rockslugs normally, so those take the standard path. **Unverified as of 2026-08-14:** whether "normally" here actually means a real `ActorDeath`/`isDead()` death, or the same false-fire-at-0-HP behaviour — not yet tested with a brine sabre.

**A stronger signal than item coincidence, where available: death animation.** RuneLite's own `LootManager` keeps a small hardcoded map (`NPC_DEATH_ANIMATIONS`) checked via `AnimationChanged`, for exactly two NPCs as of client 1.12.35 — the Slayer Kraken (`AnimationID.SWAN_QUEEN_DEATH`) and Huey/Fortis Colosseum (`AnimationID.HUEY_KNOCKOUT`) — used instead of trusting `ActorDeath`/`isDead()` for those two. This is a smaller list than initially assumed (not "Gauntlet NPCs" — verified directly against the client source, no Gauntlet/Hunllef entries exist in that map). The pattern generalises: where a monster's real death animation is known, require it as the kill signal instead of item coincidence; where it isn't known, keep falling back to despawn + coincident item spawn, graded `inferred`. This needs a place to live — see the note under edge case B and the NPC stat table in `spec-data-model.md`, since the table doesn't exist yet (`BUILD-ORDER.md` Step 0a is unbuilt) and a `death_animation_id` column is a design decision for that table, not something to bolt onto this list ad hoc.

Unknown NPCs despawning at low HP after our damage → **flagged-for-review queue**, never an auto-count.

### B. Phase transitions — one kill, many IDs

Multi-phase bosses change NPC ID mid-fight via `NpcChanged`. Naive counting records a kill per phase.

- Kalphite Queen (two forms — only the second death is real)
- Zulrah, Vorkath, Alchemical Hydra, Abyssal Sire, The Nightmare
- Kraken (whirlpool becomes the kraken)

**But:** superior slayer monsters spawn as a *separate* NPC on a normal kill — genuinely two kills. Nechryael death spawns likewise. `NpcChanged` carries a record forward; a genuinely new spawn opens its own record.

**This edge case has the same false-positive exposure as edge case A, and it's wider than transform-death monsters.** Checked 2026-08-14 alongside the rockslug finding: [runelite/runelite#15394](https://github.com/runelite/runelite/issues/15394) reports Kalphite Queen reading as dead (via Entity Hider's "hide dead NPCs") mid-fight while still alive — "KQ is one of those NPCs that can be red-barred on health but still be alive somehow." [runelite/runelite#16479](https://github.com/runelite/runelite/issues/16479) reports the same for Zalcano, theorised as hitting 0 HP and regenerating on the same tick during an invulnerable/knockdown phase. Both are the identical root cause as edge case A: health ratio zero without an actual death, just triggered by a phase/invulnerability mechanic instead of a required finishing item.

**First empirical confirmation, 2026-08-14 (rock/sand crabs — see `FINDINGS.md`):** the "same actor, new id" assumption this whole edge case rests on is now confirmed for at least one real transform, not just asserted. Identity-tagged discovery logging on 7 dormant-rock-to-crab wake-ups showed `NpcChanged` firing every time, with the actor's `identityHashCode` unchanged across the transform in all 7 — no despawn+spawn pair observed once. **This is not evidence about real phase bosses** — a crab's wake-up has no health-based phase transition, so it doesn't exercise the specific failure mode this edge case exists for (false `ActorDeath` mid-transition). KQ/Zulrah/Vorkath/Alchemical Hydra remain untested, per `BUILD-ORDER.md`'s deferred list.

**Sharper finding from the follow-up wake-and-kill test, same day:** a dormant rock can't be attacked, so combat can only start *after* the wake-up finishes — in all 4 kills traced, `NpcChanged` completed 2–3 ticks before the first hitsplat. That means the record-gated branch in `onNpcChanged` (carrying an *already-open* record through a live transform, and clearing a false `actorDeathSeen`) never executed once, and structurally cannot be exercised by a crab at all — the wake always precedes combat for this monster, never interrupts it. **The crab tests confirm the foundational same-actor assumption but cannot substitute for a real phase-boss test of the carry-forward branch itself.** That code remains genuinely unexercised until KQ/Zulrah/Vorkath/Alchemical Hydra (or an equivalent) is reachable.

**Consequence for Step 4 — built 2026-08-14, reusing edge case A2 rather than a separate guard.** A naive `NpcChanged`-carries-the-record-forward implementation would not be sufficient on its own if `ActorDeath` can fire mid-fight on a phase boss the same way it fired on the rockslug — so `onNpcChanged` doesn't just carry the record forward, it checks `CombatRecord.actorDeathSeen` (the A2 survival-check field, already list-independent by design) and, if set, treats the `NpcChanged` itself as proof the preceding `ActorDeath` was false: clears the flag, logs the sequence, retargets the record to the new phase, and never emits a kill. One mechanism now covers both edge cases A and A2's stated purpose. See `FINDINGS.md` 2026-08-14 ("Step 4 built on the existing post-ActorDeath survival detector").

**Still an open gap, not covered by an id-change handler at all:** the Zalcano report (`#16479`) theorises health hitting zero and regenerating on the *same* npc_id during an invulnerable/knockdown phase — no `NpcChanged` fires in that case, so `onNpcChanged` has nothing to key off. That would still require either a known death-animation check (preferred, see edge case A) or a same-id "despawn must be the final phase" requirement, neither of which is built. Untested either way — no accessible multi-phase boss on this account. Do not assume this gap is closed; only the id-changes-mid-fight case (Kalphite Queen, Kraken, etc.) has a mechanism now.

### C. Other people's kills — biggest overcount risk

`ActorDeath` fires scene-wide. In a busy dungeon most nearby deaths aren't ours. Fully solved by the damage-first design — no record, no event.

### D. Shared and assisted damage

Another player finishing our mob (or vice versa), cannon (ours, XP-paying, not a hand kill), thralls (damage, no XP, still ours), group content, AoE (chinning, barrage). Record `kill_source` and damage share, grade `ambiguous`, exclude from published rates, keep in personal totals.

### E. Despawns that aren't deaths

Scene exit, region change, hop, teleport, player death, logout, disconnect. Handled by dropping open records on `GameState` change.

### F. Multi-kill ticks

Cannon, chinchompas and barrage kill several NPCs in one tick. RuneLite's Slayer plugin needed dedicated multikill handling. Per-actor records handle it correctly; grade the tick `inferred` since the XP checksum can't validate individually.

### G. Variable HP — breaks all HP maths

Chambers of Xeric scales monster HP by party size and levels. Tombs of Amascut scales by invocation. Self-healing monsters. **Exclude from HP-derived validation entirely**, including the anti-cheat ceiling. Flag via `has_scaled_hp`.

**Note (2026-08-14):** `InstantDamageCalculator`'s source shows ToA's scaling is actually formulaic and derivable client-side — it reads raid level, path level and live party size from varbits/widgets and computes a real per-NPC multiplier at runtime (see `FINDINGS.md`). That means this blanket exclusion is more conservative than strictly necessary for ToA specifically, and loosening it for that one raid would be a legitimate, deliberate design change. **Recorded, not acted on:** raid drop tracking is explicitly out of scope per `docs/PROJECT.md` ("Explicitly out of scope"), which makes this moot for now regardless of whether it's technically loosenable. If raids are ever brought back into scope, this is the note to revisit first, rather than re-deriving the same conclusion from scratch.

### H. Not-really-combat kills

POH combat dummies (real XP, meaningless as kills — needs **explicit exclusion**), pickpocket knockouts, implings, Herbiboar, Barbarian Assault penance, Pest Control portals, minigame NPCs, random events.

### I. Client reliability

Plugin enabled mid-fight (record has no start → `inferred`), client restart mid-task, dropped ticks, logout during a kill animation. Sequence gaps disqualify official status for that stretch. Degrade visibly, never guess.

### J. Rule-changing worlds

Leagues relics alter XP rates outright; Deadman, tournament and beta worlds change balance. Handled by `world_flags[]` on the session with all views defaulting to normal worlds. A single unfiltered Leagues period would quietly ruin every published rate.

### K. XP-capped skills — 200M Hitpoints

**Added 2026-08-14, from reading `InstantDamageCalculator`'s source (see `FINDINGS.md`).** Once a player's Hitpoints skill reaches the 200M XP cap, `StatChanged` may stop being a reliable signal for further Hitpoints XP gains — IDC subscribes to both `StatChanged` and `FakeXpDrop` for Hitpoints specifically, with a comment stating the latter is needed for 200M-XP players. Step 5's derived-XP pipeline currently only listens for `StatChanged`; without a `FakeXpDrop` subscriber, a 200M-Hitpoints account would silently stop producing derived XP (and therefore the residual/confidence-grading checksum) for every kill from that point on — not a crash, not a visible error, just quietly wrong. Cheap to handle, and exactly the kind of gap that would otherwise only surface as an unexplained anomaly much later. **Required for Step 5, not deferred:** add a `FakeXpDrop` subscriber alongside `StatChanged` before Step 5 is considered complete. Whether the underlying client behaviour actually works the way IDC's code implies is still unverified on our side — see the corresponding entry in "Unverified assumptions" below.

## Unverified assumptions — confirm empirically

- ~~Whether `ActorDeath` and the `isDead` despawn can **both** fire for one kill~~ — **Verified 2026-08-14** for normal (non-transform-death) NPCs: both fired, `ActorDeath` first, for 11/11 consecutive Goblin kills, correctly deduped every time.
- Exact XP settle window in ticks
- Noise floor for residuals
- Whether cannon and thrall hitsplats report as `isMine()`
- Full combat dummy / minigame exclusion list
- Which monsters have manually defined XP bonuses beyond Great Olm
- Whether `ActorDeath` can fire **twice** for the same transform-death NPC — e.g. a rockslug sits at 0 HP, regenerates without being salted, is damaged back down to 0 HP again. Does the client fire a second `ActorDeath` for the same actor reference? Our despawn-gated logic should be immune either way (only the coincident-item-spawn despawn counts), but the underlying client behaviour itself is unconfirmed.
- Whether `StatChanged` actually goes unreliable for Hitpoints past the 200M XP cap, or `FakeXpDrop` is merely an extra signal IDC uses defensively — see edge case K. Not tested on our side; cannot be tested on this account regardless (nowhere near 200M Hitpoints XP).
