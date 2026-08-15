# Licensing

Rules for what may be copied into this repository, and what may only be read.

Not legal advice. When money or a real dispute is involved, ask someone qualified.

---

## Our licence

This plugin ships under **BSD 2-Clause "Simplified"**. The Plugin Hub submission process requires a `LICENSE` file and its README specifically instructs BSD 2-Clause.

**Check now:** a `LICENSE` file must exist at the repo root containing BSD 2-Clause text. The example-plugin template normally ships one. If it's missing, add it before submission or the PR will be rejected.

Do not change this licence. Anything more restrictive risks rejection; anything that conflicts with RuneLite's own BSD 2-clause creates problems downstream.

---

## The default rule

**Read freely. Reimplement freely. Do not paste.**

RuneLite's Developer Guide explicitly encourages reading core plugins to learn, and permits reading Plugin Hub plugins as a secondary reference — noting their API usage isn't always correct.

Copyright protects **expression**, not ideas. Understanding how another plugin solves a problem and then writing our own version carries **no obligation of any kind**. That is what this project does, and it's how every reference in `docs/PROJECT.md` is intended to be used:

| Reference | What we take |
|---|---|
| InstantDamageCalculator | Which rounding and XP-timing problems exist |
| Monster Stats | Where NPC stats can be sourced and how they bridge to npc_ids |
| RuneLite `LootManager` | The tile-coincidence approach, already reimplemented in our own structure |
| Gauntlet Performance Tracker | Their tick-loss definition, so our numbers are comparable |

None of these require attribution, because none of them involve copying code.

---

## If code is ever copied

Sometimes a snippet genuinely is the right answer — a constant table, a well-tested edge-case branch. In that case:

1. **Open the source repo's `LICENSE` file first.** Do not assume.
2. **BSD 2-Clause / MIT / Apache 2.0** → copying is permitted, but the copyright notice, licence conditions and disclaimer **must be retained** in our source. Keep the original header block at the top of the file, and note the origin in a comment.
3. **GPL / AGPL / any copyleft** → **do not copy.** It would force this entire plugin under that licence and break Plugin Hub compatibility. Reimplement from understanding instead.
4. **No LICENSE file at all** → **do not copy.** Public on GitHub does not mean freely reusable; with no licence granted, the default is all rights reserved. This is more common than people expect.
5. **Flag it in the report.** Any copied code must be called out explicitly, never merged silently.

---

## Data and APIs are separate from code

A plugin's code licence says nothing about its data or its servers.

- **OSRS Wiki content** is CC BY-NC-SA 3.0 — non-commercial. Fine while there are no ads; a blocker if that ever changes. Long-term plan is deriving our own rates from kill logs.
- **The Wiki's real-time prices API** is explicitly public-use and separate from the content licence.
- **Another project's HTTP API** (collectionlog.net, TempleOSRS, etc.) is governed by their terms, not their code licence. Ask about rate limits before depending on one.

---

## Integration ≠ code reuse

Most of what `docs/PROJECT.md` calls integration involves no code at all:

- **Not duplicating** a solved feature (our Dink "integration" is simply not building webhooks)
- **Matching conventions** so data is comparable
- **Documenting** how users point another tool at us
- **Our website** calling **their** public API

The Plugin Hub has no inter-plugin dependency system, and we cannot add dependencies anyway. Code-level coupling is not on the table.

---

## The cultural layer

The Plugin Hub README recommends contributing to existing plugins where authors accept contributions, to avoid fragmenting the ecosystem. Reviewers hold this norm. Be ready to state clearly why this plugin is new functionality rather than a duplicate of an existing tracker.

Precedent worth following: TempleOSRS cloned WikiSync's collection log implementation **with the team's blessing** and thanked them publicly. They likely didn't need to ask. They asked anyway.

**Message authors even when not legally required.** This is a small community and goodwill compounds.
