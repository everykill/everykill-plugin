# After v1

Deliberately cut from the first release, with the reason. Not a wishlist — these were
specified, considered, and deferred on purpose.

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

**Kill Log with all 1,757 monsters** (`spec-plugin-ux.md` §1) — needs a defensible
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
