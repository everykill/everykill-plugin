# Plugin → site: publish is built client-side, needs an endpoint

**From:** Tyler (plugin lane)
**To:** Gage (website/backend lane)
**Date:** 2026-08-25

Delk confirmed the direction: **RSNs on leaderboards, opt-in.** Exactly the two-consent
split you designed, so nothing changes — but he wants it built rather than planned, and
the client half is done.

## What ships in the plugin now

Two config items, both off by default:

| | key | default |
|---|---|---|
| Upload | `uploadEnabled` | off |
| Publish my name | `publishName` | off, and useless without upload |

Config text matches the policy wording: *"Off means you are still ranked, just not
named."*

## The call I'm making

`POST /v1/publish`, bearer token, not implemented on your side yet.

```json
{ "publish": true, "displayName": "Zelnork" }
```

Withdrawing sends `{"publish": false}` with **no** `displayName` — because the name
should be deleted, not flagged. Your own note: a `published = false` column with the
name still in it is the version that leaks.

Only called when the toggle actually **changes**, not every flush. The plugin tracks
what it believes the server thinks and stays quiet otherwise.

## The three rules, now enforced rather than promised

**The name is read at publish time and never stored.** No field on `KillRecord`, nothing
in `UploadIdentity`, nothing in the ledger. It goes from `getLocalPlayer().getName()`
into one request and is not retained.

**Publish is its own call.** Not a flag on the kill batch, so "did we send a name" is one
method to read.

**A test now enforces it structurally.** `noKillFieldCouldEverHoldAName` asserts that the
only string fields on a serialised kill are `eventId`, `npcName`, `grade`, `signal` and
`lootConfidence`. Anyone adding a string field to the wire — `owner`, `who`, anything —
fails the build without having to remember this conversation.

`getUsername()` is never called anywhere in the plugin. You can grep for it.

## What I need from you

1. **`POST /v1/publish`** accepting the body above.
2. **What happens to a name on `DELETE /v1/me`.** It should go with everything else, but
   I'd rather you confirm than assume.
3. **Any name validation you want done client-side.** Length, characters. I can reject
   before sending, but I'd rather not invent rules that disagree with yours.

## One thing I'd like your view on

**Should publishing be retroactive?** If someone uploads 4,000 kills unnamed and then
opts in, do those kills become theirs publicly, or only kills from that point on?

Retroactive is what people will expect — they earned the rank. But it means a single
toggle can reveal months of activity at once, and someone who opts in casually may not
picture that. Not asking you to decide alone; flagging it because it's the kind of thing
that's obvious in hindsight and awkward to reverse.

— Tyler
