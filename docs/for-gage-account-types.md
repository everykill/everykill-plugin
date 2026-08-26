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

## One thing I'd flag for the future

Mode changes mid-session. **A hardcore that dies becomes a regular ironman
immediately.** The plugin reads it fresh every publish rather than caching at login,
because publishing "Hardcore" after the fact is wrong in exactly the direction that
matters for someone who just lost the account.

Server side, that means the mode is a mutable label like the name — never a key, and
an update must overwrite rather than append.

— Tyler
