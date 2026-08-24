# Everykill

RuneLite plugin that tracks **per-monster** XP, kill counts and drops. A companion hiscore website consumes what it records; **that site is specced outside this repo** and nothing here depends on it existing.

The RuneLite coding conventions imported above are authoritative and take precedence over anything below. This file adds project-specific context on top of them.

> **Naming:** the project is now called **Everykill** (everykill.com registered). The codebase still uses `everykill` / `everykill-plugin` throughout. **Do not rename anything until explicitly told to** — the config group rename in particular must be a clean, deliberate task, and it must land before any real user data exists, since renaming a config group silently resets user settings.

## Why this exists

Official OSRS hiscores cover ~90 bosses and nothing else. Regular monsters — every slayer task, every training mob — have **no kill counter and no XP attribution anywhere in the game or its APIs**. Wise Old Man and TempleOSRS both mirror the official hiscores and inherit the same blind spot.

**The precise claim** (verified against the full Plugin Hub, Aug 2026 — do not overstate it):

- Kill tracking, dryness tracking and drop-chance calculators **already exist and are popular** — but every one is scoped to a single boss, raid or minigame
- Nobody tracks **all monsters uniformly under one schema**
- Nobody publishes **observed** rates from real kills rather than published estimates
- Nobody does **cross-player** comparison at the per-mob level
- Nobody attempts **verified-from-zero** kill counts

**Integrity is the product.** A public leaderboard whose numbers can't be trusted is worth nothing. Every design decision favours a smaller clean dataset over a larger dirty one.

## Current state

- Source tree is now `com.everykill`. The old `everykill` package — including the legacy
  JSON-snapshot writer and its GitHub gist upload — was deleted on 2026-08-20.
- Steps 1–4 confirmed in-game before the rewrite (see `docs/FINDINGS.md`).
- Step 5 is **built but unproven**: XP is measured from `StatChanged` and allocated by
  damage share. The step was rewritten — the old "derive XP from damage" design was
  wrong, see `docs/GAME-MECHANICS.md`.
- Compiles against RuneLite 1.12.35 with no warnings, 34/34 tests pass. **Nothing in
  the rewritten tree has run in a game client.** That is the next gate.
- No backend, no site, no upload. Client-side only.

## Hard constraints — do not violate

See the imported conventions for the full list. Project-specific additions:

1. **No hardcoded mob list, ever.** Mobs are discovered by `npc_id` at runtime so new content works on release day. Where specific NPC IDs are unavoidable (the transform-death list), use `net.runelite.api.gameval` constants.
2. **Never display anything mid-fight that could inform the next action.** Record in the client, analyse on the website.
3. **Never collect data about other players.** Recording *that* foreign damage occurred is fine as an integrity signal; recording who, what they wore, or where they stood is not. `scene_has_other_players` is a boolean only.
4. **Upload is opt-in and off by default**, with the exact warning text required by the imported config rules. The plugin must remain fully useful with upload disabled.
5. **Config group name:** now `everykill`, changed 2026-08-20 with the package rewrite.
   No migration was written and none was needed — the old `everykilltracker` group held
   only settings for the deleted snapshot/gist features, there was never a ledger in
   config, and `settings.properties` contained no keys under the old group at all.
   Verified before the change, not assumed. **This was the one free moment to do it:
   any rename after real user data exists needs a read-the-old-group migration.**

## Core rules the code must enforce

- **A kill requires BOTH a death AND our damage.** `ActorDeath` fires for every NPC in the loaded scene, including strangers' kills.
- **`ActorDeath` means "health ratio hit zero", not "died".** Verified empirically and externally corroborated. For transform-death NPCs it must never be trusted as a death signal.
- **XP is a function of damage, not kills.** `StatChanged` is a checksum, not a source of truth.
- **Classify, never correct.** Where attribution is uncertain, record the uncertainty. Never guess, never backfill from expected rates.
- **Unknown ≠ empty.** A kill whose loot couldn't be attributed is excluded from drop-rate maths entirely — never counted as a dry kill.
- **Store raw `npc_id` forever.** Display grouping is a read-time concern and must stay reversible.
- **The NPC stat table must never block kill recording.**

## Explicitly out of scope

Cut after surveying the Plugin Hub — these are already solved by popular plugins and rebuilding them wastes effort without adding anything defensible.

| Not building | Because |
|---|---|
| Discord webhook notifications | **Dink** (66k installs) does this well. Emit events it can consume instead |
| Raid drop tracking (CoX/ToB/ToA) | **Raid Data Tracker** (57k) handles points and splits properly; **Dry Rate Tracker** (26k) covers dryness. Deep work we would do badly |
| In-client drop table display | **Loot Lookup** (153k) owns this surface |
| Session GP/hr as a headline feature | **GP Per Hour**, **Profit Tracker**, **Supplies Tracker** cover it in-client. Our value is comparative, not the raw number |

## Prior art worth reading

Existing plugins that solve pieces of our problem. Read before reinventing.

| Plugin | Relevance |
|---|---|
| **InstantDamageCalculator** (18k) | Calculates damage from the Hitpoints XP drop — exactly our derived-XP arithmetic, in production. **Read before Step 5**; it will have hit the same rounding and timing issues |
| **Monster Stats** (31k) | Maintains an NPC defensive-stat dataset keyed to the client. Possible shortcut for Step 0a instead of building from the wiki Bucket API |
| **Damage Counter** (53k), **Royal Titans Damage Tracker** (17k) | Per-actor hitsplat damage tracking and contribution share — same mechanic as Step 1 |
| **Gauntlet Performance Tracker** (29k) | Tick-loss measurement. Match their definition rather than inventing a private one |
| **Loot Logger** (71k) | Local persistence of Loot Tracker data — reference for on-disk patterns |
| **RuneLite `LootManager`** | The reference implementation for tile-coincidence loot attribution |

## Scale target

Plugin Hub install distribution is steep — the top plugin has ~595k, the 250th has ~16k, and tracking plugins cluster in the 20k–80k band. **Design for ~10k users, not 500k.** This affects storage estimates, batching, and whether raw events are retained indefinitely.

## Docs

- `docs/LICENSING.md` — what may be copied and what may only be read. Read before referencing another plugin's source.
- `docs/SUBMISSION-CHECKLIST.md` — Plugin Hub pre-flight list. Not for now — read when we're actually preparing to submit.
- `docs/BUILD-ORDER.md` — **the task list. Start here.**
- `docs/INTEGRITY.md` — **testing and data-integrity standards. Read before marking any step complete.**
- `docs/WORKING-AGREEMENT.md` — **report format and reasoning failure modes.**
- `docs/GAME-MECHANICS.md` — **every game fact the code depends on, with a wiki URL and a date. No game-mechanic claim enters code without a row here.**
- `docs/FINDINGS.md` — append-only log of verified empirical results
- `docs/spec-kill-detection.md` · `docs/spec-drop-attribution.md` · `docs/spec-data-model.md` · `docs/spec-performance.md`
- `docs/STANDING-ASSUMPTIONS.md` — external facts this project depends on, when each was last verified against a primary source, and how often to re-check
- `docs/JAGEX-LOGIN.md` — logging into a Jagex account from the dev client
- `docs/PRODUCT-DIRECTION.md` — why the plugin exists, what's cut, and which inputs the site needs recorded now. **Plugin scope only; the site's own feature set lives outside this repo.**
- `docs/STEP-3.7-DETAILED.md` — detailed walkthrough of swapping the example plugin's files for the real ones
- `docs/README.md` — early project README (predates the Everykill naming and current spec set; kept for history, not current design)

## Working style

- Build one step at a time. Each step in `BUILD-ORDER.md` has acceptance criteria — do not move on until they're met.
- The author is newer to coding. Explain what code does in plain language, and say why an approach was chosen over alternatives.
- The specs are **designed, not verified**. When testing contradicts one, say so plainly and propose the correction rather than working around it.
