# After v1

Deliberately cut from the first release, with the reason. Not a wishlist — these were
specified, considered, and deferred on purpose.

---

## 1.1 — Helmet and title picker

**Decided 2026-08-27**, from `from-gage-picker.md`. First 1.1 item, ahead of Goals.

Players earn helmets and titles today and **have no way to choose one**. The endpoints
have existed since the unlock system shipped and nothing has ever called them.

**Why it has to live in the plugin:** the plugin holds the token. The site has no login
and deliberately never asks for an account secret, so it physically cannot authenticate
a pick. This isn't a convenience — there is no other place it can go.

**Why first, ahead of Goals:** it's the payoff for a system that already exists and
currently does nothing. Goals is the bigger build; this is the more visible one.

### The contract, verified live 2026-08-27

```
GET  /v1/unlocks    Bearer     what this account earned + what it's wearing + next rungs
GET  /v1/helmets    no auth    catalogue: 53 helmets, 37 titles
POST /v1/helmet     Bearer     { "helmet": "cowl" }    null clears
POST /v1/title      Bearer     { "title": "the-new" }  null clears
```

Confirmed against production: 53 helmets and 37 titles, shapes exactly as documented.

Sprites are `https://oldschool.runescape.wiki/images/<file>`. **Six filenames contain
an apostrophe and need `%27`** when building the URL by hand.

### What will bite

**Picking requires a published name.** A helmet and title are worn on a public profile,
and an unpublished account hasn't got one — the pick is stored on the `published_name`
row. `/v1/unlocks` returns `published: false` up front, so the panel can say so before
someone picks and eats a `409 not_published`.

Show the pickers **greyed with a line pointing at the publish toggle**, not hidden.
Seeing what you've earned is half the reason to earn it. Unpublished players still earn
normally; nothing is lost by publishing late.

**Rate limit: 1 request per 60s per token, burst 5**, shared with the upload limiter.
Fine for a human clicking, fatal for a panel that fires per keystroke or per repaint.
`/v1/unlocks` is a GET and unlimited, but still one call per panel open, not per frame.

**Five responses to handle:** `200` saved, `422 not_earned` (shouldn't happen if the
panel only offers what `/v1/unlocks` returned), `409 not_published`, `400 bad_request`,
`401 unauthorized` → re-register.

**Titles carry rarity 1-7**, which drives colour and animation on the site. Surface the
number so a player knows a tier 6 is worth switching to. Reproducing the effects in
Swing is optional — the site does the heavy version.

### Not building

No site-side picker. It would need a login, and a login means the site holds credentials
it has no reason to hold. If we ever want one, the clean route is the plugin minting a
short-lived link, never a password.

---

## 1.1 — Goals

**Decided 2026-08-26.** The one spec feature users will actually miss, held back so v1
ships and real kills start reaching the site.

From `spec-plugin-ux.md` §3:

- Set a kill goal on any monster — "500 rockslugs"
- Progress bar per goal, ordered by nearest completion
- Notification on completion
- **Goals are targets, never counts.** Setting a goal must never touch the KC
- Multiple concurrent goals
- **Auto-suggest when a slayer task starts**, matching the task amount, one click to accept

**Why it's a good 1.1 rather than a v1 item:** it's the feature that gives someone a
reason to reopen the panel, so it's worth landing as a visible update rather than
buried in a launch. It's also a real build — storage, UI, notification wiring — not a
polish pass.

**What's already in place:** `SlayerTask` reads the current task, its assigned amount
and its remaining count, which is exactly what auto-suggest needs. The milestone
ladder and chat notice path in `MilestoneNotifier` are the model for completion
notices.

**The trap to avoid:** a goal is a target. If setting one ever writes to `NpcStat`, a
user could inflate their own kill count and the whole honesty argument dies with it.

---

## Also deferred

**Kill Log with every monster** (`spec-plugin-ux.md` §1) — needs a defensible
denominator (what counts as one entry: variants, quest NPCs, dummies), grouping
rules, search, and virtualisation for ~1,800 rows. The spec's own open questions
section admits this isn't decided.

**Chat commands** (§7) — `!kc`, `!killlog`, `!ek <monster>`.

**Goal infoboxes** (§6) — depends on goals.

**Supplies consumed, damage taken, deaths** (§1b) — nothing tracks inventory changes
or our own hitpoints. A panel showing `0` for all three reads as "you took no damage"
rather than "we aren't watching", so they arrive when something measures them.

**Step 0c, the combat formula** — `BUILD-ORDER.md` records this as blocked because
the wiki Bucket API has no monster defence bonuses. That's still true for the
*bonuses*, but `NPCComposition.getStats()` exists in the API with documented
constants (`STAT_ATTACK`, `STAT_DEFENCE`, `STAT_STRENGTH`, `STAT_HITPOINTS`,
`STAT_RANGED`, `STAT_MAGIC`) and gives the combat **levels** straight from the client
cache for any NPC we've seen. That's half the formula unblocked from a source we
already have. No core plugin reads it, so the semantics are unverified — worth
reading off a known monster and checking against the wiki before relying on it.
