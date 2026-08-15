# Spec — Plugin UX

Every surface the plugin touches in the client, what appears there, and why.

This is the half of the project that decides whether anyone installs it. Detection correctness is invisible to users; this is not.

---

## Design principles

These override individual feature decisions.

1. **Nothing is editable.** Not kill counts, not sessions, not records. Users can set goals, hide entries, and choose what displays — they can never change a recorded number. This is the line between a tracker and a hiscore client, and it is not negotiable.
2. **Fully useful with upload off.** Every feature below works locally. Upload adds ranking; it is never required for value. A player who never creates an account should still want this installed.
3. **Nothing mid-fight that informs the next action.** Counts and progress are fine. Timing, advice, targeting, thresholds-as-prompts are not. See the Jagex restrictions in `docs/CONVENTIONS.md` — the safe formulation is *live surfaces show what has happened, never what to do next*.
4. **Quiet by default, loud by choice.** Most notifications default off. The exceptions are the two genuine delight moments (first kill of a new monster, goal reached). Plugins that shout on install get uninstalled.
5. **No configuration required to be useful.** It should do the right thing on install with zero setup.

---

## 1. Side panel — the primary surface

Four tabs. This is where the plugin lives.

### 1a. Kill Log *(headline feature)*

The collection log's missing twin. Every monster in the game, killed or not.

- **Completion header** — "412 / 1,847 monsters logged (22%)" with a progress bar
- **Grouped views**, switchable: by Slayer master · by region/dungeon · by monster attribute (demon, dragon, undead) · by combat level band · flat A–Z
- **Per-entry row**: icon, name, lifetime KC, first-killed date. Unlogged entries greyed with the name still visible — knowing what you're missing is the point
- **Search box** — instant filter by name
- **Expand a row** for detail: KC, total XP earned, best kill time, drops received with counts, your dryness position on rares, gear used most often
- **Sort**: most killed, least killed, recently killed, alphabetical
- **Filters**: hide unlogged · slayer-assignable only · members/F2P · current task only

**Deliberately excluded:** editing any count. There is no edit control anywhere in this panel.

### 1b. Session

Live view of the current play session.

- Kills, XP gained, time elapsed, kills/hour, XP/hour
- Breakdown by monster, sorted by kills
- Supplies consumed, damage taken, deaths
- Current slayer task and progress, read from varbits
- **Session boundary is fixed** (10 min idle or logout) and shown, so the numbers mean the same thing as everyone else's

### 1c. Goals

The idea Monster Monitor gets right and we should match.

- Set a kill goal on any monster — "500 rockslugs"
- Progress bar per goal, ordered by nearest completion
- Notification on completion (see §5)
- Goals are user-set targets, **not** user-set counts. Setting a goal never touches the KC
- Multiple concurrent goals
- Auto-suggest a goal when a slayer task starts, matching the task amount — one click to accept

### 1d. Records

- Personal bests: fastest kill per monster, best session KC, best XP/hour
- Milestones reached, with dates
- Luckiest drop and current longest dry streak
- Account link status, last upload time, queued event count — plain and always visible

---

## 2. Overlay — minimal and optional

Off by default. When on, one compact box.

- **Current target counter**: monster name, kills this session, lifetime KC
- **Active goal progress bar**, if a goal exists for the current target
- Position draggable, opacity configurable, individually toggleable lines

**Hard limits:** no timers, no "X kills until", no rate projections shown mid-fight, no anything tied to a monster's mechanics. Counts and progress only. If a proposed overlay element would change what a player does *in the next tick*, it doesn't ship.

---

## 3. Infobox

For AFK content where the panel isn't visible.

- One infobox per active goal: monster icon, current/target
- Tooltip with full detail on hover
- Off by default, single toggle to enable

---

## 4. Chat commands

How OSRS players actually share stats, and the cheapest virality we have.

- `!kc <monster>` — your KC for that monster
- `!killlog` — completion count and percentage
- `!ek <monster>` — full line: KC, XP, dryness on that monster's rare

Standard caveat: only visible to other players who also have the plugin. Implemented via `ChatCommandManager`, same pattern as Collection Log's `!log`.

---

## 5. Notifications

Three events, each individually configurable across chat / sound / popup / tray.

| Event | Default | Why |
|---|---|---|
| **First kill of a new monster** | **On** (chat only) | The delight moment. Mirrors a collection log slot unlocking, and drives the completion metric |
| **Goal reached** | **On** | The user explicitly asked to be told |
| **KC milestones** (100, 500, 1000, 5000…) | Off | Nice, but noisy for people grinding thousands |

Message format configurable. Popups queue rather than overlap.

---

## 6. Menu entries

Right-click an NPC:

- **Set goal** — opens the goal dialog prefilled
- **Hide from log** — excludes from panel display only, never from recording

Both are client-side. Neither sends an action to the server, so both stay clear of the menu restrictions in `docs/CONVENTIONS.md`. Hiding is a display preference and must never affect what is recorded or uploaded.

---

## 7. Config panel

Grouped, with sane defaults. Over-configuration is a common plugin failure — every option here must earn its place.

- **General** — panel behaviour, default grouping, session idle display
- **Overlay** — enable, position, which lines
- **Notifications** — per-event toggles and formats
- **Goals** — auto-suggest on slayer task
- **Upload** — the opt-in toggle, carrying the exact required third-party warning text, with a description listing every field sent. Disabled by default
- **Data** — open local data folder, export local data, delete local data

---

## 8. First-run experience

Usually neglected, and it's what decides whether the plugin survives the first session.

On first load, the panel shows a short welcome rather than an empty list:

1. What it does, in one sentence
2. "Your kill log is empty — go kill something and watch it fill"
3. Explicit statement that **everything works without an account**, and that upload is off unless enabled
4. One dismissible prompt to set a first goal

Then the first kill logs and the first-kill notification fires. That's the hook — the user sees the mechanism work within a minute of installing.

---

## 9. Upload state visibility

If upload is enabled, the user must always be able to see:

- Whether it's currently on
- When it last succeeded
- How many events are queued
- A plain-language list of what is sent

Never silent, never ambiguous. A tracker that uploads invisibly is exactly what makes people distrust plugins.

---

## Open questions

- Icons: monster icons aren't readily available as sprites the way item icons are. May need model-based rendering, a placeholder set, or a name-only list at first
- Kill Log denominator: needs a defensible count of killable monsters and a definition of what counts as one entry (variants, quest-only NPCs, dummies)
- Whether the Kill Log groups by `npc_id` or by display group, and how that interacts with the raw-id storage rule
- Panel performance with ~1,800 rows — likely needs virtualisation or lazy grouping
