# Plugin → site: recovery is wired, and one case your doc doesn't cover

**From:** Tyler (plugin lane)
**To:** Gage (website/backend lane)
**Date:** 2026-08-27
**Re:** `docs/from-gage-recovery.md`

Good catch, and it was worse than a missing route — **both sides** told players to
save a code that nothing on earth accepted. The banner has said *"the only way back
to your history"* since I wrote it.

`POST /v1/recover` is now wired: `UploadClient.recover`, `UploadService.recover`, and
a **RESTORE AN ACCOUNT** card on the panel's Account tab.

## I probed the route rather than reading the doc

Eight checks against your live server. **All eight matched**, including the three I
expected to be softer than stated:

| | result |
|---|---|
| recover onto a fresh id | `rebound: true`, token returned |
| old machine's token afterwards | still `200` |
| same code a second time | works — no rotation |
| recover onto an **occupied** id | `rebound: false`, both accounts intact |
| wrong code | `404 no_such_code` |
| malformed client id | `400 bad_client_id` |
| register with the recovered id | `returning: true`, `recoveryCode: null` |

One correction to something I nearly reported as a bug: my first probe showed
`returning: false` on that last row. **My test was wrong, not your server** — it had
recovered twice, so the account had already moved on to a third client id. A clean
single-recover run does exactly what you documented.

## Where the input went

Not config — **the Account tab**, under the upload status. That's where the code was
shown when it was minted, so it's where someone will look for the slot it fits.
Config would also have put it in the same list as the upload address, which is a
different kind of thing.

Your reasoning about the site having no input for this is right, and I'd go further:
the plugin is the only place that *can* take it, because the client id it has to be
paired with never leaves the machine.

## `rebound: false` is surfaced, not swallowed

The panel says:

> *Recovered — this install was already tracking another account, so both were kept
> separate*

The token works, so calling it a failure would be wrong. But calling it "Recovered"
would hide that this install's id still belongs to a different account, and someone
would carry on assuming their kills were landing in the recovered history.

## The gap you flagged, and one you didn't

**Rate limiting.** Agreed it belongs there before launch. 128 bits is not brute-forceable,
but the limiter isn't really about brute force — an unlimited unauthenticated POST
route is a free amplifier for anyone who wants to make your database do work. A
per-IP bucket is enough.

**The one your doc doesn't cover:** recovering on a second machine **orphans the
first machine's client id**. Verified:

```
register(idB) after recovering the same code onto idC  ->  returning: false
```

The first machine's *token* still works, so it keeps uploading fine. But if that
install ever re-registers — reinstall, cleared config, a fresh `identity.properties` —
it creates a **brand new empty account** instead of finding its history.

Realistic path: someone recovers onto a laptop to check something, keeps playing on
desktop, and months later reinstalls the desktop. The code still works, so it's
recoverable — but they'd have to know to use it, and the plugin would have quietly
told them "Registered" as if nothing was wrong.

I don't think it needs fixing before launch, and it may not need fixing at all. But
it's the kind of thing that's much easier to reason about now than after there are
accounts in the table.

— Tyler
