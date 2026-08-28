# Helmet and title picker — needs a panel in the plugin

Gage → Tyler. Delk asked how players actually choose their helmet and title.
The answer was: they can't. The endpoints have existed since the unlock system
shipped, and nothing has ever called them.

**NOT FOR 1.0.** Delk's call: ship the plugin first, add the picker in 1.1.
Nothing here blocks the Hub PR. The server side is already live and will sit
there waiting — `/v1/unlocks` and both setters are deployed and tested, so when
you build the panel there's nothing to coordinate. Players earning helmets and
titles between now and then lose nothing; the unlocks accumulate whether or not
anyone can wear them yet.

The picker belongs in the plugin because **the plugin holds the token**. The
site has no login and deliberately never asks for your account secret, so it
physically cannot authenticate a pick.

## The three calls

**What has this account earned** — new, shipped today in `71a967ac`:

```
GET /v1/unlocks          Authorization: Bearer <token>

{
  "published": false,
  "helmets": ["cowl"],
  "titles":  ["the-new"],
  "wearing": { "helmet": null, "title": null },
  "next":    [ { "id": "bronze-med", "how": "100 kills" }, ... ]
}
```

Everything a panel needs in one call. `next` is the ladder rungs just above
where they are, so the panel can show "100 kills" under a locked slot rather
than just greying it out.

**The catalogue** — names, sprite filenames, tiers, unlock text:

```
GET /v1/helmets          no auth

{ "helmets": [ { "id":"cowl", "name":"Leather cowl",
                 "file":"Leather_cowl.png", "tier":"Starter",
                 "how":"Upload a kill" }, ... ],   // 53
  "titles":  [ { "id":"the-new", "name":"First Blood",
                 "how":"Upload your first kill", "rarity":1 }, ... ] }  // 37
```

Sprites are `https://oldschool.runescape.wiki/images/<file>`. Six filenames
contain an apostrophe and need `%27` if you're building the URL by hand.

**Picking:**

```
POST /v1/helmet          { "helmet": "cowl" }     null clears it
POST /v1/title           { "title":  "the-new" }  null clears it
```

## Responses you have to handle

| Status | error | What the panel should say |
|---|---|---|
| 200 | — | saved |
| 422 | `not_earned` | shouldn't happen if you only offer what `/v1/unlocks` returned |
| 409 | `not_published` | **"Publish your name first"** — see below |
| 400 | `bad_request` | unknown id, or wrong type |
| 401 | `unauthorized` | token gone; re-register |

## The one that will surprise you

**Picking requires a published name.** A helmet and title are worn on a public
profile page, and an unpublished account hasn't got one — the pick is stored on
the `published_name` row, so there's nowhere to put it.

`/v1/unlocks` returns `published: false` up front so the panel can say so
before a player picks something and eats a 409. I'd show the pickers greyed
with a line pointing at the publish toggle rather than hiding them — seeing
what you've earned is half the reason to earn it.

Unpublished players still **earn** normally. Nothing is lost by publishing
late.

## Rate limit

`/v1/helmet` and `/v1/title` share the upload limiter: **1 request per 60s per
token**, burst 5. Fine for a human clicking, but don't let a panel fire a
request per keystroke or per repaint. `/v1/unlocks` is a GET and isn't limited,
though it's still one call per panel open, not per frame.

## Titles have effects

Rarity 1-7 drives colour and animation on the site — tier 5 shimmers, tier 7
(EVERYKILL, kill all 1,347) burns with embers and a halo. Worth surfacing the
rarity number in the panel so a player knows a tier 6 is worth switching to.
Whether you reproduce the effects in Swing is up to you; the site does the
heavy version.

## What I'm not doing

No site-side picker. It would need a login, and a login means the site holds
credentials it currently has no reason to hold. If we ever want one, the clean
route is the plugin minting a short-lived link — not a password.
