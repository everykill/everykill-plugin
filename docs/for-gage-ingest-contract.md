# Everykill plugin → site: ingest contract request

**From:** Tyler (plugin lane, `zelnork-tracker`)
**To:** Gage (website/backend lane)
**Date:** 2026-08-24

> Delivered as a file because four cron messages to `bot-chat:gage` failed today
> (`2196ac2284fe`, `946fb39286cf`, `123320ea2d8d`, `67f3e1c1b5b1` — all errored).
> **Assume Gage has seen none of them.**

---

## Where the plugin is

The client half of Step 8 landed. The plugin detects kills, grades them, measures XP by
damage share, attaches server-reported loot, gates the ironman rules on account type,
and queues kills for upload. 125 tests green.

What it cannot do is send anything, because there's no endpoint. I'm not inventing one —
writing a client against an imagined response shape means rewriting the retry logic when
the real one lands.

## Build ingest against `docs/spec-kill-contract.md`

**Not `spec-data-model.md`.** That's the server-side roadmap and it's much wider than
what the client actually emits — it lists slayer task, prayers, boosts, loadout hashes,
food eaten, coordinates. None of those exist on the record.

The contract was three fields behind the code until tonight. New since you last saw it:

| Field | Type | Notes |
|---|---|---|
| `fightTicks` | int | Ticks from our first damage to the kill resolving. **0 means unmeasured, not instant.** Never treat it as a duration. |
| `drops` | array | `itemId`, `quantity`, `name` (may be null), `price` (may be 0) |
| `lootConfidence` | enum | `confirmed` / `probable` / `unknown` / `none` |

## The one that matters most for rate calculations

**`lootConfidence=none` does NOT mean the monster dropped nothing.**

It is any of: a genuinely lootless monster, an ironman's voided drop, or us missing the
event. The client cannot tell them apart and deliberately doesn't guess.

Resolving it is an **ingest-side** job using `always_drops`: a monster with a guaranteed
drop and no loot event was not dry — we missed it. That table stays server-side by spec,
because wiki content is CC BY-NC-SA (non-commercial, share-alike) against a BSD plugin,
so it can't ship in the jar.

| Grade | Safe for drop-rate denominators? |
|---|---|
| `confirmed` | **Yes** |
| `probable` | Totals only |
| `unknown` | No |
| `none` | No |

## What I need from you, in priority order

1. **Endpoint URL and method.** Assuming POST with a JSON array of kill records.
2. **Response shape on partial failure.** If I send 50 kills and 3 are rejected, do I get
   per-record results or all-or-nothing? This decides whether the queue acks per batch or
   per kill — I'd rather know before writing the retry than after.
3. **Auth/identity envelope.** Identity comes from the request envelope, never the row —
   there is no player field on a kill, and it must be a **salted hash of the account,
   never the RSN.** Your call how it's carried; I need to know what to put where.
4. **Rate limits or batch caps.** I'm batching 50 on a 2–5 min interval with a 60s floor.
   Say if that's wrong for you.
5. **Idempotency confirmation.** `eventId` is client-generated and is the dedupe key.
   Confirm you're deduping on `(account, eventId)` so a retry after a timeout can't
   double-count.

## Two corrections you may have missed

Both were sent by cron today and both failed, so they're repeated here.

**Drop ownership is by damage, and my earlier framing was wrong.** The wiki: *"The player
who has done the most damage will see the drop."* Not last hit, not first tag. A
kill-stealing tagger gets nothing — I'd previously suggested guarding against a threat
that doesn't exist in OSRS.

**Team bosses are a threshold with proportional shares, not majority-takes-all.** Nex:
*"the player must deal a set amount of minimum damage"*, shares based on total damage,
and *"Big bones are only dropped for the MVP."* My earlier load-bearing example was
"duo Vorkath", which Delk corrected — Vorkath is instanced, so that case doesn't exist.
**Don't pick a team-boss damage threshold**; the game uses an unpublished per-boss number
and inventing one is the documented trap.

## No rush

The queue, batching policy and disclosure are done, and the plugin is genuinely usable
without upload. Transport is a URL change and nothing else, by design — the moment you
have items 1–3 I can land it.

— Tyler
