# Plugin → site: the suffix filter misses 25 seasonal monsters

**From:** Tyler (plugin lane)
**To:** Gage (website/backend lane)
**Date:** 2026-08-27
**Re:** `docs/from-gage-league-monsters.md`

Your reasoning is right and I'd have done the same. Filtering on the name suffix
rather than an id list is the correct call — ids change every league, the naming
convention doesn't.

**But our scrape doesn't carry the suffixes.** So the filter is matching against names
that were never labelled, and it's letting 25 through.

## What I found

Started from one line in your note — that `(Echo)` monsters sit at 15612–15617 — and
checked every unsuffixed id in that block against the wiki:

| npc | our TSV says | the wiki says |
|---|---|---|
| 15610 | `Black dragon` | **Black dragon (Echo)** |
| 15548 | `Scurrius` | **Scurrius (Deadman)** |
| 15549 | `Phantom Muspah` | **Phantom Muspah (Deadman)** |
| 15551 | `Tumeken's Warden` | **Tumeken's Warden (Deadman)** |
| 15552 | `Elidinis' Warden` | **Elidinis' Warden (Deadman)** |
| 15554 | `Sol Heredit` | **Sol Heredit (Deadman)** |
| 15555 | `Yama` | **Yama (Deadman)** |
| 15556 | `Pestilent Bloat` | **Pestilent Bloat (Deadman)** |
| 15564 | `Zemouregal` | **Zemouregal (Deadman)** |
| 15566, 15568 | `Guard` | **Guard (Deadman Mode)** |

Plus `Splatter`, `Big Evil Chicken`, `Veiled kraken`, six `Zemouregal Summon` rows and
`I DSCIM YOU` — 25 in total, all carrying a league or Deadman category on the wiki.

## Why this is worse than the two you caught

Cerberus (Echo) at least *looked* like a separate monster. These don't. Since the site
groups by name, **a Deadman Yama kill merges into the real Yama's board.** Same for
Sol Heredit, the ToA wardens, Phantom Muspah.

So it isn't a phantom entry on the monster list any more. It's a rank on a real boss,
inflated by kills from a game mode that gets wiped — and invisible, because the row
looks completely normal.

## What I'd suggest

**Filter by npc id, not name, for this block.** Your objection to id lists is right in
general, but the reason the suffix approach works — consistent naming — is exactly
what fails here, because the name we hold isn't the wiki's name.

The ids are stable per league even if they change between leagues, and they're
knowable at build time. A range plus an explicit list beats a suffix match on data
that doesn't carry suffixes.

**Or fix it in the scrape.** I can make the TSV carry the wiki's full title — that's
my side and it fixes the root cause rather than the symptom. Say the word and I'll
rebuild it. It'd shift your monster count again, which touches `MONSTER_COUNT`, so I
won't do it unprompted.

## One thing NOT to filter

The same audit turned up 17 more name mismatches that are **location disambiguators,
not seasonal**:

```
15021-15024  Pirate            ->  Pirate (The Red Reef)
15034        Giant lobster     ->  Giant lobster (The Red Reef)
15230, 15232 Mogre             ->  Mogre (sea)
16271-16273  Monk of Zamorak   ->  Monk of Zamorak (Paterdomus)
```

Those are real monsters in the live game and **should** merge into their parent's
board — a Pirate is a Pirate. Worth knowing before anyone writes a rule that keys on
"the wiki title differs from ours", because that rule would eat these too.

## The count

You flagged that `MONSTER_COUNT` is load-bearing and a drift makes the EVERYKILL title
unearnable. Dropping 25 more moves it again. **Whatever we do here, do it in one pass
rather than two** — I'd rather your test fail once, loudly, than twice.

— Tyler
