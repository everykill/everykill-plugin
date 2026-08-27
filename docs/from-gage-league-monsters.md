# Leagues monsters are in monsters.tsv

Gage → Tyler. Site side. No action needed from you unless you disagree with the
reasoning — I've handled it on my end.

## What Delk spotted

Searching "echo" on everykill.com/monsters returned two results:

- **Cerberus (Echo)** — npc 15612, combat 477
- **Kalphite Queen (Echo)** — npc 15616 / 15617, combat 499

They're in `data/monsters.tsv` (lines 3751-3753). I checked the wiki rather
than assuming: both pages are real, and both carry
`Category:Demonic Pacts League`. Cerberus (Echo) is described as

> a Master difficulty variant of Cerberus that is accessible during the
> Demonic Pacts League

Leagues runs on a separate save inside a temporary game mode. Nobody playing
the main game can reach either of them, so on a public board they read as two
monsters that don't exist.

## What I did, and what I did NOT do

**I did not touch `monsters.tsv`.** Your scrape is right to have them — the
plugin needs the npc ids or a league kill logs as an unknown monster, and
that's worse than the alternative.

The filter is site-side, in `tools/build-monsters.py`:

```python
SEASONAL = ("(Echo)", "(Leagues", "(Deadman", "(Tournament", "(Beta")
```

Matched on the name suffix rather than an id list, because the ids change every
league and the naming convention doesn't. The build now prints what it left
out, so it can't drop something silently:

```
1,345 distinct names from 4,121 npc ids
2 seasonal name(s) left out: Cerberus (Echo), Kalphite Queen (Echo)
```

## The bit worth knowing

The count moved **1,347 → 1,345**, and that number is load-bearing on my side:
the EVERYKILL title (rarity 7, the top of the whole ladder) is "kill every
monster in the game". Its threshold was the literal `1349` while the data
shipped 1,345 — which would have made the title **permanently unearnable**, and
nothing would have reported that. It just silently never fires.

It's a single `MONSTER_COUNT` constant now, with a test that reads
`monsters.json` and fails when the two drift apart.

**If you add or remove monsters from the TSV, that test will fail on my side
until I rebuild.** That's intentional — I'd rather it break loudly than have a
title quietly become impossible. No action needed from you; just don't be
surprised if I ask about a TSV change.

## If you think I've got this wrong

The case against filtering: a league kill is still a kill, and someone who
played Leagues might want it on their record. I don't think that survives
contact with the leaderboard — league accounts are wiped, so a rank on
"Cerberus (Echo)" would be a permanent #1 nobody could ever contest.

If you want them tracked but hidden from the board rather than dropped, say so
and I'll do it that way instead.
