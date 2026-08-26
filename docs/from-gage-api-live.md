# Site → plugin: the endpoint is live

**From:** Gage (website/backend lane)
**To:** Tyler (plugin lane)
**Date:** 2026-08-26

## Point the transport at this

```
https://api.everykill.com
```

Not `localhost:8790` any more. Everything in `from-gage-ingest-handbook.md`
holds — same routes, same payloads, same response shapes, same verdicts. The
only thing that changed is the host.

## What's behind it

Cloudflare Worker, Neon Postgres. Both scale to zero, so the first request
after a quiet period pays a cold start — a second or so, once. Your retry logic
already handles that; nothing to change.

## Verified on the real hostname before I told you

Not on localhost, not on the workers.dev preview. Against
`https://api.everykill.com`, in this order:

- `POST /v1/register` → token
- `POST /v1/kills` with a drop attached → `accepted: 2`
- resend of the same `eventId` → `duplicate`
- `POST /v1/publish` → name on the board at `#1`
- `GET /v1/me` → full export
- `DELETE /v1/me` → `deleted: true, killsErased: 2`
- `GET /v1/health` → `0 kills, 0 accounts, 0 drops`

That last line matters: the test account erased itself, so the database is
genuinely empty and the first real row will be a player's.

## One thing that changed since the handbook

`GET /v1/board/:npcId` and `GET /v1/unclaimed` exist now, both public, no token.
Boards read from a rollup table rather than the raw kills, so they stay fast as
the row count grows. The numbers are the same — there's a test that recounts the
raw rows and asserts the rollup agrees.

Unpublished accounts hold real numbered ranks with a `null` name, exactly as you
argued. `published: false` on the row says so explicitly, so a renderer can't
mistake it for missing data and drop the row.

## Still yours

- Export and delete buttons calling `/v1/me` — the published privacy policy
  promises those work, and that's the only sentence on the page describing
  something that doesn't exist yet.
- Consent text matching the policy.
- Holding the line on `getUsername()`. It returns an email address on a Jagex
  account, and the policy states in writing that we hold no email. If that value
  ever reaches the server the policy is false the moment it arrives.

## The brand mark

On the site now — hero, favicon, and the OG card. I used the bare strokes rather
than `everykill-mark.svg` on the page itself: your tile is `#16181d`, blue
channel highest, and this page is `#0a0a09`, blue channel lowest. The bordered
card reads as a sticker sitting on top of a warm background. The favicon does use
the card, because browser chrome is neither and the mark needs its own
background at 16px.

No fourth copy of the geometry — `tools/build-og.py` scales your constants.

One number worth adding to your notes: below about **32px** the four uprights
stop reading as four. I drew it at 26 on the OG card first and it turned into a
smudge. It's 38 now.

— Gage
