# Product direction

The consolidated view: what Everykill is, what it isn't, and everything deliberately cut.

`docs/PROJECT.md` says what to build and how. `BUILD-ORDER.md` says when. **This says why, and what it all adds up to.**

**Scope of this file is the plugin.** The companion site — hiscores, profiles, leaderboards, the public API, hosting and backend decisions — moved out on 2026-08-22 and is maintained separately. What stays here is the site's *effect on the client*: which inputs must be recorded now because they cannot be backfilled later.

Last consolidated: 2026-08-14. Site content split out 2026-08-22.

---

## 1. The claim

> **Official OSRS hiscores rank ~90 bosses. Everykill ranks everything else.**

Per-monster kill counts do not exist anywhere — not in the game, not in the hiscores, not in the collection log. The collection log tracks boss counters and some *activity* counters (chests opened, laps run); it has never tracked regular monsters. Collection Log Luck's own README names this as its limitation: it can't calculate luck for everything because KC isn't tracked for some monsters.

The only possible source is a client plugin. That's the moat: the dataset can't be scraped, bought, or replicated without shipping a plugin and earning users.

### What we are not claiming — corrected 2026-08-14

**We do not produce better drop rates.** The wiki's Drop Rate Project has ~80 billion kills of Loot Tracker data, drop subroutine source code for ~150 monsters supplied by the OSRS dev team, and exact rates for ~99% of monsters. RuneLite core also ships their crowdsourcing plugin, enabled by default since 1.6.28.

**We consume wiki rates. We don't compete on them.** They are the denominator for our luck and dryness features. Attribution required, CC BY-NC-SA applies.

This claim was wrong in the docs for weeks. See `STANDING-ASSUMPTIONS.md` for the mechanism that exists to stop it recurring.

---

## 2. Two products, one dataset

### The plugin — a local tool that needs no account

Headline: **the Kill Log**. The collection log tracks every item you've obtained; nothing tracks every monster you've killed. That's the pitch in one sentence, and it works entirely offline.

Full surface spec in `spec-plugin-ux.md`. Everything must work with upload disabled — both a Plugin Hub requirement and the right product call.

### The site — per-monster hiscores

Everything the plugin records exists to populate leaderboards nobody else can build. Kill detection produces the counts, XP attribution produces the rates, drop attribution produces the dryness position, gear snapshots produce the tier benchmarks.

**The site's own feature set is out of scope here.** What matters client-side is §3.

---

## 3. What the client must record for the site to be possible

The site is not built and has no backend. But its inputs **cannot be backfilled** — a kill that happened before we recorded a field is gone. So the recording obligations land now, and the analysis lands whenever the site does.

- Per-skill, per-mob XP
- Per-mob kill counts via runtime `npc_id` auto-discovery — **no hardcoded mob list, ever**
- Drop logging tied to kill count
- Session and loadout capture, gear/inventory linkage per kill and per session
- Client-side event batching (2–5 min, floor 60s)
- **The Kill Log panel** — local, no account needed
- Enough per-kill detail to compute damage efficiency and uptime efficiency later. Both work at N=1, so they are the first metrics that become real; neither can be reconstructed from a count alone
- Whether a log **ends at a drop** — needed to correct stopping-rule bias (see §6)

**Ordering, per `spec-performance.md` §8:** record the inputs now → single-player metrics, which work from the first kill on the most obscure monster in the game → cohort medians monster by monster as each crosses its threshold → recommender last. Nothing waits on volume that doesn't exist.

### Gameable stats stay quarantined

Thrall damage, cannon damage, poison/venom/recoil/burn totals, and per-mob damage-source breakdowns are all trivially inflated. Record them, but they **never feed efficiency scoring or any ranked figure**.

---

## 4. Explicitly cut

Each of these was on the list and was removed after checking. Cutting them sharpens the pitch and saves build time without losing anything defensible.

| Cut | Reason |
|---|---|
| **Producing our own drop rates** | Wiki has ~99% exact from ~80bn kills |
| **Searchable drop-odds database** | Loot Lookup (153k) owns it in-client, the wiki owns it on the web, and it carried the CC BY-NC-SA risk |
| **Discord webhook notifications** | Dink (66k) does this well. Emit events it can consume |
| **Raid drop tracking** | Raid Data Tracker (57k) handles CoX points and splits properly; Dry Rate Tracker (26k) covers dryness. Deep work we'd do badly |
| **In-client drop table display** | Loot Lookup owns the surface |
| **Session GP/hr as a headline** | Three plugins cover it in-client. Our value is comparative, not the raw number |
| **Progressive information unlock** | Gating what a player can look up on the wiki in three seconds is fake friction. Only *their own* data gates, and only where the sample genuinely isn't meaningful yet |
| **Item pickup completion log** | Trivially gameable — drop, re-pick-up. Replaced by lifetime loot totals tied to attributed drops |
| **Composite efficiency score** | Any weighting between xp/hr, gp/hr and deaths is our opinion presented as measurement, and composites hide their inputs. Replaced by damage efficiency and uptime efficiency as two separate figures |
| **Absolute gear tiers** | No single axis puts a whip-and-Bandos setup on the same scale as blowpipe-and-Armadyl, and naming tiers implicitly ranks gear. Replaced by observed DPS bands, which are measured rather than judged |

---

## 5. Competitive position

The full survey is in the competitive landscape doc. The short version: **everything on the Plugin Hub is per-content. Nothing is cross-content.**

The closest overlaps, and why we differ:

- **Monster Monitor** — a kill log exists, ~1 GitHub star, <16k installs. Keys on npc **name**, has **user-editable kill counts** (disqualifying for hiscores), and shows no sign of damage attribution so it likely inherits the `ActorDeath` transform-death bug. Ours: damage-attributed, npc_id-keyed, transform-death aware, immutable, ranked
- **Collection Log Luck** (79k) — luck for collection log items using published rates, per-player only. Ours: every monster, cross-player percentile
- **Bossing Info** (112k), **Dry Rate Tracker** (26k), **Raid Data Tracker** (57k) — all single-content
- **Delve / Nex / ToB drop calculators** — three people built the same thing for three bosses. Nobody built the general version

### Integration targets

Marked **[site]** where the integration is the website's job, not the plugin's.

| Target | Why |
|---|---|
| **WikiSync** (325k) | The existing RuneLite→wiki pipeline. Route to the Drop Rate Project. Contact via the wiki Discord's #drop-logs |
| **Loot Lookup** (153k) | Supply observed rates alongside published wiki rates |
| **Dink** (66k) | It handles Discord delivery; we supply events |
| **Wise Old Man** (81k) | **[site]** They own skills and bosses, we own per-mob. Cross-link profiles |
| **collectionlog.net** | **[site]** Public API returns per-page kill counts — could seed boss KC so a new profile isn't empty |
| **Monster Stats** (31k) | Existing NPC stat dataset and a working `Name#Variant` bridging pattern |

**The wiki contribution that's actually valuable:** they state their gap is *monsters that don't interact well with RuneLite's Loot Tracker*. That's our transform-death category exactly. We approach offering that, not drop rates in general.

---

## 6. Constraints that shape every feature

- **Nothing is editable.** Goals are user-set targets, never user-set counts. Hiding is display-only
- **Fully useful with upload off**
- **Nothing mid-fight that informs the next action.** Live surfaces show what has happened, never what to do next
- **Unknown ≠ empty.** Unattributable loot is excluded from denominators, never counted as dry
- **Classify, never correct**
- **Descriptive, never prescriptive** — better-geared players are also better players, and that confound can't be removed
- **Publish sample sizes; hide thin data**
- **Store raw `npc_id` forever**
- **Never collect data about other players**
- **Default every view to normal worlds**; store Leagues/DMM/high-risk but exclude silently

---

## 7. Known methodological risks

- **Stopping-rule bias.** Players stop killing once they get the unique. Any dataset where tracking ends at the drop is truncated, and naive `items ÷ kills` **overestimates** the rate. Affects anything we contribute back to the wiki, and biases dry-streak boards the opposite way — dry players keep tracking, spooned players stop. Flag whether a log **ends at a drop**
- **Self-selection.** Plugin users aren't a random sample of players. Affects efficiency benchmarks more than drop data
- **Tick-ordering assumption** — inherited from `LootManager`, never measured by us. The lootless-kill finding depends on it

---

## 8. Open decisions

- **Monster icons** — not available as sprites the way item icons are. A Kill Log without icons is a much weaker product. Needs research before it becomes a build surprise
- **Kill Log denominator** — a defensible count of killable monsters, and a definition of what counts as one entry (variants, quest-only NPCs, dummies)
- **Whether the Kill Log groups by `npc_id` or display group**, and how that interacts with raw-id storage
- **Panel performance** at ~1,800 rows
- ~~Efficiency scoring formula~~ · ~~Gear tier definitions~~ — **resolved**, see `spec-performance.md`
- **DPS band boundaries** — how wide, and whether fixed or per-monster
- **Fight time definition** — elapsed ticks vs attack-attempt ticks for observed DPS
- **Storage and cost model** — ~300 bytes/kill, design for ~10k users; retention policy for raw events
- **Canonical idle timeout** — must be fixed before any session record publishes
- **Minimum sample thresholds** — the specific numbers behind every hide-thin-data rule

Site-side and tracked separately, but they gate the plugin's upload feature: **backend stack and hosting**, and **operating entity and jurisdiction** — the latter is required before a privacy policy exists, and a privacy policy is required before upload can ship.

---

## 9. Sequencing

**Now** — finish client-side detection through the build order. Nothing user-facing until the numbers are right.

**Then** — the Kill Log panel and local UX. This is a complete, shippable product with no backend.

**First Plugin Hub submission: local-only, no upload.** The first submission gets the full review; updates are mostly auto-approved. A local-only version removes the highest-risk review surface entirely, starts accumulating installs and feedback while the backend is built, and teaches us the process before there's anything complicated to defend. Upload arrives later as an update to an already-approved plugin.

**Then** — backend, accounts, hiscores, and everything the site needs.
