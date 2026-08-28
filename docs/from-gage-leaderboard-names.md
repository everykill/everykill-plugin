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
#1 on hundreds of monsters, go take some,"* and a record nobody can see is yours is
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

## Display name = RSN. Decided.

Delk's call: the RSN is what players want to be recognised by, and your client
makes verification free. Settled.

**One consequence, and it's the important one: an RSN is not an identifier.**

Jagex [releases the names of inactive
accounts](https://oldschool.runescape.wiki/w/Update:Name_Release_Begins_13th_March)
to other players, and people rename voluntarily. So both of these are real:

- the same person appears under two names over time
- **two different people appear under the same name over time**

The second is the one that bites. Key anything on the name and eventually
someone who claimed a released name inherits a stranger's 10,000 gargoyle kills
— a data-integrity bug and a privacy breach in a single move, in public.

So on my side: everything keys on our random account id, the name is a mutable
display attribute in its own table, and a name that moves to a different account
carries no history because none was ever attached to it.

**On your side it's one line of behaviour:** when publish is on, send the
*current* name with each upload. Don't cache it and don't try to detect renames
— a rename then fixes itself on the next upload and neither of us needs
change-detection logic.

## Not the account hash, though

I looked at `client.getAccountHash()` while working this out. RuneLite's own
`ConfigManager` keys profiles on it and carries `displayName` alongside as
mutable, which is exactly the right pattern — and we already have it, because
our random client id does the same job.

**Don't send it.** It's a persistent Jagex-issued identifier that any other
plugin can also read, which makes it a cross-plugin correlation key. Ours is
random and site-scoped, so it's strictly better for privacy and identical for
us. Storing the account hash would mean collecting a stronger identifier than
the job needs, which is the thing Article 25 exists to prevent.

`rights.test.js` now fails the build if `account_hash` appears in the schema, so
that decision is enforced rather than remembered.

## Open, and worth your opinion

- **Ironman and group accounts.** You know the account-name APIs better than I
  do — is the display name reliably readable across account types, and does
  anything differ on GIM? If there's a case where the client can't read it,
  publish needs to fail closed rather than send a blank.
- Do unpublished accounts hold a visible numbered rank ("#4 — unpublished") or
  vanish from the list? First is honest about sample size, second is tidier.

— Gage
