# Account type, and the last-active switch

Gage → Tyler, 2026-08-26. Small client change, but it gates a feature I've
deliberately left unbuilt rather than ship half of.

## What I need

One field on the register call (or on the kill record — your call, register is
cheaper):

```json
{ "clientId": "...", "accountType": "main" }
```

Values: `main`, `ironman`, `hardcore`, `ultimate`, `group`, `hardcore_group`,
or `unknown` if the client genuinely can't tell yet. Anything unrecognised
gets stored as `unknown` and treated as an ironman — safest default wins.

RuneLite exposes this; `AccountType` off the varbit is what other plugins use.
If it's not readable at register time, send `unknown` and update it on the
first kill that can read it.

## Why

Delk asked for a **last active** stat on player profiles, then asked for it to
be a per-player switch: **mains on by default, iron accounts off**. That's the
right shape and I can't build it without knowing which is which.

The reason it's a switch at all, and not just a field:

> "tracking since March"   — credibility. says the numbers are long-run.
> "last kill 14 mins ago"  — targeting. says where someone is right now.

There's a well-circulated r/2007scape PSA about pkers using another tracker's
public activity data to find people in-game. A named public profile with a live
last-seen is that same tool. Irons default off because death actually costs
them something.

## What's shipped in the meantime

`GET /v1/player/:slug` returns `firstKill` (how long we've tracked them) and
**does not** return `lastKill`, `lastSeen` or anything equivalent. There's a
test — *"a profile says how long, never how recently"* — that greps the raw
response for all six spellings and fails if any appears. I verified it fails by
planting `lastKill: Date.now()` in the store.

When your field lands I'll add the per-account switch, flip mains on, and
update that test to assert the field appears **only** for accounts that
enabled it, rather than deleting the test.

## Consent copy

If you add a panel toggle, it needs to say what it actually does. Something
like:

> **Show when I was last active**
> Off by default. Puts a "last seen" on your public profile. Anyone can read
> it, including people looking for you in-game.

Don't soften that last line. It's the whole reason the switch exists.
