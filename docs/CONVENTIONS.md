# RuneLite Plugin Development — Agent Guidelines

## Precedence — read this first

**This file and the project's own documentation are canon.** Where anything disagrees,
the order is:

1. **This file** (`CONVENTIONS.md`) — RuneLite and Plugin Hub rules. Non-negotiable;
   breaking these gets the plugin rejected.
2. **`PROJECT.md`** — project constraints and the integrity rules the dataset depends on.
3. **The rest of `docs/`** — `BUILD-ORDER.md` is the task list, `FINDINGS.md` is the
   append-only record of what has actually been measured, `spec-*.md` are designed but
   not all verified.
4. **Installed agent skills and plugins** — last.

**Third-party agent skills are complementary workflow tooling, not project authority.**
They describe how to work — debugging discipline, TDD loops, review passes, handoffs —
and that is genuinely useful. They do **not** dictate project-specific behaviour, file
layout, or where work is tracked.

Concretely, when an installed skill's convention collides with ours, ours wins:

| A skill may assume | This project uses |
|---|---|
| `CONTEXT.md` for project context | `docs/PROJECT.md` |
| GitHub Issues for work tracking | `docs/BUILD-ORDER.md` |
| `docs/adr/` for decisions | `docs/FINDINGS.md` (measured results, append-only) |

Do not create a parallel copy of something this repo already has. Two sources of truth
agree on the day they are written and quietly diverge afterwards, and the whole point of
this project is that the data can be trusted.

## Logging

- Use `log.debug()` for developer/diagnostic logging.
- Do not use `log.info` for per-frame or per-event logging - RuneLite runs at INFO level in production, so high-frequency info logs will pollute user logs. `log.info()` is fine for one-time startup/shutdown messages or infrequent events.

## Threading & Concurrency

- Never use `Thread.sleep()`.
- Never block on `shutDown()` or `startUp()` — don't call `executor.awaitTermination()` in shutdown, just use `shutdownNow()`.
- Never do blocking network IO or disk IO on the client thread. The OkHttp thread pool can be used for blocking network requests.
  If you need to call back into `client` from the okhttp threadpool, such as from the response queued with `enqueue()`, use `clientThread.invoke()`
- Explicitly cancel scheduled tasks (e.g. `ScheduledFuture`) on shutdown, in addition to shutting down the executor.
- For batching async work, use `CompletableFuture.allOf()` — not `CountDownLatch`.
- If you must use `Process.waitFor()`, always pass a reasonable timeout.

## Performance

- Don't scan the entire scene every tick or frame. Use events such as object and npc (de)spawn to track what you care about and maintain your own collection.
- Keep the computations in Overlays, which are run each frame, to a minimum.

## API Usage

- Use `net.runelite.api.gameval` package constants — `ItemID`, `InterfaceID`, `ObjectID`, etc. Never hardcode magic numbers when gameval constants can be used instead.
- Use `LinkBrowser` to open URLs, not `java.awt.Desktop`
- When looking up Widgets, pass the component ID from gamevals (eg `client.getWidget(InterfaceID.DomEndLevelUi.LOOT_VALUE)`) - do not manually combine interface + component child IDs.
- Use of Java reflection is forbidden.

## HTTP & JSON

- Use OkHttp for all HTTP requests. `@Inject OkHttpClient` to get the HTTP client. Do not use `HttpURLConnection`, `java.net.http.HttpClient`, or Apache HttpClient.
- Use `@Inject Gson` to get a Gson instead, never create your own from scratch. You can use `.newBuilder()` to create one derived from the base `Gson.`
- Do not add transitive dependencies from `runelite-client` directly to `build.gradle`, such as gson, guice, or okhttp.
- Never execute okhttp calls on the client thread. Prefer using `enqueue()` which places the request on the okhttp threadpool.

## File I/O

- Only read/write files inside the `.runelite` directory. Create a subdirectory for your plugin (e.g. `.runelite/your-plugin-name/`) if you need to store data on disk.
- Use `RuneLite.RUNELITE_DIR` to get the path.
- Alternatively, use `JFileChooser` for user-initiated file operations.

## Config

- Config group names must be specific — e.g. `"deadman-prices"`, not `"deadman"`.
- Never rename a config key or config group without providing a migration. Renaming silently resets users' saved settings.
- If you add a `@ConfigItem` that toggles a feature involving a third-party server, it must:
  - Be **disabled by default** (opt-in)
  - Have a `warning` field set to: `"This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"`

## Plugin Setup & Packaging

- Rename everything from the template. Do not leave `com.example`, `EverykillTrackerPlugin`, `EverykillTrackerConfig`, or `example` as the config group. Rename the package path, class names, config group, `build.gradle` group, `settings.gradle` project name, and `runelite-plugin.properties`.
- Do not include a `META-INF/services/net.runelite.client.plugins.Plugin` file.
- Do not commit build artifacts — no `.class` files, `out/` directories, or `.tmp` directories.
- `build.gradle` must target Java 11** and match the structure of the example-plugin template.
- Retain a permissive license, such as BSD-2.

## Resources & Assets

- Optimize icon PNGs. Java loads images at full resolution in memory (`width × height × 4` bytes), so a seemingly small file can use significant memory.
- Ensure PNGs are actually PNGs — do not rename JPEGs or ICOs to `.png`.

## Cleanup

- Remove unused config classes, fields, and imports.
- Clean up subscriptions, listeners, and overlays in `shutDown()`.
- Do not mix code reformatting with feature changes in the same commit — it makes diffs unreadable for reviewers.

## Testing

You cannot verify plugin behavior yourself. Even if you have screen-capture or computer-use tools available, **do not use them to interact with RuneScape** — automating game input violates Jagex's third-party client guidelines and will get the user's account banned. Only the user can confirm a plugin works in-game.

After completing a task, do not declare it done. Instead:

1. Offer to launch RuneLite for the user by running `./gradlew run` from the plugin's root directory.
2. Instruct the user to follow the "Using Jagex Accounts" instructions found at https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts to login to the development client.
3. Tell the user *what to test* — the specific behavior you changed, the golden path, and any edge cases worth exercising.
4. Wait for the user to confirm the feature works in-game before considering the task complete. A clean JVM start is not a passing test.

---

# Plugin Rules & Restrictions

Features that are **forbidden or restricted** in RuneLite hub plugins.
Sourced from [Jagex's Third-Party Client Guidelines](https://secure.runescape.com/m=news/third-party-client-guidelines?oldschool=1) and RuneLite's [Rejected or Rolled-Back Features](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features).

**If your plugin does any of the things listed below, it will be rejected.**

## Forbidden Language Features

- All code must be Java 11 compatible
- No use of reflection
- No use of JNI or JNA
- No direct access to native memory access via Unsafe or LWJGL
- No executing external processes, including with Process or ProcessBuilder
- No downloading or dynamic loading of code, including classloading
- No runtime generation of code
- No use of Java (de)serialization

## Boss & Combat Restrictions

Applies to all bosses, Raids sub-bosses, Slayer bosses, Demi-bosses, and wave-based minigames (Fight Caves, Inferno, etc.):

- No next-attack prediction (timing or attack style)
- No projectile target/landing indicators
- No prayer switching indicators
- No attack counters
- No automatic indicators showing where to stand or not stand (manual tile marking is allowed)
- No additional visual or audio indicators of a boss mechanic, unless it is a manually triggered external helper
- No advance warning of future hazards (highlighting currently active hazards is OK)
- No "flinch" timing helpers
- No combat prayer recommendations
- No NPC focus identification (which player the NPC is targeting)
- No content simulation (e.g. boss fight simulators)

New high-end PvM boss plugins are not accepted as a blanket policy.

## PvP Restrictions

- No removing or deprioritising attack/cast options in PvP
- No opponent freeze duration indicators
- No PvP clan opponent identification
- No PvP loot drop previews
- No identifying an opponent's opponent
- No PvP target scouting information
- No player group summaries (attackable counts, prayer usage, etc.)
- No level-based PvP player indicators (highlighting attackable players or those within level range)
- No spell targeting simplification (removing menu options to make targeting easier)

## Menu Restrictions

- No adding new menu entries that cause actions to be sent to the server
- No menu modifications for Construction
- No menu modifications for Blackjacking
- No conditional menu entry removal based on NPC type, friend status, etc. (can be overpowered)

## Swing panels — the BoxLayout size trap

Every layout bug in this plugin so far has been one root cause, in four costumes.
It cost five rounds of "it needs to hug the left wall", three of "the rows are
squished", and one of "the text is cut off". Read this before adjusting a border.

**A `BoxLayout` child with no maximum size is stretchable.** The container hands
it leftover space along the layout axis, and centres it on the other one.
`setAlignmentX` positions a child; it does not stretch one.

That produces:

| Symptom | Real cause |
|---|---|
| Content floats away from the left edge | No max width — BoxLayout centred it |
| Rows squish when a sibling expands | No max height — the sibling took their space |
| Cards look loose and stretched on a short tab | No max height and no glue — they soaked up the slack |
| Wrapping text is clipped after one line | HTML label derived height from an unbounded width |

**The rules:**

1. **Set a maximum size on every child of a vertical `BoxLayout`.**
   `setMaximumSize(new Dimension(Short.MAX_VALUE, h))` for a full-width row.
2. **Measure AFTER adding children.** `getPreferredSize()` on an empty panel
   returns the border and nothing else. Measuring too early is a different wrong
   answer, not a safer one.
3. **Never hardcode a row height.** A literal `26` was correct the day it was
   written and stale the moment a 24px icon moved in, silently crushing every
   row by 12px. Measure the first child and use that.
4. **Put `Box.createVerticalGlue()` at the end of a short list**, so leftover
   space has somewhere to go that isn't your content. Glue alone is not enough —
   an unpinned child still competes with it.
5. **Wrapping `<html>` labels must be measured explicitly.** A JLabel derives its
   preferred *height* from its preferred *width*, and in a vertical BoxLayout
   nothing tells it how wide it will be, so it assumes one enormous line and
   reports one line of height. Use `BasicHTML.createHTMLView`, `setSize(width, 0)`,
   then `getPreferredSpan(Y_AXIS)`. `EverykillPanel.paragraph()` does this.

**When a layout is wrong, measure it before changing it.** Every one of these was
found by dumping actual numbers — a script that printed every `EmptyBorder` inset
(largest was 5px, so borders were never the problem), a harness that printed tab
label widths against cell widths, a probe that showed a label wanting 1107x16 when
it needed 191x112. None were found by nudging padding.

## Interface Restrictions

- No unhiding hidden interface components (special attack bar, minimap)
- No moving or resizing click zones for 3D components
- No moving or resizing click zones for combat options, inventory, equipment, or spellbook
- No resizing prayer book click zones
- No resizing spellbook components
- No removing inventory pane background or making it click-through
- No detached camera world interaction (interacting with the game world from a camera position that isn't the player's)

## Input Restrictions

- No injecting input events, including mouse and keyboard events
- No autotyping — plugins must not programmatically insert text into the chatbox input (includes pasting, shorthand expansion)
- No modifying outgoing chat messages after the user sends them

## Data & Privacy Restrictions

- No exposing player information over HTTP
- No crowdsourcing data about other players (locations, gear, names, etc.)
- No credential manager plugins that stores account credentials

## Content Restrictions

- No adult or overtly sexual content
- No plugins that use player-provided IDs for their entire functionality (causes moderation issues)
