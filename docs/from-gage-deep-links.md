# Deep links — put the player on their own board

Gage → Tyler, 2026-08-26. Site side is done and live-ready; this is the one
line of plugin work that switches it on.

## What I need

When the panel opens a monster on the site, append the account's public slug:

```
https://everykill.com/board?name=Abyssal%20demon&me=<publicSlug>
```

That's it. No new endpoint, no new consent, no new field — `publicSlug` is
already in the `GET /v1/me` response you're using for the export button.

## Why

Delk's words: *"i dont really like the 'type your kill count' cause anyone
using the site is probably registered to it."* He's right. The first version
of the board asked players to hand-enter a kill count the client is already
uploading, which is busywork dressed up as a feature.

RuneDiary's install guide is the model — *"Install the plugin. Log into OSRS.
Your profile is created automatically."* The client knows who you are, so the
website shouldn't ask.

## What the site does with it

1. Stores the slug in `localStorage` under `ek-me`, so every board afterwards
   knows the player without another link.
2. Marks their row on any board they appear on.
3. **Re-centres the board on them** when they're outside the visible top five.

Point 3 is the one that matters. From the leaderboard-design research:

> The five people above and below them are the only rows with any motivational
> content. The rows at the top are decoration.

A whip board led by someone 1,400 kills dry tells a new player the competition
isn't for them. Their own neighbourhood tells them it is. The heading changes
from "Driest" to "Around you" when that happens.

## Privacy — nothing new here

- The slug is **already public**: it's on every published leaderboard row and
  in `/v1/player/:slug`. Passing it in a URL exposes nothing that isn't.
- An **unpublished** account's slug is never emitted by the board API, so
  linking one shows only that player's own rows, to that player.
- `localStorage` holds one opaque string, cleared by a `×` in the corner of
  the panel. No cookie, no fingerprint, nothing sent anywhere.
- Don't put the RSN in the URL. The slug is the identifier; the name comes from
  `published_name` and only if they published it.

## Testing it

Any slug works — grab one from `GET /v1/me` and open:

```
https://everykill.com/board?name=Gargoyle&me=<slug>
```

If the account has kills on that monster you'll get a rust bar naming the
player and their rank. If not, you get "No kills here yet" — which is correct,
not an error.
