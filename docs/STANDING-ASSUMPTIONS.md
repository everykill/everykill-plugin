# Standing assumptions

Every external fact this project depends on, when it was last verified against a **primary** source, and how often it needs re-checking.

## Why this file exists

On 2026-08-14 the project's stated differentiator — that we would produce more accurate drop rates than existed — was found to be **wrong**. The Drop Rate Project already has ~80 billion kills of Loot Tracker data, drop subroutine source code for ~150 monsters supplied by the OSRS development team, and exact rates for roughly 99% of monsters. RuneLite core also ships an OSRS Wiki Crowdsourcing plugin, enabled by default since 1.6.28.

The claim had been carried since the foundation doc, sourced from a **search snippet** that said ~95%, never re-verified against the wiki page itself. It survived several rounds of planning because nobody re-checked a premise once it was written down.

**The rule that follows: a claim that justifies the project's existence gets verified against a primary source, and re-verified on a schedule. Not once.**

---

## How to use this file

- **Primary source only.** A search snippet, a summary, or a secondary wiki is not verification. Fetch the actual page, repo, or API.
- **Date every entry.** An unverified date is an expired entry.
- **Re-check on cadence, not on doubt.** By the time something feels doubtful it has usually already been wrong for months.
- **When an entry turns out wrong, log it in `FINDINGS.md` as `contradicted-spec`** and update every doc that depended on it — not just this one.

---

## Register

### Project premise

| Assumption | Status | Last verified | Cadence |
|---|---|---|---|
| Official OSRS hiscores cover only ~90 bosses; no per-monster kill counts exist anywhere | Holds | 2026-08-14 | Quarterly |
| No plugin or site tracks kills/XP across **all** monsters uniformly | Holds — all competitors are per-content | 2026-08-14 | Quarterly |
| Nobody publishes cross-player per-mob comparison or ranking | Holds | 2026-08-14 | Quarterly |
| ~~We can produce more accurate drop rates than exist~~ | **FALSE** — wiki has ~99% exact from ~80bn kills | 2026-08-14 | — |
| The wiki has monsters that don't work with RuneLite's Loot Tracker | Holds — this is our contribution niche | 2026-08-14 | Quarterly |

### RuneLite platform

| Assumption | Status | Last verified | Cadence |
|---|---|---|---|
| Plugin Hub requires BSD 2-Clause, Java 11, no reflection/JNI/native | Holds | 2026-08-14 | Per release |
| Third-party upload requires the exact IP-address warning, disabled by default | Holds | 2026-08-14 | Per release |
| Zero new dependencies keeps `build=standard` and expedited review | Holds | 2026-08-14 | Per release |
| `ActorDeath` fires at health-ratio-zero, not actual death | Verified empirically + issues #12453, #15394, #16479 — all still open | 2026-08-14 | Per release |
| `LootManager` clears item spawns per `GameTick`; `ItemSpawned` precedes `NpcDespawned` | **Inherited assumption, not our own measurement** | 2026-08-14 | Per release |
| Slayer state is read via `DBTableID.SlayerTask` / `SlayerArea` + `VarbitID.SLAYER_TASKS_COMPLETED`; old `VarPlayer` constants deprecated | Holds | 2026-08-14 | Per release |

### External data sources

| Assumption | Status | Last verified | Cadence |
|---|---|---|---|
| Wiki Bucket API (`action=bucket`) is the supported query interface; `action=ask` hard-deprecated | Holds | 2026-08-14 | Quarterly |
| Wiki content is CC BY-NC-SA 3.0 — non-commercial | Holds | 2026-08-14 | Annually |
| Wiki real-time prices API is explicitly public-use, separate from the content licence | Holds | 2026-08-14 | Annually |
| Jagex third-party guidelines prohibit only combat-assist features | Holds — list is explicitly non-exhaustive and can grow | 2026-08-14 | Per OSRS update batch |

### Ecosystem

| Assumption | Status | Last verified | Cadence |
|---|---|---|---|
| No Plugin Hub plugin does cross-content per-mob tracking | Holds | 2026-08-14 | Quarterly |
| Discord webhooks solved by Dink; raid dryness by Raid Data Tracker; in-client drop tables by Loot Tracker/Loot Lookup | Holds | 2026-08-14 | Quarterly |
| Tracking plugins cluster at 20k–80k installs; scale target ~10k users | Holds | 2026-08-14 | Annually |

---

## Recurring checks

**Per RuneLite release** — read the release blog and changelog. Watch for: deprecated API we use, event behaviour changes, Plugin Hub rule changes, new core plugins that overlap us.

**Per OSRS game update batch** — watch for: new monsters (auto-discovery should handle it, but transform-death mechanics need adding by hand), drop table changes, XP formula changes, new world types, anything that invalidates a patch-tagged benchmark.

**Quarterly** — re-read the Drop Rate Project page, scan the Plugin Hub for new overlapping plugins, re-check that our premise claims still hold.

**Annually** — licences, terms, scale assumptions.

---

## Open items with no owner yet

These are known-unverified and should not be treated as settled.

- **Tick ordering** of `ItemSpawned` vs `NpcDespawned` — inherited from `LootManager`, never measured by us. The lootless-kill finding depends on it
- **XP settle window** and residual noise floor — unmeasured, needed for Step 5
- **Zygomite `_CAP` variants** — excluded on plausibility, needs 57 Slayer to test
- **Multi-phase boss carry-forward** — structurally untestable on this account
- **Whether `ActorDeath` can fire twice** on one NPC that regenerates
