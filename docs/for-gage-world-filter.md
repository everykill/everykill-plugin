# Plugin → site: kills now only upload from normal worlds

**From:** Tyler (plugin lane)
**To:** Gage (website/backend lane)
**Date:** 2026-08-27

You and Delk found it: nothing recorded which world a kill happened on, so a Deadman
or Leagues kill was indistinguishable from a main-game one.

**This is the bigger hole, not the npc-id list.** On a Deadman world you kill ordinary
monsters with ordinary ids — `Guard`, `Abyssal demon`, whatever. No id list can catch
those, because the ids are genuinely the same. Only the world type separates them.

## What the plugin does now

`WorldFilter`, gating `uploadService.offer(kill)`. Excluded:

```
DEADMAN             separate save, wiped each season
SEASONAL            Leagues; separate save, wiped
TOURNAMENT_WORLD    throwaway
BETA_WORLD          a copy of your account on unreleased content
NOSAVE_MODE         nothing persists at all
QUEST_SPEEDRUNNING  a prebuilt account, not yours
FRESH_START_WORLD   separate save with its own hiscores
```

Everything else uploads. Same call core makes in `ChatCommandsPlugin`, where a
non-normal world type resolves to a different hiscore endpoint entirely.

**PvP, high-risk, bounty, LMS and skill-total worlds are ranked.** They are the live
game with different rules — same account, same save, real kills. Excluding them would
throw away legitimate history.

## Two deliberate choices

**The gate is on upload, not on recording.** A Deadman kill still goes in the player's
local ledger. It's their kill and the plugin's job is an honest record of what they
killed; what must not happen is it landing on a shared board. Same shape as the
ironman loot rules — classify, never discard.

**An unreadable world type is treated as not ranked.** If the read fails we send
nothing, because a leaderboard cannot un-count a kill after the fact.

The panel says so rather than going quiet: `world: deadman`, and *"Kills here are
recorded locally but not uploaded — this world has its own save."* An upload status
reading "Up to date" while nothing is being sent is indistinguishable from working.

## What I'd ask you to do anyway

**Don't trust the client on this.**

The plugin is open source and the check is client-side, so anyone who wants a Deadman
rank can delete four lines and rebuild. That's not a reason to skip the client check —
it stops the honest 99.9% — but it does mean the server shouldn't assume it happened.

Two options, and I'd take the first:

1. **Add `world` to the kill contract** — the current world id as an int. The server
   can then reject or flag seasonal worlds itself, and the data is there if you ever
   want per-world stats. It's one more field on a record that already carries region
   id, and it isn't personal information.

2. Infer it from the seasonal npc-id list, which only catches league-specific
   monsters and misses every ordinary monster killed on a Deadman world.

If you want option 1, say so and I'll add `world` to `KillRecord` and the contract.
I haven't done it unprompted because it changes the wire format, and the last time I
changed that without checking with you first, every record got rejected on an enum's
letter case.

— Tyler
