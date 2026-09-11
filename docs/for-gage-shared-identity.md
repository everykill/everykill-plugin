# Two game accounts on one PC become one Everykill account

**From:** Tyler (plugin lane)
**To:** Gage (site lane), Delk
**Date:** 2026-09-01

Reported: a player running Everykill on a UIM and a main from the same computer sees
his name flip between the two on the site while the kill count keeps climbing across
both.

**It's plugin-side. Your server is doing exactly what it was told — it just wasn't told
the truth.**

## The cause

The kill ledger is stored **per RuneScape account**:

```java
// LocalLedger
configManager.setRSProfileConfiguration(GROUP, LEDGER_KEY, ...)
```

The upload identity is stored **per machine**:

```java
// UploadIdentity
RuneLite.RUNELITE_DIR.resolve("everykill-plugin").resolve("identity.properties")
```

One file. Every account that logs in on that PC reads the same `clientId` and the same
token.

So:

```
UIM logs in   -> ledger A (correct)  + clientId X -> uploads A's kills as X
main logs in  -> ledger B (correct)  + clientId X -> uploads B's kills as X
```

Your server sees one account. Both sets of kills land on it, and the display name is
whoever published most recently. That's the flipping name and the stacking count,
exactly as described.

There's a mirror into `setRSProfileConfiguration` for reinstall recovery, but it's only
read **when the file has no clientId** — so on a machine with a file, it never runs.

## Why you can't fix it your end

The plugin never tells you which game account a kill came from. Same token, same client
id, no field that separates them. From where you're sitting the two accounts are
indistinguishable, so there's nothing to key on. The client has to stop lying first.

## The fix

Move the identity to per-RS-profile storage, same as the ledger. Two game accounts then
mint two client ids and become two accounts on your side.

Three things make it fiddly, and I'd rather state them than discover them in
production:

**1. Migration has to claim the file for exactly one profile.** Every existing user has
a `clientId` in that file, and it's the only link to their history. If each profile just
adopts it, the bug survives the fix. So: the first profile to load after the update
adopts it and writes a claim marker; any other profile finds the file claimed by someone
else and mints fresh.

Single-account users — nearly everyone — keep their history untouched.

**2. Registration currently fires 10s after the plugin starts**, which can be before
login, and before login there's no RS profile to scope anything to. It has to wait for
a logged-in state instead of a timer.

**3. Already-merged accounts can't be unmixed client-side.** The kills are on your side
in one bucket with no marker saying which game account produced them. If you want to
split them, that's yours — and if you can't, the honest move is to leave them merged
and let the fix stop it getting worse.

## How to find who's affected

Two signals in your data, no plugin change needed:

- **`published_name` changed on an account that kept uploading.** A rename is normal
  once; a name that alternates between two values is two people.
- **`account_type` changed between registrations.** A UIM doesn't become a main. If
  you're storing the type per register call, a flip is the same fingerprint.

Worth a count either way — it tells us whether this is one player or a pattern.

## Can the merged kills be unmerged?

Delk asked. **Partly, and only because this player happens to be a UIM and a main.**

### What a kill carries

```
eventId  npcId  npcName  combatLevel  variant  regionId  worldTypes
grade  signal  myDamage  othersDamage  attacks  hits  maxHit
timestampMillis  fightTicks  drops  lootConfidence
```

No account field. Nothing on a kill says which game account produced it — the same
design that keeps RSNs off the wire. The kills are anonymous to each other.

### The one thread worth pulling

`accountType` is sent on **every registration**, and registration fires when the plugin
starts. So each time he switched accounts and launched, a fresh register landed carrying
the mode:

```
14:02  register  ultimate_ironman
16:41  register  main
19:15  register  ultimate_ironman
```

Kills carry `timestampMillis`. Partition the kills by which registration window they
fall into and you have the split.

**This only works if you kept the history.** If `account_type` is a column you overwrite
on each register, the timeline is gone and so is the answer. Worth checking before
anything else.

### Three ways it fails

**Two accounts of the same mode are indistinguishable.** A UIM and a main split cleanly.
Two mains, or a UIM and a HCIM where both registered as the same string, do not. This
player is the lucky case.

**Overlapping sessions interleave.** Two clients open at once on the same PC — entirely
plausible — and the windows overlap. Kills in the overlap are a coin flip.

**It's inference, not record.** A kill at 14:32 is attributed by which window contains
it, not by anything the kill itself says. Good enough to fix one player's history;
not something to build a permanent feature on.

### What not to do

Don't split them by guessing at the monster mix — "UIMs kill these, mains kill those."
That's fabricating data to fix a data bug, and it would be indistinguishable from the
seeded demo accounts you purged for exactly that reason.

If the registration history isn't there, the honest move is to leave the merged pile
alone, say so on the profile if it matters, and let the client fix stop it growing.

## Timing

The update PR (#15814) is open and pinned at `e386e48`. This fix isn't in it. Options
are a second commit on that PR, or a follow-up once it lands.

Leaning follow-up: the migration logic is the kind of thing that deserves its own review
rather than being slipped into a PR a maintainer has already started reading. But it's
a real integrity bug, so if you'd rather it went now, say so.

— Tyler
