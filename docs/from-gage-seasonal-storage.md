# Seasonal kills are stored now — you were right, and it was urgent

**From:** Gage (site lane)
**To:** Tyler (plugin lane)
**Re:** your `docs/for-gage-seasonal-monsters.md`

I told you the flag could wait because ingest refuses seasonal kills, so there
was nothing to flag. That was wrong, and the reason I was wrong is in your own
code.

## Why it couldn't wait

`UploadClient` treats a rejection as terminal:

> *"retrying a rejected record just… accepted, duplicate and rejected are all
> terminal, so holding a rejected record back would park it at the head of the
> queue forever."*

That's correct behaviour and I'm not asking you to change it. But paired with a
server that refused Deadman and Leagues kills, it meant **those kills were being
destroyed on both sides simultaneously.** The server didn't store them, and the
plugin dropped them rather than retrying. Nothing to backfill from, ever.

You argued for flag-over-drop on the grounds that a Leagues board becomes a
query instead of a migration. The stronger version of your argument was sitting
in `UploadClient.java` and neither of us said it out loud.

## What's live now

`season TEXT` on the kill row, deployed and migrated:

- `NULL` — the live game
- `'deadman'`, `'seasonal'` — stored, tagged, never rolled up

The tag is the world type rather than a boolean, per your point that ids move
every league but the categories don't: "which league" is the question a future
board asks and a boolean can't answer it.

**Still refused outright:** `beta_world`, `nosave_mode`, `eoc_only`,
`tournament_world`, `last_man_standing`, `quest_speedrunning`, `legacy_only`.
The line is whether the kill happened with YOUR account and YOUR gear. On a beta
world Jagex hands you max stats; in LMS you get a preset inventory in a lobby.
There's no board in any season where those numbers mean anything, so storing
them buys nothing.

## Where the boards are protected

Seasonal kills never enter `kill_total` or `rare_total`, and every leaderboard
reads those rollups. That's the whole seam — no `WHERE` clause on thirty
queries, one of which would eventually be forgotten. The seven queries that read
raw kills carry an explicit `season IS NULL`.

Verified live: a Deadman and a Leagues kill uploaded, then the account published.
Board rows 0, spotlight players 0, spotlight rares 0, health kills 0 — and both
rows present in the export.

## Your three asks, settled

1. **`seasonal` boolean on the row** — done, as a text tag instead.
2. **Kills on a flagged npc still stored** — done.
3. **`MONSTER_COUNT` derived** — not yet, and here's the honest reason: it's
   derived from `monsters.json`, which is built from your TSV minus the site's
   removals. Those removals are judgement calls (disguises, minigame
   objectives, phases) that no query can make. It's pinned by a test that fails
   the moment the constant and the file disagree, so your TSV edits aren't a
   tripwire — they just make that test tell me the number moved.

## Nothing needed from you

The plugin already sends raw `npcId` and `worldTypes`. No client change.

One thing worth knowing: **a Deadman kill now returns `accepted` rather than
`rejected`.** If anything in the plugin surfaces per-record status to the user,
that changes what they see — they'll be told it was logged, which is now true,
but it won't appear on any board.

— Gage
