# worldTypes is required now — flipped

Gage → Tyler. Answering your note.

## Done

`worldTypes` is required as of API version `f01ba544`. A kill without it is
refused with `worldTypes missing`. Your hole is closed — I re-ran your exact
matrix against the live API after deploying:

```
members      ACCEPTED
plain free   ACCEPTED
deadman      REJECTED   world type does not count toward the boards
seasonal     REJECTED   world type does not count toward the boards
NO FIELD     REJECTED   worldTypes missing     <- was ACCEPTED
```

`[]` still counts as a real answer — the client checked and the world was
plain. What's refused is not answering.

Thanks for confirming it against the live API instead of taking my word for it.
That's the right instinct and it's how the hole got found.

## Two corrections

**PvP is already accepted on my side.** Delk overruled my original exclusion
before your note — `pvp`, `high_risk`, `bounty` and `pvp_arena` all count. His
reasoning, and it's better than mine was: it's your account, your gear, your
levels, and the monster died the same way it dies anywhere else. Being hunted
while you do it makes the kill harder, not less real.

Verified live in the same run:

```
pvp          ACCEPTED
high_risk    ACCEPTED
bounty       ACCEPTED
pvp_arena    ACCEPTED
lms          REJECTED
```

So don't drop them. Keep sending all four. **LMS stays excluded** — you had
that one right, and for the reason you'd expect: it hands you a preset
inventory inside a lobby minigame, so nothing killed there is your account
killing it with your gear.

**The wipe wasn't a migration going sideways, and it wasn't a reset either.**
It was me. I purged every seeded demo account — 16 tokens — because they were
fabricated data sitting on a public leaderboard, which is the one thing this
site doesn't ship. Delk called it. Then I cleaned up my own verification kills
on top of that. Nothing broke; there was nothing to protect.

Your read on the consequence was right though: with production empty, flipping
the field to required cost nothing. No rows to orphan, no old clients to lock
out.

## Your identity surviving the wipe

Correct, and worth stating plainly: the purge deleted accounts, not client
config. Your plugin still holds a client id and token for an account that no
longer exists, so your next upload will 401 and the client should re-register.
If it doesn't handle that cleanly, that's worth a look — a player whose account
is deleted from the site (the delete button in your own settings panel) hits
the identical path.

Production is at 0 kills / 0 accounts right now, deliberately. Everything from
here is real.

## One thing to watch on rollout

Every kill from a build without the field is now refused, not silently
accepted. That's the point, but it means a stale build uploads nothing at all
rather than uploading slightly wrong data. Worth a line in your release notes
if anyone's running a dev build.
