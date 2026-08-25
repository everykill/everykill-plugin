# Plugin Hub submission checklist

Everything required to pass review, plus the project-specific risks that would get us rejected.

Review focuses on **two things only**: security, and Jagex game-rule compliance. Reviewers explicitly do **not** check that the plugin works, performs well, or displays accurate information — correctness is entirely our problem. Since April 2026 an AI agent reviews alongside human maintainers.

**Do not submit until every box here is true.**

---

## 1. Mechanical requirements

- [ ] **Public GitHub repository**
- [x] **`LICENSE` at repo root** — BSD 2-Clause, `Copyright (c) 2026, Delkyy`
- [x] **Java 11** language level in `build.gradle` — `options.release.set(11)`, verified 2026-08-25
- [x] **`runeLiteVersion = 'latest.release'`** — verified 2026-08-25
- [x] **`runelite-plugin.properties`** complete: `displayName`, `author`, `description`, `tags`, `plugins`, `version`, `build`
- [x] **`build=standard`** — replaces our `build.gradle`/`settings.gradle` at submission and qualifies for **expedited review**. Only possible with zero custom dependencies
- [x] **`icon.png`** at repo root — 48x48 RGBA PNG, 578 bytes, magic-byte checked 2026-08-25;, max **48×72 px**, a genuine PNG (not a renamed JPEG or ICO), optimised — Java loads images at full resolution in memory
- [x] **README** describing what the plugin does
- [x] **No `META-INF/services/net.runelite.client.plugins.Plugin` file** — verified absent 2026-08-25
- [x] **No build artifacts committed** — `git ls-files` finds zero `.class`/`out/` 2026-08-25 — no `.class` files, no `out/`, no `.tmp` directories

---

## 2. Template cleanup

The template ships with placeholder names. Every one must be gone.

- [x] Package path renamed — no `com.example`
- [x] Class names renamed
- [x] **Config group renamed** — `everykill`, verified 2026-08-25 — must be specific (`everykill`, not `example` or `everykill`)
- [x] `build.gradle` group renamed
- [x] `settings.gradle` project name renamed
- [x] `runelite-plugin.properties` updated

⚠️ **The config group rename must happen before any real user data exists.** Renaming a config group without a migration silently resets every user's saved settings.

---

## 3. Forbidden language features

Automated scanning checks for these. Any one is an instant rejection.

- [x] No **reflection** — only `java.lang.reflect.Type` for Gson `TypeToken`, which is core's own pattern; no `invoke`/`setAccessible`/`forName`. Verified 2026-08-25
- [x] No **JNI or JNA** — verified 2026-08-25
- [x] No native memory access via **Unsafe** or **LWJGL** — verified 2026-08-25
- [x] No **`Process` or `ProcessBuilder`** — verified 2026-08-25
- [ ] No **downloading or dynamic loading of code**, including classloading
- [ ] No **runtime code generation**
- [ ] No **Java serialization**
- [ ] No **`Thread.sleep`**

---

## 4. Dependencies

- [ ] **Zero dependencies added to `build.gradle`**

Anything not already transitive to `runelite-client` requires cryptographic hash verification and manual maintainer review, which the README says adds significantly to review time. It also forces `build=gradle` and loses the expedited path.

- [ ] HTTP via **`@Inject OkHttpClient`** — never constructed, never added to gradle
- [ ] JSON via **`@Inject Gson`** — `.newBuilder()` if customisation is needed

---

## 5. Threading and lifecycle

- [ ] No blocking network or disk IO on the client thread
- [ ] All OkHttp calls via **`enqueue()`**; `clientThread.invoke()` to call back into `client`
- [ ] No blocking in `startUp()` or `shutDown()` — `shutdownNow()`, never `awaitTermination()`
- [ ] Scheduled tasks (`ScheduledFuture`) explicitly cancelled on shutdown
- [ ] Subscriptions, listeners and overlays cleaned up in `shutDown()`
- [ ] Scene not scanned every tick — event-driven collections only
- [ ] Overlay computation minimal (runs every frame)

---

## 6. Third-party upload — the rule that governs this project

- [ ] Upload behind an explicit **`@ConfigItem` toggle**
- [ ] **Disabled by default**
- [ ] `warning` field set to **exactly**:
      `"This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"`
- [ ] Config **description lists every field sent** — the requirement is a warning explaining *what data is being sent*
- [ ] **Plugin fully useful with upload off** — local tracking must stand alone
- [ ] **One hardcoded API domain.** No user-supplied URLs, ever

⚠️ **`GistUploader` must be removed before submission.** Arbitrary user-supplied upload URLs make compliance impossible to verify, and the review policy states plainly that if it is difficult to establish a plugin isn't against the rules, it will not be merged.

- [ ] On-disk buffering only in `.runelite/everykill/` via `RuneLite.RUNELITE_DIR`
- [ ] Resources loaded via **`getResourceAsStream`**, never `getResource` — ships as a jar, never unpacked

---

## 7. Jagex rule compliance

Everything prohibited is a **combat-assist** feature. Passive recording is clear, but the line must not be crossed by accident.

- [ ] **Nothing displayed mid-fight that could inform the next action**
- [ ] No attack prediction, projectile indicators, prayer prompts, freeze timers, attack counters, or "where to stand" indicators
- [ ] No menu entries that send actions to the server
- [ ] No interface or click-zone modification
- [ ] No input injection, no autotyping, no modifying outgoing chat
- [ ] **No crowdsourcing data about other players** — locations, gear, names. `scene_has_other_players` is a **boolean only**; never store who dealt foreign damage, what they wore, or where they stood
- [ ] No exposing player information over HTTP beyond the consenting user's own data

---

## 8. Project-specific cleanup

- [x] `GistUploader` removed from the public branch — verified absent 2026-08-24
- [x] Legacy pre-rewrite code removed — `writeSnapshot`, `XpSession`, `killCounts` all absent from `src/main` 2026-08-24
- [x] **Two parallel kill counters reconciled** — no `onNpcLootReceived` path remains; the damage-attributed path is the only one
- [x] **`Files.createDirectories()` in `startUp()` and `writeSnapshot()`'s disk write in `shutDown()` are blocking disk IO on the client thread** — both violate `docs/CONVENTIONS.md`'s threading rules directly (found 2026-08-14 review). `writeSnapshot()`'s `Files.write()` runs inside `clientThread.invoke()`, i.e. *on* the client thread, not off it. Must be fixed as part of removing/replacing this legacy code, not carried into whatever replaces it.
- [x] **`new GsonBuilder().setPrettyPrinting().create()` in `EverykillTrackerPlugin` violates the `@Inject Gson` rule** (found 2026-08-14 review) — construct via `@Inject Gson` + `.newBuilder()` instead, per `docs/CONVENTIONS.md`'s HTTP & JSON section, when this code is replaced.
- [ ] Unused config classes, fields and imports removed
- [ ] `net.runelite.api.gameval` constants used instead of magic numbers
- [ ] No reformatting mixed into feature commits — it makes diffs unreadable for reviewers
- [x] **`onAnimationChanged`'s temporary transform-death discovery logging removed** — only once a real death-animation id is identified, confirmed (negative-control criterion, edge case A) and wired into detection logic. Currently scoped to `TransformDeathNpcs` only so it's safe to ship mid-development, but it must not survive into a submission if the animation-id work is still unresolved by then; resolve or explicitly re-confirm scope before submitting either way.

---

## 9. Ecosystem norms

Not hard rules, but reviewers hold them.

- [ ] Able to state clearly **why this is new functionality**, not a duplicate of Collection Log Luck, Bossing Info, or Dry Rate Tracker. The hub explicitly prefers contributing to existing plugins over creating new ones, to avoid fragmentation
- [ ] Repository under the **`everykill` GitHub org**, not a personal account — the PR references the repo URL permanently

---

## 10. Submission

1. Fork `runelite/plugin-hub`
2. Create a branch
3. Add one file in `plugin-hub/plugins` with `repository=` and `commit=`
4. Open a PR
5. Check CI: `.github/workflows/build.yml / build (pull_request)`
6. If **RuneLite Plugin Hub Checks** says *Changes are needed*, read the requested changes
7. Push further commits to the **same PR** and update the `commit=` hash — maintainers prefer one PR over several
8. Wait

Updates are the same process with a new commit hash, re-reviewed each time.

---

## Strategy note — submit early, minimal

The **first** submission is the expensive one; it gets the full review. Subsequent updates are re-reviewed but the AI agent auto-approves most simple ones.

There is a strong case for submitting a **local-only version first** — kill tracking, drop logging, side panel, **no upload at all**. That version:

- Has no third-party server, so the highest-risk review surface doesn't exist yet
- Passes review on a much simpler footprint
- Starts accumulating installs and user feedback while the backend is still being built
- Teaches us the review process before there's anything complicated to defend

Upload then arrives as an update to an already-approved plugin, with a config toggle, rather than as part of a first submission that has to justify everything at once.

**Trade-off:** shipping without upload means no data flows in early, so the dataset starts later. Worth weighing, but the risk reduction is real.
