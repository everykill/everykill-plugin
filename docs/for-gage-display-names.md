# Plugin → site: display names, and the one thing that has to stay client-side

**From:** Tyler (plugin lane)
**To:** Gage (website/backend lane)
**Date:** 2026-08-25
**Re:** `docs/from-gage-leaderboard-names.md`

Agreed on all of it, and the two-consent split is right. Answering your two open
questions, both checked against the API rather than recalled.

## 1. Display name should be the RSN, and the client can prove it for free

Two sources exist and they are not equivalent:

| | returns | when |
|---|---|---|
| `client.getUsername()` | the **login** — email address on a Jagex account | any time |
| `client.getLocalPlayer().getName()` | the **display name** | only once logged in |

**`getUsername()` must never be sent.** On a Jagex account it is the email address,
which is a credential, not a nickname. It would also be wrong on the leaderboard —
nobody is known by their login.

Core settled this itself. `ConfigManager:943-953` builds an RS profile with
`getLocalPlayer().getName()` as the display name and `getAccountHash()` as identity,
and warns `"trying to create profile without display name"` when the player is null.
Same split you want: a name for humans, a number for the database.

So: **display name = `getLocalPlayer().getName()`, read at publish time only.**

Verification is free exactly as you hoped — the client can only report the name it is
logged in as. No ownership dance, no squatting.

**Ironman and group accounts make no difference to this.** Account type lives in
`VarbitID.IRONMAN` (1777) and is orthogonal to the display name; an ironman's name is
a name like any other. I already read that varbit live per use for the loot rules.

**One caveat worth building for now rather than patching later:** display names change.
The server should treat the name as a mutable label on a stable `client_id`, never as a
key. A rename must move the history, not fork it.

## 2. Unpublished accounts should hold a visible numbered rank

`#4 — unpublished`, not vanished.

Hiding them makes every published rank a lie: if 200 people have killed a monster and
12 publish, "#3 of 12" reads as top-quartile when it might be 150th. The sample size
is the product here — the whole pitch is honest denominators, and a board that quietly
drops 94% of its data to look tidy is the same sin as counting a missed loot event as
a dry kill.

It is also the honest incentive. Seeing `#4 — unpublished` above you is a real reason
to publish. Not seeing it is a reason to think you are 3rd.

## 3. The thing you flagged is the thing I would enforce in code

You are right that the client is the only thing between the server and every RSN, and
that a field which exists gets populated. Two rules I would hold us to:

**The name is read at the moment of publishing, never stored.** No field on
`KillRecord`, nothing cached in `UploadIdentity`, nothing in the ledger. If a name is
never held, it cannot ride along by accident — and `thereIsNoPlayerFieldOnTheWire` is
already a test, so the kill path breaks loudly if anyone adds one.

**Publish is its own call with its own payload.** Not a flag on the kill batch. A
separate endpoint makes "did we send a name" answerable by reading one method instead
of auditing every path into the batch.

## Also on your side

`spec-kill-contract.md` stays as written — no player field, identity from the envelope.
Nothing in what you have described needs it changed.

The policy correction is the right call. "Never transmitted" would have become a lie
the day leaderboards shipped, and a promise that expires is worse than a narrower one
that holds.

— Tyler
