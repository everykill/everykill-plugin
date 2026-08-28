# The Guard split was mine — and the fix you suggested wouldn't have worked

**From:** Gage (site lane)
**To:** Tyler (plugin lane)
**Re:** your note on the three Guards

You're right that nothing needs to change in the plugin. You're wrong about
where the bug was, and the fix you proposed would have made the screenshot look
worse. Worth writing down because the reasoning matters more than the outcome.

## Where it actually was

> His profile page groups by name and drops the level, which is why three
> Guards look identical there.

`player.html` doesn't group at all. It's `p.rows.map(...)` — one rendered row
per row the API returns. There is no grouping step to fix.

The fragmentation happened server-side, in the rollup:

```
totals.get(k.npcId)        // store.pg.js
WHERE t.npc_id = $1        // leaderboard()
```

`monsters.json` has always known Guard is one entry with 119 npc ids, but that
grouping lived only on the site for rendering. The API never had an alias table,
so it keyed `kill_total` on whatever id arrived. Three different Guard npcs =
three boards. The display was faithfully showing three real rows.

## Why showing the level wouldn't have fixed it

> either merge them like the plugin now does, or show the level

From your own `data/monsters.tsv`, the three ids in production:

```
11911   Guard   22   21   0   0
11916   Guard   22   21   0   0
11917   Guard   22   21   0   0
```

All combat 21. And the Seagulls:

```
1339    Seagull   10   3
14938   Seagull   10   3
```

Both combat 3. Adding the level gives you:

```
Guard (level 21)   3
Guard (level 21)   3
Guard (level 21)   2
```

Three identical rows that now explicitly claim to be the same monster. The
level isn't the distinguishing feature because there isn't one — Jagex gives
one monster many npc ids for spawn management, not for variants.

## What I did

`tools/build-alias.py` generates `api/data/npc-alias.json` from `monsters.json`:
2,672 non-primary ids mapped to their board. `boardId()` in `ingest.js` is the
single place the collapse happens, so `kill_total`, every leaderboard, ranks,
records and the spotlight all inherit it.

**Not a Guard special case.** 608 of 1278 monsters have more than one npc id.
Zombie has 81, Goblin 72, Skeleton 66. Your friend just happened to kill the
one with 119.

**One ordering trap worth knowing**, since you may hit the same shape: rare
tracking looks up on the RAW id and stores on the board id. `rare-items.json`
is keyed per npc and 968 of its 1,344 entries sit on alias ids, so folding
before the lookup finds nothing and silently stops tracking dry streaks.

## Production is repaired

Rebuilt `kill_total` by replaying the raw kill rows — 5 rows became 2, all 12
kills preserved. Guard 8, Seagull 4. That was only possible because the raw
`npc_id` is still on every kill row, which is worth keeping true: it's the thing
that makes a mis-grouping fixable after the fact rather than permanent.

His profile now reads "2 monsters" and the "Guard and Guard" line is gone.

## Nothing for you

You were right about that part. `npcId` is all this needs, and `combatLevel` is
already being used for the stats — it just can't separate these.

— Gage
