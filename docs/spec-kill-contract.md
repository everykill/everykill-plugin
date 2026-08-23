# Spec — kill contract (client → site)

The one document the plugin and the site both read. If the site needs to know
something about a kill, it belongs here.

**This describes what the client emits today**, field for field, checked against
`com.everykill.model.KillRecord`, `Confidence` and `DeathSignal`. It is not a
wishlist. When the client changes, this changes in the same commit.

Nothing uploads yet. Transport is Step 8. The shape is fixed now so that landing
transport is a URL change and nothing else.

## Do not build against `spec-data-model.md`

That document's `kill_event` table is the **server-side target design**, and it is
much wider than what the client actually produces. It lists slayer task fields,
prayers, boosts, loadout hashes, food eaten and coordinates. **None of those exist
on the record the client emits.** It also still lists `weapon_speed_ticks`, which
was deliberately dropped (FINDINGS 2026-08-20 supersedes 2026-08-14).

Build the ingest against this file. Treat `spec-data-model.md` as the roadmap for
where the schema is going, not as a description of anything you can receive today.

## The record

One kill, as it leaves the detector.

| Field | Type | Notes |
|---|---|---|
| `eventId` | string | Client-generated, idempotent. **The dedupe key.** |
| `npcId` | int | Raw game id. Stored forever; grouping is a read-time concern. |
| `npcName` | string | Display name. Not unique — see below. |
| `combatLevel` | int | Disambiguates same-named monsters. |
| `regionId` | int | Where we engaged. **`-1` when unreadable** — not null, not absent. |
| `grade` | enum | `uncontested` / `inferred` / `ambiguous`. See below. |
| `signal` | enum | `OBSERVED` / `DESPAWN_WHILE_DEAD` / `TRANSFORM_FINISH`. Diagnostic only. |
| `myDamage` | int | Our hitsplats, summed. |
| `othersDamage` | int | Other players' damage. Non-zero **forces** `ambiguous`. |
| `attacksCount` | int | Our hitsplats including blocks and splashes. Accuracy denominator. |
| `hitsCount` | int | Our hitsplats with damage > 0. |
| `maxHit` | int | Our largest single hitsplat this fight. |
| `timestampMillis` | long | Client clock. |

`totalDamage()` is `myDamage + othersDamage` and is derived — do not store it as a
column you can disagree with.

### Dedupe on `(account, eventId)`

**There is no player field on the record.** Identity must come from the request
envelope, not the row.

That envelope identifier must be a **salted hash of the account, never the RSN.**
The moment RSNs sit in the database the site becomes a player-lookup tool nobody
consented to. The plugin has a standing rule that the user's RSN does not appear
in plugin files; the same rule applies to the server.

### `npcName` is not unique and not stable

Two different monsters share a name at different combat levels — two dagannoths,
both `"Dagannoth"`, with different HP. **Key on `npcId`, never on `npcName`.**
Carry `combatLevel` so the site can tell them apart in display.

A slayer task can also span several `npcId`s, so one task shows as several rows
that must be summed. That is by design.

## Grades

Three, always exactly one, never null. `Confidence.java` is the source of truth.

| Grade | Meaning |
|---|---|
| `uncontested` | We hit it, we saw it die, nobody else touched it while we watched |
| `inferred` | Deduced, not witnessed — despawned dead, or a transform finish |
| `ambiguous` | Someone else damaged it too |

**Rules the site must enforce at the database level, not in the frontend:**

- Anything with a **denominator** — rates, accuracy, published per-NPC numbers —
  reads `uncontested` only.
- **Raw totals** read all three.
- `ambiguous` counts in totals and **never** in a denominator.

Surface the split per NPC (a confidence bar or equivalent) so a wall of
`ambiguous` kills is visible rather than quietly poisoning a number.

### The client's ceiling is `uncontested`, and there is no `exact`

Deliberate, and the single most important thing on this page to get right.

A combat record opens on the **first hitsplat we witness**. Damage dealt before we
engaged is invisible to the client. So the strongest honest statement it can make
is "nobody else hit it after we turned up" — not "we earned this kill".

The grade was called `EXACT` until 2026-08-20. It was renamed because it claimed
something unobservable: thirteen multicombat kills showed zero foreign damage
while the game printed the ironman kill-credit warning. The grade was wrong and
looked fine.

**`exact` is reserved for the server**, where the monster's max HP makes
conservation checkable — deal less than max HP and it dies anyway, and the
difference came from someone else. If a true `exact` is ever assigned, it is
assigned server-side and it is a different claim from anything the client sends.
Do not display client data as `exact`.

### Accept `exact` on the wire, normalise on write

Ledgers written before the rename store the grade counts under the key `exact`.
The client self-migrates when reading them:

```java
@SerializedName(value = "uncontested", alternate = {"exact"})
public int uncontested;
```

Note **where** that alias lives: on `NpcStat` and `NpcStat.DayTally`, the stored
aggregate rows — **not** on the `Confidence` enum itself. A `KillRecord` in flight
always carries the current spelling.

Ingest should still accept `exact` as an alternate spelling and normalise it to
`uncontested` on write, because migrated ledgers and older clients exist. A hard
enum column that rejects `exact` will fail on real data.

## Two things the site must not claim

**Do not compare `totalDamage()` to the monster's max HP and call it 100%.** All
it proves is that nobody else hit it after we arrived. Pre-engagement damage is
invisible.

**Do not write "nobody else does this" anywhere.** Kill, dryness and drop trackers
already exist and are popular — Collection Log Luck (79k), Bossing Info (112k),
Dry Rate Tracker (26k). What is actually unbuilt is all monsters under one schema,
observed rather than published rates, and verified-from-zero. Sell that.

## Zero-damage kills are real

A record with `myDamage == 0` is **not** a bug and must not be dropped. A magic
splash is a real attempt, and it is exactly what makes observed accuracy
meaningful for Magic. Those arrive graded `inferred`.

Any ingest filter of the form `WHERE damage > 0` silently deletes them and biases
every Magic accuracy number upward.

## XP

`xp` is **measured** from the client's own XP drops and split by damage share. It
is not derived from damage, and the site must not recompute it.

Three reasons derivation cannot be the source of truth, all verified:
overkill grants no XP but hitsplats report damage *rolled*; the per-monster XP
bonus has manual overrides the published formula misses (Vorkath computes +20%
against a listed +0%); and rounding into tenths is undocumented.

Slayer XP is deliberately excluded — it is granted per kill rather than per
damage point, so it never enters a damage-proportional allocator.

## Privacy posture

Publishing is **opt-in**, not opt-out. Once public-by-default ships it cannot be
un-shipped, and opt-out means someone's data is exposed for however long it takes
them to notice. This is not ironman-specific: a main pushing a wall of ambiguous
kills has the same exposure problem.

The plugin must work for **all account types**.

## Changing this document

The client is the source of truth. Change `KillRecord`/`Confidence` and this file
together, in the same commit, or the two silently drift — which is exactly the
dirty data this project exists to avoid.
