# Hub submission

Everything needed to open the PR. Manifest first, then the body to paste.

## 1. The manifest

In your `plugin-hub` fork, create a file called **`plugins/everykill`** (no
extension):

```
repository=https://github.com/everykill/everykill-plugin.git
commit=a7a99227a348934f20338906121b3b28854e6982
```

Same shape as `plugins/thrall-check`. The `authors=` line is optional and only needed
when the GitHub account doesn't match the display name.

**Re-check the commit hash before opening the PR.** It has to be the exact commit you
want built, and anything pushed after this is written makes it stale:

```
git -C ~/IdeaProjects/zelnork-tracker rev-parse origin/everykill-merge
```

## 2. PR title

```
Add Everykill
```

## 3. PR body

Everything between the lines. Short on purpose — reviewers read a lot of these, and
the detail lives in the repo.

---

Kill counts for every monster in the game, not just the ~90 on the hiscores.

There are 1,757 monsters in OSRS. The official hiscores rank about 90 of them, so
nobody knows who's killed the most Rockslugs — nothing counts them. This plugin
counts them, locally, and grades every kill by how well it can be evidenced.

**Why it isn't a duplicate:** Collection Log Luck does log slots, Dry Rate Tracker
does raid dryness, Bossing Info covers the bosses that already have hiscores. Those
answer *"how am I doing"* from your own data. This answers *"how am I doing compared
to everyone else"*, which needs a shared denominator. Loot Lookup owns drop tables and
Dink owns Discord webhooks — neither is rebuilt here, and milestones are handed to
Dink via `PluginMessage` rather than a second webhook implementation.

**Upload is opt-in and off by default.** With it off the plugin is fully useful and
makes no network calls. With it on, it sends kill records to everykill.com. Your
RuneScape name is never sent unless you separately turn on name publishing, which is
its own toggle and also defaults off. There's an in-plugin export and delete for
anything already uploaded.

Notes for review:

- Zero dependencies. `build=standard`, Java 11, `@Inject` OkHttp and Gson, all calls
  on `enqueue()`
- No reflection, no classloading, no serialization, no `Thread.sleep`
- Nothing subscribes to NPC animations, projectiles or incoming hitsplats — counters
  only move after something has already died, and there's no per-boss logic
- The overlay is off by default and shows kill counts only
- Kills from Deadman, Leagues, beta and tournament worlds are excluded

BSD 2-Clause. 186 tests.

---

## Size, for context

Measured 2026-08-27 by cloning each plugin from the hub manifest and counting `.java`
lines in `src/main` (tests excluded).

| plugin | files | lines |
|---|---:|---:|
| Quest Helper | 712 | 206,838 |
| 117 HD | 160 | 43,303 |
| Dink | 121 | 15,624 |
| Inventory Setups | 59 | 14,595 |
| **Everykill** | **30** | **9,028** |
| Collection Log | 19 | 3,851 |
| Bank Memory | 37 | 2,717 |

Middle of the pack — bigger than a single-purpose tracker, a fraction of the plugins
people think of as large. The jar is 142 KB with no dependencies.

Worth knowing for review: line count isn't what makes a submission slow. Zero
dependencies and `build=standard` qualify for expedited review, and the reviewable
surface here is small — the network layer is five classes, and everything else is
local bookkeeping.

## 4. Opening it

1. Sync your `plugin-hub` fork with upstream `runelite/plugin-hub` first — a stale
   fork is the most common reason a PR shows unrelated diffs.
2. Branch, add the file, commit as `Add Everykill`.
3. PR against `runelite/plugin-hub` **master**.

## 5. What happens next

A reviewer reads the source, and CI builds the plugin from the exact commit pinned in
the manifest. If the build fails, fix it and push a new commit hash to the PR — the
manifest is just a pointer.

Expect questions about the upload. The answers are in `docs/WHY-NOT-A-DUPLICATE.md`
and the privacy section of the README, and everything on
`docs/SUBMISSION-CHECKLIST.md` is ticked with a date.
