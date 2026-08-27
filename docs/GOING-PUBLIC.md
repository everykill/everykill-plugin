# Going public: the org, the transfer, the switch

Everything left before the Hub PR. **GitHub Desktop can't do any of it** — creating an
org, transferring a repo and flipping visibility are all web-only. Desktop is only
useful at the end, to confirm the remote followed.

Roughly 15 minutes. Do it in this order; each step depends on the one before.

---

## Before you start

The repo is `github.com/Delkyy/everykill-plugin`, currently **private**, branch
`everykill-merge` (177 commits). Have it open in a tab.

---

## 1. Create the org

**github.com/organizations/plan** → pick **Free**.

| Field | Value |
|---|---|
| Organization name | `everykill` |
| Contact email | yours |
| Belongs to | **My personal account** |

Skip the "invite members" step — you can add Gage later.

> **If `everykill` is taken**, GitHub will say so immediately. Fall back to
> `everykill-osrs` or `everykillgg` and tell me, because the plugin's `support` URL
> has to match whatever you pick.

---

## 2. Transfer the repo into it

**github.com/Delkyy/everykill-plugin/settings** → scroll to the bottom, red
**Danger Zone** → **Transfer ownership**.

- New owner: `everykill`
- Type `Delkyy/everykill-plugin` to confirm

GitHub redirects the old URL automatically, so nothing breaks while it's private.

---

## 3. Make it public

Still in **Settings → Danger Zone** → **Change visibility** → **Make public**.

Read the confirmation and type the repo name.

**This is the irreversible one.** Everything in the history becomes visible to
everyone, permanently, including to people who clone it before you notice a mistake.

It's ready: the RSNs came out on 2026-08-25, and the AI trailers came out of all 177
commits today. Both verified against the remote, not just locally.

---

## 4. Make `everykill-merge` the default branch

**Settings → General → Default branch** → switch from `master` to `everykill-merge`.

Worth doing before anyone looks. Right now `master` is a 3-commit stub with no shared
history, so a visitor landing on the repo sees an empty-looking project while all 177
commits of real work sit on a branch they have to go find.

---

## 5. Tell me the new URL

One line in the plugin needs it — `runelite-plugin.properties:7`:

```
support=https://github.com/Delkyy/everykill-plugin/issues
```

That has to point at the org URL before the Hub PR, because the PR references it
permanently. I'll change it and push.

**Also worth deciding then:** `LICENSE` reads `Copyright (c) 2026, Delkyy`. That's
valid — copyright belongs to a person, not an org — but if you'd rather it read
`Everykill` or your legal name, now is the moment, while the git history is still
being rewritten cheaply.

---

## What you do NOT need to do

- **Re-clone or re-add the repo in GitHub Desktop.** The transfer redirects; your
  local remote keeps working. Do a Fetch afterwards to confirm.
- **Force-push again.** Already done and verified.
- **Touch the backup** at `~/IdeaProjects/zelnork-tracker-backup-20260826-235811`
  until you're happy. Then delete it — it contains the pre-scrub history.

---

## After this, what's actually left

The submission checklist drops to **zero** once the repo is public and under the org.

The Hub PR itself is a separate thing: fork `runelite/plugin-hub`, add a one-line
manifest file naming this repo and a commit hash, open a PR. That's the last step and
it can't happen until the repo is public — which is why this page exists.
