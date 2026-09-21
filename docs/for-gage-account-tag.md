# for-gage: accountTag on register

**From:** Tyler (plugin lane)
**Date:** 2026-09-11
**Ships in:** `deb5c3c` onward

You were right, and the claim check was mine. Fixed, plus you now get the
thing you asked for: a way to know that two uploads are genuinely two
different game accounts.

## what broke

`canSeeProfiles = synced != null` was meant to ask "can we tell one game
account from another". It can't. The object wrapping `ConfigManager` is
constructed at injection and is always non-null. What actually varies is
whether a *profile* exists behind it, which is false until the player logs
in.

`flush()` is scheduled from `startUp()` — plugin start, not login — and
fires 10s later. So `load()` routinely ran logged out, read null for the
profile id, saw a claimed file, concluded "another account owns this" and
minted a fresh identity. The mirror then wrote to a profile that didn't
exist and was dropped, so the next launch did it again.

One real account, a new Everykill account every start. That's your "a lot
of different accounts that are actually the same account."

Fix: `ConfigManager.getRSProfileKey()` is null until login. Both branches
are now gated on it. No profile means no decision — use the file as-is.

## the new field

`POST /v1/register` may now carry:

```json
{ "clientId": "…32 hex…", "accountTag": "a1b2c3d4e5f60718" }
```

- **16 lowercase hex characters**, always that length when present.
- **Omitted entirely when logged out.** Treat absent as "unknown", never as
  a value. Both shapes already 200 on your side — I probed it.
- Stable for one game account on one install.

## what it is, precisely

`SHA-256(installSalt || 0x00 || rsProfileKey)`, first 8 bytes, hex.

The salt is generated once per install, stored locally, and **never
uploaded**.

**Why it isn't the raw profile key.** RuneLite builds that key as
`"rsprofile." + Base64(six bytes of the Jagex account hash)` — see
`ConfigManager.findRSProfile`. It's an encoding, not a digest. Anyone
holding it decodes the account hash straight back out. Sending it raw would
hand you a cross-plugin, cross-site account identifier, which is not
something either of us should be storing.

## what you can and can't do with it

**Can:**
- Prove two registrations from one install are different accounts.
- Prove two registrations are the *same* account — which is what would have
  caught this bug from your side.
- Spot an install registering repeatedly for one player.

**Can't:**
- Get a RuneScape name. It isn't in the input.
- Follow a player across machines. The salt is per install, so the same
  account on a different PC produces a different tag.
- Join it against anyone else's data. Same reason.

## what I'd ask you to do with it

Store it on the account row. When a register arrives with a tag you've seen
before on a *different* `clientId` from the same install, that's this bug
recurring — and it's now detectable server-side instead of via a player
noticing their name flipped.

Don't reject on it. An old client sends nothing, and a logged-out first
flush legitimately sends nothing.

## still open, your call

The accounts already fragmented in prod. The tag doesn't retroactively
identify them — those registers happened without it. If you want them
merged it has to be your side, and I'd want to know how you'd pick the
survivor before recommending it.
