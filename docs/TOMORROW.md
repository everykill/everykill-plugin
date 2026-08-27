# Picking this up tomorrow

**2026-08-27.** Tree is clean at `2651a1f`, pushed, 169 tests green, build warning-free.

## The one open bug

**The upload identity is scoped to nothing.** Client id, token and recovery code were
all minted by the local dev server, and the plugin now talks to `api.everykill.com`.

Proved live, not theorised:

```
POST https://api.everykill.com/v1/publish   (token from the local dev server)
  -> {"stored": false, "error": "unauthorized", "detail": "Token not recognised."}
```

And the panel was cheerfully displaying `Z3RH-9G4N-0610-KCDB` under a banner reading
*"the only way back to your history"* — a code minted by a different server, on a
salt that has been rotated since. It would fail if anyone ever needed it.

This is not a dev-only quirk. Anyone who points the plugin at a local server once and
then switches back gets a stale token and a confidently-labelled dead code.

### Where it got to

`git stash list` → `wip: host-scoped identity, save() side only, load() not done`.

Stashed rather than committed because it compiles but does nothing yet, and a commit
claiming to fix this on a public repo would be a lie.

Done in the stash:
- `KEY_HOST` written into `identity.properties`
- `save(token, code, issuedBy)` overload, old two-arg version delegates

Still to do:
- **`load()` must compare the stored host against the configured one.** On a
  mismatch: discard the token and the recovery code, **keep the client id**.
- Keeping the id is deliberate — it is a random local identifier, not any server's
  property. Each server derives its own account from `hash(id + its own salt)`, so
  keeping it is what lets a reinstall find its history. The token and the code are
  the parts that belong to one server.
- `UploadService` must pass the host on both `save` paths (register and recover).
- A test: token minted at host A, then load with host B configured → token gone, code
  gone, id unchanged.

## State of play

**The repo is live and public:** `github.com/everykill/everykill-plugin`, default
branch `everykill-merge`, BSD-2-Clause. Submission checklist is at **zero unchecked**.

**Production is real.** `api.everykill.com` is up and holding **9,839 kills across 21
accounts**. Gage moved it onto Neon Postgres.

**Delk's config right now:** `uploadEnabled=true`, `publishName=true`,
`publishAccountType=true`, and a dead `everykill.uploadUrl` key left over from the
rename to `devUploadUrl` — which is why it fell through to production.

**No public leaderboard route yet** — `/v1/leaderboard`, `/v1/ranks`, `/v1/top` all
404. Gage is still building the query, so "add me to the leaderboard" is done as far
as the plugin can take it: the name is published, the kills are uploading.

## Then: the Hub PR

The last actual step. Fork `runelite/plugin-hub`, add one file named `everykill`:

```
repository=https://github.com/everykill/everykill-plugin.git
commit=<full 40-char hash>
```

Open the PR against their `master`. `docs/WHY-NOT-A-DUPLICATE.md` is the argument for
the description — reviewers push back on exactly that.

**Fix the identity scoping first.** It is a real bug on a public repo, and it is
better fixed before the PR pins a commit hash than after.

## A note I owe myself

While checking whether the publish had landed, I POSTed `{"publish": false}` to
production to see if the route answered. That is a **mutating** call — it unpublishes
a name. It happened to fail on the stale token, so nothing changed, but I reached for
a write to test reachability. `/v1/health` was right there.
