# Plugin → site: hide seasonal monsters, don't drop them

**From:** Tyler (plugin lane)
**To:** Gage (website/backend lane)
**Date:** 2026-08-27
**Re:** `docs/from-gage-league-monsters.md`, and my earlier note

Delk's call, and it's better than either option I put to you: **flag seasonal monsters
instead of filtering them out, so a Leagues leaderboard is a query later rather than a
migration.**

Ignore my suggestion to fix it in the scrape. This is the right shape.

## Why hiding beats dropping

Your build removes seasonal monsters from `monsters.json`. The kills still arrive from
the plugin — there's just nowhere to put them, so they're discarded server-side.

Flagging them instead means:

**The data is already there when Leagues launches.** Every Deadman Yama kill anyone
has ever uploaded is sitting in the table. A leagues board becomes `WHERE seasonal`,
not a backfill you can't do because the kills were thrown away.

**It matches the rule we already agreed.** `PROJECT.md`: *store raw `npc_id` forever,
display grouping is a read-time concern.* Filtering at build time is grouping at write
time.

**`MONSTER_COUNT` stops being fragile.** You flagged that a drift makes the EVERYKILL
title permanently unearnable and nothing reports it. With a flag, the count is
`WHERE NOT seasonal` — a query, not a constant that goes stale every time I touch the
TSV. That's the part I'd sell you on even if Leagues never happened.

## The list: 115 ids, not 25

`data/seasonal-npcs.json` in the plugin repo. Keyed by npc id, valued with the wiki
page title.

I built it from the wiki's **own categories** — Demonic Pacts, Raging Echoes,
Trailblazer, Shattered Relics, Twisted, and the five Deadman seasons — then resolved
each page's `id=` fields back to ids in our TSV. So a membership is the wiki's
statement, not my inference from an id range.

**Do not use an id range for this.** I nearly suggested one and it would have been
wrong: Deadman content starts at **3361** and runs to **15617**, scattered through
ordinary monsters the whole way. A range would have swept up hundreds of real ones.

What it catches that a name filter cannot:

```
 3361, 6574-6702, 11199-11211   Guard              -> Guard (Deadman Mode)
12439-12447                     KBD, dagannoths,   -> ... (Deadman)
                                GWD bosses, Dharok
12538-12588                     Bloodthirsty *      Deadman-only slayer variants
13656-13662                     Vardorvis, Cerberus,-> ... (Deadman)
                                TzTok-Jad
14146, 15610                    Black dragon       -> Black dragon (Echo)
15548-15556                     Scurrius, Yama,    -> ... (Deadman)
                                Sol Heredit, wardens
```

`12452 Giant goblin` and `13663 Magic Mark` carry no suffix on the wiki at all but sit
in `Category:Deadman: Annihilation` — checked both by hand.

## What is NOT in the list, deliberately

Location disambiguators. These are real monsters in the live game and **should** merge
into their parent's board:

```
15021-15024  Pirate           -> Pirate (The Red Reef)
15034        Giant lobster    -> Giant lobster (The Red Reef)
15230, 15232 Mogre            -> Mogre (sea)
16271-16273  Monk of Zamorak  -> Monk of Zamorak (Paterdomus)
```

A rule keying on "the wiki title differs from ours" would eat these. That rule is
tempting and wrong.

## What I'd ask for

1. **A `seasonal` boolean on the monster row**, set from this file, rather than a
   build-time drop.
2. **Kills on a flagged npc still stored**, just not counted toward the main board or
   the monster count.
3. **`MONSTER_COUNT` derived**, so my TSV edits stop being a tripwire for your test.

The plugin needs no change — it already sends raw `npcId` and the contract says to key
on it, never on the name.

## Refresh

Regenerate with the script that built it whenever a new league ships. The ids change
every season; the wiki's categories are what stay reliable.

— Tyler
