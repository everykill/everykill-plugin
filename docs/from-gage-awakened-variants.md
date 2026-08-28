# Awakened DT2 bosses need a variant tag from the plugin

**From:** Gage (site lane)
**To:** Tyler (plugin lane)
**Re:** splitting Awakened from post-quest

Delk wants Awakened DT2 bosses on their own boards — quest and post-quest
together, Awakened separate. I can't do it from the data I get, and this is the
one case where the fix has to be yours.

## Why the id doesn't help

The wiki lists the **same npc ids** for post-quest and Awakened:

```
Vardorvis        post-quest 12223,12426    awakened 12223,12426
The Leviathan    post-quest 12214          awakened 12214
The Whisperer    post-quest 12204,12205    awakened 12204,12205
Duke Sucellus    post-quest 12191          awakened 12191
```

Your TSV agrees — `grep -i awakened data/monsters.tsv` returns only
`Awakened Altar` (7288-7294), which is Guardians of the Rift scenery, unrelated.

Jagex reuses the id and swaps the stats. There is nothing in `npcId` to read.

## What WOULD separate them

**Combat level.** You already send it — `KillDetector.java:120` passes
`npc.getCombatLevel()`, read live off the NPC:

```
                 quest    post-quest    AWAKENED
Duke Sucellus      538       758          1099
Vardorvis          572       784          1136
The Leviathan      593       798          1157
The Whisperer      587       791          1146
```

Unambiguous — the gaps are 340+ levels wide.

## Why I can't just use it

`combat_level` is on every raw kill row, but the leaderboards read `kill_total`,
which is keyed `(account_id, npc_id)`. The rollup exists so a million kills cost
90 bytes instead of 520 each, and it collapses post-quest and Awakened into one
row before any board query runs.

I could add `combat_level` to that key. I'd rather not, for a reason worth
stating: it would silently split **every** monster whose level varies. Zombie
alone has variants at 13, 24 and 44 — one board would quietly become three, and
nobody asked for that. A schema key is a blunt instrument for what is really a
per-boss judgement.

## What I'd like instead

An explicit variant on the kill record when you can tell:

```java
// DT2 bosses reuse their npc id across quest / post-quest / awakened.
// The combat level is the only live signal, and the client has it.
private static String dt2Variant(NPC npc)
{
    switch (npc.getId())
    {
        case 12191:  // Duke Sucellus
            return npc.getCombatLevel() >= 1099 ? "awakened" : null;
        case 12223:  // Vardorvis
            return npc.getCombatLevel() >= 1136 ? "awakened" : null;
        case 12214:  // The Leviathan
            return npc.getCombatLevel() >= 1157 ? "awakened" : null;
        case 12204:
        case 12205:  // The Whisperer
            return npc.getCombatLevel() >= 1146 ? "awakened" : null;
        default:
            return null;
    }
}
```

Send it as `variant` on the kill record — `null` for everything else. I'll add
it to the ingest contract as optional, and it becomes a board the same way the
ToB modes did.

**Threshold, not equality.** If Jagex rebalances by a few levels, `>=` keeps
working and `==` silently stops tagging. Same reasoning as the world-type list
being explicit.

## Delk's rule for the split

Quest and post-quest stay **together** — same fight, one is just a re-run.
Awakened goes on its own board. So the boards end up:

```
Duke Sucellus              quest + post-quest
Duke Sucellus (Awakened)   awakened only
```

## What I checked so you don't have to

I audited all 1,279 monsters for the same pattern — one npc id carrying two
different combat levels on the wiki. **Only these four.** The one other hit was
Zombie (levels 13 vs 24), which is an ordinary level variant, not a difficulty
you opt into, and stays merged.

So this is a contained change, not the first of many.

## Not urgent

Nothing is broken today; Awakened kills land on the normal board and are counted.
This is about making a board that should exist. Ship it whenever suits — 1.1
alongside the helmet picker would be sensible.

— Gage
