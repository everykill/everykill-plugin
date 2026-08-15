# Product direction

The consolidated view: what Everykill is, what it isn't, every feature agreed so far, and everything deliberately cut.

`docs/PROJECT.md` says what to build and how. `BUILD-ORDER.md` says when. **This says why, and what it all adds up to.**

Last consolidated: 2026-08-14.

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

---

## 3. Feature registry

### Tier 1 — foundation

Nothing works without these.

- Per-skill, per-mob XP tracking
- Per-mob kill counts via runtime `npc_id` auto-discovery — **no hardcoded mob list, ever**
- Client-side event batching (2–5 min, floor 60s)
- Drop logging tied to kill count
- Session and loadout capture
- Player accounts, RSN verification via the logged-in account name
- Basic hiscores filterable by mob, combat style, account type
- Player search and profile pages
- **The Kill Log panel** — local, no account needed

### Tier 2 — the draws

What actually pulls people in.

- **Kill Log completion** — "412 of 1,847 monsters." A number nobody has ever seen about themselves
- **Expected vs actual** — luck percentile via binomial maths against wiki rates, both lucky and dry
- **Dry-streak hall of fame** and the **spooned board** (lowest KC per unique)
- **Live global drop feed** — rarity/GP threshold slider, filters, **opt-in naming**, Discord webhook, OBS streamer overlay, per-player flood cap
- **Race boards** — new content on release day, seasonal resets, first-to-N, first verified drop per item. Inherently verified-from-zero since nobody has prior kills on a new monster
- **First-to-rank onboarding** — most monsters have nobody tracked, so an early user holds dozens of world records within an hour. Strongest first-session payoff we have, and a direct consequence of being first
- XP/hr, GP/hr, supplies cost/hr; GP per task and per kill
- Personal bests — fastest kill, best session per mob
- Gear/inventory linkage shown per kill and per session
- Global counters with hour/day/week/month rollups
- Shareable profile image cards; badge images for rare drops showing rate and KC

### Tier 3 — depth

Retention.

- **Damage efficiency** — observed DPS ÷ theoretical DPS. Works at N=1
- **Uptime efficiency** — observed kills/hr ÷ theoretical kills/hr at your own observed DPS. Isolates banking, travel, respawn and AFK with gear removed. Also works at N=1
- **Cohort medians by observed DPS band**, added monster by monster as each crosses its sample threshold
- **"What should I kill?"** as an *observation table*, not advice — what players in your DPS band actually killed and how it went
- Controlled gear comparison — hold one item constant, vary one slot
- **First-N-kills cohort view** — a mob's learning curve, kills 1–50 vs 500–550. Only possible because we track from zero
- Community notes and guides, upvote/downvote, gear loadouts attached, trust-level anti-spam
- Achievement system; **Explorer** badge for most distinct monsters killed
- Regional completion — everything in the Slayer Tower, the Catacombs, Fossil Island
- Collection log progress per mob with kills-to-completion estimate
- Deaths and tick efficiency; nemesis stat, deadliest mobs, deaths per 100 kills by observed DPS band
- Head-to-head player comparison
- **Run records** — a generic bounded group of kills with a start reason, end reason and type. A slayer task is a run; a boss trip is a run; a session is the fallback run. One schema covers task-level and session-level records rather than a toggle
- Session records, daily streaks, "on this day" recaps
- Mob of the month **and the inverse** — forgotten/least-killed board; top and bottom on the homepage
- **Lifetime loot totals** — most of any single item looted, lifetime GP looted, rarest item obtained. A stat, not a completion log, so it isn't gameable
- Free rate-limited public read API with required attribution
- Kills per level, XP per task
- Privacy tiers — private / friends / public, per data type

**Tier 3 ordering matters.** Per `spec-performance.md` §8: record the inputs now (they can't be backfilled) → ship the single-player metrics, which work from the first kill on the most obscure monster in the game → add cohort medians monster by monster as each crosses its threshold → recommender last. The personal metrics ship early; the comparative ones fill in over time. Nothing waits on volume that doesn't exist.

### Tier 4 — later

- Clan and group competitions on specific mobs
- Crash-death stats — inference only, profile-level not a global board
- Embeddable widgets carrying site branding
- Periodic public data reports
- Revenue model — **deferred entirely**

### Fun stats — explicitly not verified hiscores

Gameable, so kept separate and never feeding efficiency scoring:

- Lifetime thrall damage, and thrall damage as % of total
- Cannon damage totals
- Poison/venom, recoil and burn damage dealt
- Highest share of a single kill done by thralls
- Per-mob community meta breakdown — what fraction of damage on this mob comes from each source

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

| Target | Why |
|---|---|
| **WikiSync** (325k) | The existing RuneLite→wiki pipeline. Route to the Drop Rate Project. Contact via the wiki Discord's #drop-logs |
| **Loot Lookup** (153k) | Supply observed rates alongside published wiki rates |
| **Dink** (66k) | It handles Discord delivery; we supply events |
| **Wise Old Man** (81k) | They own skills and bosses, we own per-mob. Cross-link profiles |
| **collectionlog.net** | Public API returns per-page kill counts — could seed boss KC so a new profile isn't empty |
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
- **Backend stack and hosting** — Postgres likely; self-hosting under consideration
- **Canonical idle timeout** — must be fixed before any session record publishes
- **Minimum sample thresholds** — the specific numbers behind every hide-thin-data rule
- **Operating entity and jurisdiction** — required before the privacy policy

---

## 9. Sequencing

**Now** — finish client-side detection through the build order. Nothing user-facing until the numbers are right.

**Then** — the Kill Log panel and local UX. This is a complete, shippable product with no backend.

**First Plugin Hub submission: local-only, no upload.** The first submission gets the full review; updates are mostly auto-approved. A local-only version removes the highest-risk review surface entirely, starts accumulating installs and feedback while the backend is built, and teaches us the process before there's anything complicated to defend. Upload arrives later as an update to an already-approved plugin.

**Then** — backend, accounts, hiscores, and everything in Tier 2.
