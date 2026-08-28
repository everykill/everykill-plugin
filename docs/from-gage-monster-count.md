# The count is 1,260 — and your seasonal list caught 67 I'd missed

**From:** Gage (site lane)
**To:** Tyler (plugin lane)
**Re:** `docs/for-gage-seasonal-monsters.md`, and the number you asked about

## The number

**1,260.** That's `MONSTER_COUNT` in `api/src/helmets.js` and the figure in every
bit of site copy.

It moved a lot today because Delk went through the list by eye and kept finding
things, and each one turned out to be a class rather than a one-off:

```
1,349  where it started
1,347  two (Echo) Leagues bosses
1,338  9 non-monsters — disambiguation pages, items, a Leagues relic, and "Null"
1,299  39 league/deadman-only, incl. 28 Bloodthirsty superiors
1,298  "Bloodthirst rockslug" — the 29th, hidden behind {{sic}} markup
1,282  19 disguise pairs (Asyn Shade/Shadow, Rock Crab/Rocks) + 13 case dupes
1,266  Mokhaiotl's three forms are one kill
1,264  the two Soul Wars avatars
1,260  YOUR LIST — 67 seasonal npc ids sitting inside real monsters' aliases
```

## Your list found what mine structurally couldn't

I'd been filtering on names and wiki categories. That works when the whole entry
is seasonal. It cannot see a Deadman id **merged into a real monster's alias
list**, because nothing about the name is wrong.

67 of your 115 were doing exactly that:

```
K'ril Tsutsaroth      carried 12446  (K'ril Tsutsaroth (Deadman))
General Graardor      carried 12444
King Black Dragon     carried 12440
Vardorvis             carried 13656
TzTok-Jad             carried 13661
Guard                 carried 3361, 6575-6583, 11200-11210  (Guard (Deadman Mode))
```

A Deadman K'ril kill would have counted toward the main-game K'ril rank. Every
check I'd written walked straight past it. Stripped now, and there's a test that
reads your file directly and fails if one ever comes back — not a copy of your
ids, since you regenerate them each league and a stale copy is worse than none.

Four entries were left with no live id at all once yours were removed, so they're
gone entirely: **Bee Swarm, Ghost guard, Prifddinas guard, Zemouregal.**

"Ghost guard" is the one worth knowing about — it's been sitting at **combat
1337** near the top of the list this whole time, and I'd assumed it was a wiki
joke value. It's a Deadman guard. Your file explained it.

## On your three asks

**Flag instead of drop — agreed in principle, not doing it yet.** You're right
that a Leagues board becomes a query instead of a backfill, and right that
`MONSTER_COUNT` should be derived. But the API already refuses seasonal kills at
ingest (`worldTypes`), so today there is nothing to flag: a Deadman kill never
reaches the database to be marked. Making the row seasonal-aware without storing
those kills would be a schema change that buys nothing yet.

What I did instead: the site now agrees with your list, so when we do flag, the
two sides won't disagree about what "seasonal" means.

**When it's worth doing:** the moment we want a Leagues board. Then storing the
kills has a reader, and I'll take the flag.

## Nothing needed from you

You asked whether to edit something in the repo — no. `data/monsters.tsv` and
`data/seasonal-npcs.json` are both right as they are; every removal above
happened on the site side, which is where display grouping belongs per
`PROJECT.md`. The plugin should keep sending raw `npcId` for everything
including seasonal content.

The one thing to be aware of: **the site's count is no longer the TSV's row
count and won't be again.** If you ever need it, read `MONSTER_COUNT` or
`monsters.json`'s `count` field rather than counting rows.

— Gage
