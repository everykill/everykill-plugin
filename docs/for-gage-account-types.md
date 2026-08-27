# Plugin → site: account types, and why some players must be able to hide theirs

**From:** Tyler (plugin lane)
**To:** Gage (website/backend lane)
**Date:** 2026-08-26

Delk asked for account types on the site, and flagged the reason himself: **an
ironman on a public leaderboard is a target.** Agreed, and it changes the design —
this isn't just another field.

## The modes

All read from the client. Every one of these is already detected today:

| | source |
|---|---|
| `MAIN` | `VarbitID.IRONMAN` = 0 |
| `IRONMAN` | = 1 |
| `ULTIMATE_IRONMAN` | = 2 |
| `HARDCORE_IRONMAN` | = 3 |
| `GROUP_IRONMAN` | **not in the varbit** — `client.getClanSettings(ClanID.GROUP_IRONMAN)` |
| `GROUP_UNRESOLVED` | varbit returned something we have no name for |
| `UNKNOWN` | not logged in |

**Group ironman is the trap.** It has no varbit value at all — core's own switch
has no case for it and falls through to normal. It lives in the group's clan
channel. Verified live 2026-08-24: our test account is a GIM and the varbit alone
reported `UNRESOLVED`.

**Do not fold `GROUP_UNRESOLVED` or `UNKNOWN` into `MAIN`.** If Jagex adds a mode,
an unrecognised value treated as a main silently switches the ironman rules off for
accounts that need them. Store them as themselves and show them as "unranked".

## The safety problem

A public row reading **"Hardcore Ironman — 4,000 gargoyles this week"** is a target
list. It says: one life, known monster, plays often, probably in the same place.
That's more actionable than anything else we'd publish.

The mode is also genuinely useful — comparing an ironman's dry streak against a
main's is comparing different games. So it can't just be dropped.

**So it's a third consent, not part of the second:**

| | what it does | default |
|---|---|---|
| Upload | kills, pseudonymously. No name, ever. | off |
| Publish | display name on public entries | off |
| **Show account type** | mode next to the name | **on**, only applies when publishing |

On by default because most accounts are mains and the comparison is the point. A
player who doesn't want it flips one switch, and the config text says plainly what
it reveals rather than making them guess.

## What the plugin sends

`POST /v1/publish` gains one optional field:

```json
{ "publish": true, "displayName": "SomePlayer", "accountType": "HARDCORE_IRONMAN" }
```

**When it's withheld the field is omitted entirely** — not sent as `"hidden"`, not
sent as null. A field the server never receives can't be logged, leaked, or
un-hidden by a later migration. Same reasoning as the RSN.

Withdrawing publish (`{"publish": false}`) sends no mode either.

## What I need from you

1. **Accept the optional field.** Absent means the player withheld it; store nothing.
2. **A row with no mode must still rank.** It shows as unranked/unknown, not hidden —
   same call we made about unpublished accounts. Sample size is the product.
3. **Never infer the mode from anything else.** No guessing from drop patterns, no
   backfilling from an earlier publish where they left it on. If they turn it off,
   the mode should disappear from the site, and a stored copy that survives that is
   the version that leaks.

## Fallen hardcores — copy the official hiscores, don't invent

Delk asked whether we could do this the way Jagex does. We should, and it isn't what
either of us assumed. From the wiki (read 2026-08-26):

> *"their experience and total level on the Hardcore Ironman HiScores table will be
> **locked**, with their name ~~slashed across like this~~"*

**Not deleted. Not moved. Not reset. Frozen.** The account carries on as a normal
ironman and its hardcore entry stays where it is, struck through.

That's the right call and it's worth stating why: those kills were real and were
earned under hardcore rules. Deleting them is dishonest. Migrating them to the
ironman board is worse — it credits a normal iron with a hardcore's work.

There's a seventh mode for it: `DEAD_HARDCORE_IRONMAN`. Ironman loot rules apply
unchanged; `countsAsHardcore()` returns false.

**What I'd ask the site to do:**

1. **Freeze the hardcore row at the death, don't remove it.** Strike the name
   through, same as the hiscores.
2. **Route kills after that point to the ironman board.** They're an ironman's kills
   now.
3. **Don't recompute the frozen row.** If a later kill arrives with a timestamp
   before the death — a queued batch that drained late — it belongs to the frozen
   total. The server has the timestamps; the client does not know when the death was.
4. **The transition is one-way.** An account that reports `HARDCORE_IRONMAN` after
   reporting `DEAD_HARDCORE_IRONMAN` is a bug or a spoof, not a resurrection.

### One caveat you should know about

`IRONMAN_HARDCORE_DEAD` (varbit 5403) is how the client tells a fallen hardcore from
an account that was never hardcore. **No core plugin reads that varbit**, so the name
comes from the cache constants and my "nonzero means dead" reading is inference, not
a measurement.

I built it so being wrong is cheap: a false positive labels a regular ironman as
fallen-hardcore on a board they were never on, and every loot rule is identical
either way. **Your side is the authority** — you see the mode transition across
publishes and can confirm it properly. Treat the client's label as a hint on first
contact, not as proof.

## Also for the future

Mode changes mid-session, which is why the plugin reads it fresh every publish rather
than caching at login. Server side that means the mode is a mutable label like the
name — never a key, and an update overwrites rather than appends.

— Tyler
