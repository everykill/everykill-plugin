# Site → plugin: names on leaderboards, and a contradiction in the docs

**From:** Gage (website/backend lane)
**To:** Tyler (plugin lane)
**Date:** 2026-08-25

Delk read the privacy policy draft and asked the question neither of us had:
*"are we going to have RSN on the leaderboards?"*

We can't both be right. Two project documents have disagreed since before either
of us wrote a line:

> **PRODUCT-DIRECTION.md** — "Everything the plugin records exists to populate
> leaderboards nobody else can build." Hiscores, player search, profile pages.

> **spec-kill-contract.md** — "a salted hash of the account, **never the RSN**.
> The moment RSNs sit in the database the site becomes a player-lookup tool
> nobody consented to."

Both are protecting something real. A board showing `player_a3f8c1` killed 4,000
gargoyles isn't a leaderboard — the whole first-run payoff is *"nobody is ranked
#1 on 1,757 monsters, go take some,"* and a record nobody can see is yours is
worth nothing. Equally, a searchable RSN-to-activity database is a surveillance
tool in a hiscores costume.

Design is written up in the site repo as `docs/NAMES-ON-LEADERBOARDS.md`. The
short version and the parts that land on you:

## Two consents, never bundled

| | what it does | default |
|---|---|---|
| **Upload** | sends kills, pseudonymously. No name, ever. | **off** |
| **Publish** | attaches a display name to public entries | **off**, requires upload |

Upload gets you ranked. It does **not** get you named — an unpublished account
counts toward everyone's percentiles and appears in no visible row.

GDPR Article 7(2) wants a consent request clearly distinguishable from other
matters, and bundling "send my kills" with "publish my name" is the bundled
consent that gets invalidated. It's also just two different asks: plenty of
people want dryness maths without their name on a board.

## What this means for the plugin

**`spec-kill-contract.md` does not change.** There is still no player field on
the wire, the kill record still carries no name, and identity still comes from
the envelope. That rule was right and stays.

What changes is that a **separate** publish call may carry a display name, only
when publish is on. Not on the kill record, not "attached for later," not sent
speculatively.

**The thing I want to flag:** the client is the only thing standing between the
server and every player's RSN. The plugin knows the logged-in account name,
which makes verification genuinely free — a client can only report a name it's
actually logged in as, so there's no ownership dance and no name-squatting. That
strength is also the risk. **A field that exists gets populated.** If the name
is only sent when publish is on, the promise holds; the moment it rides along
"just in case," it doesn't.

Not asking for anything yet — this isn't built on either side. Flagging it now
because the client half is the half that can't be fixed server-side later.

## What's public, when it exists

Narrow on purpose: display name, kill counts, ranks, completion totals, luck
position, sample sizes.

**Not** per-kill timestamps, region ids or session boundaries. Kill counts are a
scoreboard. Timestamped kill events keyed to a real name are an activity log,
and publishing one builds exactly what your spec refuses to build — for players
who consented to a leaderboard, not to being followed.

Your existing rule stands untouched: nothing about *other* players beyond
`scene_has_other_players`. Consent covers you, never whoever was in your scene.

## Turning publish off

Deletes the name, doesn't hide it. Ranks survive as unnamed entries since the
kills still count for everyone else's position. A `published = false` column
with the name still in it is the version that leaks.

## Policy already corrected

The draft said the RSN is "never transmitted," full stop. True of everything
that exists today, and it would have become a lie the day leaderboards shipped.
Now scoped: *uploading kills* never sends it, publishing is a separate opt-in
that doesn't exist yet, and the policy describes it up front rather than
surprising anyone later.

`check-markup.py` asserts all three of those sentences stay on the page, so the
policy can't quietly drift back to an unconditional promise.

## Open, and worth your opinion

- **Display name = RSN, or free text?** RSN is verifiable and is what players
  want to be recognised by. Free text is safer but unverifiable and invites
  impersonation. I lean RSN precisely because your client makes verification
  free — but you know the account-name APIs better than I do, including how they
  behave on ironman and group accounts.
- Do unpublished accounts hold a visible numbered rank ("#4 — unpublished") or
  vanish from the list? First is honest about sample size, second is tidier.

— Gage
