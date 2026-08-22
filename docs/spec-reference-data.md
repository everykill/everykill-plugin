# P0 reference data

Per-monster facts we can't observe from a single client: max hitpoints, guaranteed
drops, and the npc_id ↔ wiki bridge.

Scoped 2026-08-20 after a live session made it concrete. Everything in the "Verified"
sections was tested against the live API or measured in a client. Everything in
"Open" is not decided — this doc exists to be argued with, not followed.

---

## 1. Why it stopped being theoretical

It was always listed as "the real P0 gate" without a specific consumer. Now it has one.

A combat record opens on the first hitsplat **we witness**, so damage dealt before we
engaged is invisible. `EXACT` therefore means *"nobody hit it after we turned up"*
while reading as *"we earned this kill"*. Thirteen kills in multicombat produced zero
detected foreign damage while the game printed the ironman kill-credit warning.

Max HP fixes that, and not with a heuristic:

> **If we dealt less total damage than the monster has hitpoints, and it died, the
> difference came from somewhere else.** That's conservation.

Measured in the Catacombs on 2026-08-20. Nine kills of npc_id 7259, damage rolled:

```
67, 71, 71, 72, 72, 72, 72, 73, 75
```

Eight cluster at 71–75. One sits at 67, four below the next lowest. The wiki gives
7259 **70 hitpoints** — so that kill was three points short of being possible. Rolled
damage is an *upper* bound on applied damage, which makes the gap wider, not narrower.

**What it proves and doesn't.** It proves unseen damage. It does **not** prove another
player — our own poison or venom produces the identical signature, since those
hitsplats aren't ours. So it can justify downgrading to `AMBIGUOUS`. It can never
accuse anyone, and must never be worded as though it does.

---

## 2. Where it lives — server, not client

**Recommendation: the reference table stays server-side and is never shipped to the
client.**

The client already stores everything the check needs: `npc_id`, `myDamage`,
`othersDamage`. The server knows max HP. The arithmetic runs at ingest.

Reasons, in order of how much they'd hurt to get wrong:

1. **`PRODUCT-DIRECTION.md` §"First Plugin Hub submission: local-only, no upload."**
   Fetching reference data at runtime is a network call. It drags the third-party
   disclosure and a server dependency back into the first review — exactly the surface
   that decision removed.
2. **`LICENSING.md:54` — OSRS Wiki content is CC BY-NC-SA 3.0, non-commercial.**
   Share-alike also sits badly against a BSD plugin. Keeping wiki-derived data off the
   client keeps that problem in one place, on our own infrastructure, where it can be
   swapped out (see §4).
3. **Bundling goes stale.** A jar-shipped table needs a plugin release for new content,
   against `PROJECT.md`'s "new content works on release day".
4. **Read-time interpretation is reversible.** A wiki correction retroactively fixes
   every historical kill. Baked into the client, it's frozen at whatever we believed
   that day. Same reasoning as "store raw npc_id forever, display grouping is a
   read-time concern".

This also matches the stated architecture. `EverykillPlugin`'s header already says the
plugin records and does not analyse.

**The cost, stated plainly:** a local-only user never sees a refined grade. Their panel
shows `EXACT` on kills the site would call `AMBIGUOUS`. That's two numbers for one
kill, which is the trust problem the grade system exists to prevent. See §5 — it needs
deciding before any of this gets built.

---

## 3. The source — verified working

**Endpoint:** `https://oldschool.runescape.wiki/api.php?action=bucket&format=json&query=...`
Public, no auth, HTTP GET. Field and bucket names are lowercase with underscores.

### Buckets that matter

| Bucket | Carries |
|---|---|
| `infobox_monster` | hitpoints, combat level, `experience_bonus`, combat stats, defensive bonuses, **`id` (repeated)** |
| `dropsline` | individual drop rows — **39,101 entries** across the game |
| `drop_table_sources` | drop table membership |
| `npc_id` | id ↔ name bridge |

### Working query

```
bucket('infobox_monster')
  .select('name','id','hitpoints','combat_level','experience_bonus')
  .where('name','Dagannoth')
  .limit(20).run()
```

Returns, among others:

```json
{"id": ["7259"], "hitpoints": 70,  "combat_level": 74, "experience_bonus": 0}
{"id": ["7260"], "hitpoints": 120, "combat_level": 92, "experience_bonus": 0}
{"id": ["970","971","972"], "hitpoints": 70, "combat_level": 74, "experience_bonus": 0}
{"id": ["976","977","978","979"], "hitpoints": 120, "combat_level": 100}
```

### The puller — built and run, 2026-08-21

`tools/fetch-reference-data.sh`. One curl call, one awk pass, **0.9 seconds** for the
whole table. Writes `data/monsters.raw.json` and `data/monsters.tsv`.

```
4,325 npc ids
   15 missing hitpoints   (0.3%)
  875 missing xp bonus    (20%)
```

**`data/` is gitignored on purpose.** Wiki content is CC BY-NC-SA — non-commercial and
share-alike, which sits badly against a BSD repo. Committing the dataset would be
redistributing it. The script is committed; the data is generated on demand.

**Validated against six monsters measured in play.** Every one matches, with overkill
always positive and small:

| npc_id | wiki HP | rolled damage observed |
|---|---|---|
| 7259 Dagannoth | 70 | 71–75 |
| 7260 Dagannoth | 120 | 123–126 |
| 421 Rockslug | 27 | 26–27 |
| 7257 Ankou | 60 | 60 |
| 409 Cave crawler | 22 | 23 |
| 11917 Guard | 22 | 22 |

**Two traps the raw data hides**, both handled in the script and both invisible until
you look:

- **`id` carries non-numeric entries** — `beta14278`, `hist3011`. Wiki revision refs,
  not npc ids, and nothing in a live client will ever match them. Filter to numerics.
- **Wiki namespace pages leak into the bucket**, e.g. `RuneScape:Templates`. Filter
  anything with a colon in the name.

**20% of monsters have no `experience_bonus` at all.** That is the "editor-entered data
may be unfilled" warning from `GAME-MECHANICS.md`, measured at scale. Anything reading
this field must treat absent as *unknown*, never as zero — 875 monsters would silently
get a 1.0 multiplier they were never assigned.

### Three things that query taught us

- **`id` is a repeated field.** Multiple npc_ids share one row. The bridge is free — no
  separate join needed for the common case.
- **`experience_bonus` exists and is non-zero for some monsters** (5 for two dagannoth
  variants). Confirms `GAME-MECHANICS.md`: read it, never compute it.
- **The last row has no `experience_bonus` field at all.** Unfilled editor data, live,
  exactly as warned. **Missing means unknown, not zero.** Anything that treats an absent
  field as 0 will silently understate.

### Use the API, not the pages

A summarised fetch of the `Dagannoth` article reported **70 hitpoints for both
variants**. The structured API gives 70 and 120, and our own damage data agrees with the
API. Page summaries flatten multi-variant infoboxes — the same failure already logged
for the rockslug. **`FINDINGS.md`, 2026-08-20.**

---

## 4. The better long-term source is our own kill logs

`PRODUCT-DIRECTION.md:54` already plans to derive rates from kill logs. **Max HP is
easier than rates**, because minimum observed lethal damage converges on true max HP
from above and tightens with every kill from every user.

Tonight, from one player in one session:

| npc_id | wiki HP | min observed lethal damage |
|---|---|---|
| 7259 | 70 | 71 (excluding the contested 67) |
| 7260 | 120 | 123 |

Two kills each and it's already within 3. Across ~10k users it converges fast.

Why this matters beyond elegance:

- **No licence attached.** It's our data. The CC BY-NC-SA problem becomes a temporary
  bootstrap rather than a permanent dependency.
- **Covers new content the day someone kills it**, with no release and no wiki editor.
- **Self-correcting** when Jagex rebalances something.

**So: seed from the wiki, tag every row's provenance (`wiki` / `observed`), and let
observation take over.** Never silently merge them — a disagreement between the two is
interesting and should be visible, not averaged away.

---

## 5. Decided — the client's ceiling came down

**Decided 2026-08-21: lower the client's ceiling.** `EXACT` overclaimed, and moving the
data around was never going to fix it.

The client's top grade is now `UNCONTESTED`, and there is no `EXACT` below the server.
It means what the client can actually see: *nobody else hit it after we turned up*. It
does not mean we earned the whole kill — without max HP we can't know what happened
before our first hitsplat.

Why this over the alternative (let local and uploaded grades differ, explain it in the UI):

- **One kill, one grade.** A count that drops the moment you upload reads as the site
  calling you a liar, however carefully it's worded.
- **The name matches what the code checks.** `UNCONTESTED` is literally the condition in
  `KillStateMachine` — death signal, our damage, no foreign hitsplats.
- **It costs a word, not a number.** Nobody's count went down.

`EXACT` is reserved for the server, where max HP is available and conservation can be
checked for real. If it ever exists it gets assigned there, never in the client.

Shipped in `Confidence`, `NpcStat`, the panel and the overlay. `NpcStat` still reads the
old `exact` JSON key via `@SerializedName(alternate = ...)`, so no ledger written before
today loses a count.

Still open:

- Refresh cadence and how a wiki edit war or vandalised infobox is prevented from moving
  anyone's grades.
- Whether `always_drops[]` is needed at P2 or can wait until loot attribution is real.
- Whether the ~39k `dropsline` rows are pulled wholesale or lazily per monster.

---

## 6. Not doing

- **Shipping the table in the jar.** §2.
- **Fetching it from the client at runtime.** §2, and it breaks the local-only first
  submission.
- **Computing `experience_bonus` from stats.** `GAME-MECHANICS.md` — manual overrides
  ignore the published formula, Vorkath computes +20% against a listed +0%.
- **Treating a missing field as zero.** §3.
