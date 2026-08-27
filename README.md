# Everykill

Kill counts for every monster in the game, not just the ~90 on the hiscores.

**[everykill.com](https://www.everykill.com)**

## About

The official hiscores rank about 90 bosses. There are 1,757 monsters in the game, and
nobody knows who has killed the most Rockslugs, because nothing counts them.

This is a passion project — built because that gap bothered us, not because anyone
asked for it. It is actively developed and **new features land in the plugin and on
the site with every update**. If something is missing, it is probably on the list.

## What it does

Records a per-monster kill count for anything you kill, locally, and grades every
kill by how well it can be evidenced. It also measures per-monster combat experience.

Optionally — and **off by default** — it uploads those kills to
[everykill.com](https://www.everykill.com), where they rank you on monsters nobody
else ranks and compare your dry streak on a drop against other people actually
killing that thing. Your RuneScape name is never sent unless you separately ask for
it to be published.

The plugin is fully useful with upload off. Everything above happens locally first.

## Why it exists alongside Loot Tracker

RuneLite's Loot Tracker already stores per-NPC kill counts — `ConfigLoot` holds
`int kills` and `int[] drops` per monster, persisted, on by default. This plugin is
not a replacement for it and should be run alongside it.

The difference is what counts as a kill:

| | Loot Tracker | Everykill |
|---|---|---|
| Attribution | loot ownership — "did loot render for me" | damage — "did I hit it" |
| Kills that drop nothing | invisible (`LootManager` posts no event when the ground is empty) | recorded |
| Confidence | none | three grades on every kill |
| Cross-player ranks | boss KC via hiscores | any monster |

Neither approach is wrong; they answer different questions. Loot Tracker is about
loot. This is about the kill.

## The three grades

Every kill carries one, always. There is no shape in this plugin that expresses
"probably a kill" other than a grade — **classify, never fabricate**.

- **uncontested** — we hit it, we saw it die, and nobody else touched it while
  we were watching
- **inferred** — the death was deduced rather than observed: it despawned while
  flagged dead, or it vanished right after we used an item on it
- **ambiguous** — another player damaged it too, so we cannot claim it cleanly

Ranks and published rates read `uncontested` only. Totals read everything. The
colours (`#5f9e5f`, `#c9913c`, `#b45252`) are identical in the plugin and on the
site, so they are learned once.

The ceiling is `uncontested` and there is no `exact` — a record opens on the
first hitsplat we witness, so damage dealt before we arrived is invisible. See
`docs/spec-kill-contract.md`.

## Experience is measured, not derived

Combat XP is paid per point of damage, which makes it tempting to compute each
monster's share from our own hitsplats. That is wrong, and `docs/GAME-MECHANICS.md`
records why with sources: overkill grants no XP while hitsplats report damage rolled,
the per-monster bonus has manual overrides that ignore the published formula, and the
tenths-rounding is undocumented.

So the roles are the other way round. **The client's XP updates are the measurement**
— the game has already accounted for overkill, bonuses and rounding — and **damage is
only the allocator**, answering which monster the experience came from when several
were being hit. Experience that arrives with no damage on record is never pushed onto
the nearest monster; it is shown on the panel as unattributed, because a rising number
there is the symptom of a broken allocator and hiding it would hide the bug.

Slayer XP deliberately never enters this path: it is granted per kill, equal to the
monster's hitpoints, so a damage-proportional split would be silently wrong.

## What it deliberately does not do

Analysis lives on the server, not in the client. The rule for anything proposed
here: *could the plugin produce this display with no network call?* If not, it does
not belong in the client.

So there is no drop-rate display, no dry percentile, no pity bar, no GP/hr, no NPC
highlighting, no per-boss logic, and no chatbox input.

Discord notifications are handed to [Dink](https://github.com/pajlads/DinkPlugin)
rather than rebuilt — see `docs/DINK-INTEGRATION.md`.

## Compliance

Jagex's third-party client guidelines prohibit features that aid **boss fights** —
next-attack prediction, prayer switching indicators, attack counters, projectile and
impact locations, automatic stand-here markers.

A kill count is not one of those. The game publishes kill counts itself (chat
messages, HiScores, collection log, Combat Achievements), core RuneLite's Slayer
plugin ships a task counter as a default-on infobox, and many approved Plugin Hub
plugins display kill counts on the canvas.

What matters is the **trigger**, not the fact that the output is a number. Three
properties are load-bearing and must survive any future change:

1. Nothing subscribes to an NPC's animation, projectile, graphic, or incoming
   hitsplat. Counters move after something has already died.
2. There is no per-boss branch anywhere. Multi-phase handling is generic
   `NpcChanged` carry-forward — bookkeeping about identity, not advice about
   mechanics.
3. Nothing is drawn on or near an NPC.

`KillRecord.attacksCount` counts **our own** hitsplats, never the NPC's. Counting an
NPC's attacks is the thing Jagex names explicitly; counting your own is what core's
Special Attack Counter already does.

Jagex reserves the right to add to the prohibited list, so design to the trigger
rule rather than to the current wording.

## Layout

```
src/main/java/com/everykill/
├── EverykillPlugin.java          event wiring, lifecycle
├── EverykillConfig.java          config, incl. the required IP-address warning
├── detect/
│   ├── KillStateMachine.java     the rules — plain Java, no client dependency
│   └── KillDetector.java         adapter from RuneLite events onto the machine
├── xp/
│   ├── XpAttributor.java         measured XP, allocated by damage share — no client
│   ├── XpService.java            adapter for Skill / StatChanged
│   └── CombatSkill.java          the skills damage pays into; Slayer excluded
├── ledger/LocalLedger.java       per-NPC totals, RS-profile scoped, persisted
├── model/                        Confidence, DeathSignal, KillRecord, NpcStat
├── notice/MilestoneNotifier.java tier-1 notices — works offline, no server
└── ui/                           panel + overlay
```

`KillStateMachine` and `XpAttributor` have **no RuneLite imports** — they take
primitives, and `KillDetector` and `XpService` are the only things that touch client
types. Every interesting decision in kill detection is a judgement about evidence, and
none of it needs a game client to express or to test. That split is why the tests run
at all; keep it. Anything that needs a client to say belongs in the adapter.

One consequence worth knowing: the state machine tracks actors by an opaque key minted
in the adapter, **not** by `npc.getIndex()`. The game recycles an index as soon as its
slot frees, and keying on one drops kills silently — see `docs/FINDINGS.md`,
2026-08-20.

## Tests

`KillStateMachineTest` and `XpAttributorTest` cover the rules as executable statements.
The negative cases matter most — a tracker that over-counts is worse than one that
under-counts, because the error is invisible and inflates everything downstream at
once:

- a stranger's kill is never recorded
- one splat from another player contests a kill we otherwise dominated
- `ActorDeath` followed by `NpcDespawned` is one corpse, not two
- three phases of one boss is one kill, with damage accumulated across them
- an NPC that walked away is not a kill
- a transform death counts only when the player used an item on that exact NPC
  within 3 ticks of it vanishing — evidence, not a guess
- experience with no damage on record is reported, not forced onto a monster
- a split never leaks or invents experience: the parts sum to the whole

## Building

```
./gradlew build
./gradlew run     # launches a dev client with the plugin loaded
```

Java 11, BSD 2-Clause, no third-party dependencies, `build=standard`. Compilation is
pinned to Java 11 by a toolchain block, so the JDK on your PATH does not affect the
output.

**Gradle itself must run on JDK 22 or older.** Gradle 8.10 cannot run on JDK 23+, and
Lombok 1.18.30 cannot initialise its annotation processor there either — the failure
is `ExceptionInInitializerError` or `Unsupported class file major version`, both of
which read like code faults and are neither. IntelliJ is fine as shipped (it uses the
project JDK 17). From a terminal on a newer default JDK, point Gradle at an older one:

```
JAVA_HOME="/c/Program Files/Java/jdk-17" ./gradlew build
```

## Known open items

These are placeholders chosen at a desk, not measured. They want real play data
before P3:

- `STALE_TICKS = 100` — how long a damage record survives without activity
- `EMITTED_TICKS = 10` — double-fire suppression window
- `FINISH_WINDOW_TICKS = 3` — how recently an item-use counts as a transform death
- `SETTLE_TICKS = 2` — how far back an XP drop may reach for its damage
- notice cooldown 45s, session cap 12, milestone ladder 100/250/500/1k/2.5k/5k/10k

## Documentation

`docs/BUILD-ORDER.md` is the task list and the place to start.
`docs/GAME-MECHANICS.md` holds every game fact the code depends on, with a source and
a date on each. `docs/FINDINGS.md` is the append-only record of what has actually been
established. `docs/INTEGRITY.md` defines what counts as verified.

## Not affiliated with Jagex

Old School RuneScape is a trademark of Jagex Ltd. This is an unofficial fan project.
