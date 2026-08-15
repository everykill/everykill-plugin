> **Historical.** Written before the Everykill naming and the current damage-first
> kill-detection architecture (see `docs/PROJECT.md`, `BUILD-ORDER.md`). Describes a
> different feature set (XP/location/gear snapshot + gist upload) than what's
> being built now. Kept for history, not as current design.

# Everykill

A local RuneLite plugin that tracks XP rates, location, gear and loot, and writes
them to a JSON snapshot you can paste into a conversation or serve remotely.

Built to answer questions that general guides can't: *what is my actual XP rate on
this task, with my gear, at my levels* — rather than a figure written for an
account twenty levels stronger.

## What it captures

**XP rates.** Every skill's gain since the session started, plus an hourly rate.
Sessions auto-label themselves with your current slayer task, so you get
"Bloodveld: 41,203 strength xp/hr" rather than an undifferentiated blob. Sessions
reset after a configurable idle period so a lunch break doesn't wreck the average.

**Location.** World coordinates, plane, region ID and current world.

**Gear.** Everything equipped and everything in your inventory, by name and
quantity.

**Kills and loot.** A running count per monster, and every drop received with its
high-alch value. Useful for questions like "have I actually seen that 1/128 yet."

**Slayer task.** Current assignment and remaining kill count, parsed from chat.

## Setup

You need JDK 11 or newer and a copy of RuneLite you can run from source.

1. Clone RuneLite's plugin template or an existing plugin project, or use this
   directory directly as a Gradle project.
2. Drop the `com.everykill` package into your plugin source tree.
3. Run RuneLite in development mode with `--developer-mode`, which makes
   side-loaded plugins available.
4. Enable **Everykill** in the plugin list.

Snapshots are written to:

```
~/.runelite/everykill-plugin/snapshot.json
```

## Configuration

| Setting | Default | What it does |
| --- | --- | --- |
| Auto-write every (min) | 5 | How often to write a snapshot. 0 disables. |
| Write on logout | on | Final snapshot when you log out. |
| Session idle timeout (min) | 15 | Reset XP rates after this long idle. 0 never resets. |
| Track location | on | Include coordinates and region. |
| Track kills and loot | on | Count kills, log drops. |
| Track equipment and inventory | on | Include worn and carried items. |

### Optional: gist upload

If you want the snapshot readable from outside your machine, create an empty
secret gist on GitHub, then set:

- **Upload to gist** → on
- **Gist ID** → the hex string from the gist's URL
- **GitHub token** → a personal access token with `gist` scope

The plugin will `PATCH` the gist on every write. The token is stored by RuneLite
and only ever sent to `api.github.com`. Nothing else reads it.

Use a **secret** gist rather than public — it still has a guessable-in-principle
URL, but it won't be indexed or searchable. Only put in it what you're comfortable
having live at a URL.

## A caveat on this code

I wrote this without being able to compile against RuneLite's API, which changes
between releases. The logic is sound and the XP curve is unit-tested, but expect
to fix a few imports or method signatures on first build — `InventoryID`,
`NpcLootReceived` and the config `secret` attribute are the most likely
candidates, as those have moved around across versions.

The XP curve implementation is verified against known values (level 2 = 83,
level 63 = 368,599, level 99 = 13,034,431) and reproduces real in-game
"remaining XP" figures exactly.

## Reading a snapshot

```json
{
  "generatedAt": "2026-08-12T18:04:11Z",
  "player": "Everykill",
  "session": {
    "label": "Bloodveld",
    "elapsedSeconds": 2841,
    "totalXpGained": 47320,
    "totalXpPerHour": 59960,
    "bySkill": {
      "Strength": { "gained": 28104, "perHour": 35608 },
      "Hitpoints": { "gained": 9368, "perHour": 11869 },
      "Slayer":    { "gained": 9848,  "perHour": 12478 }
    }
  },
  "task": { "name": "Bloodveld", "remaining": 41 },
  "killCounts": { "Bloodveld": 32 },
  "drops": [
    { "item": "Blood rune", "quantity": 10, "from": "Bloodveld", "alchValue": 240 }
  ]
}
```

That `perHour` figure is the thing worth having. Everything else is context.
