> **Scratch working doc, not spec.** A one-off IntelliJ coaching walkthrough
> written by an assistant for a specific past session (references pasting in
> "my" files, the old `com.everykill` package, and `GistUploader` as a file to be
> created — since superseded). Not authoritative on current structure.

# Step 3.7 in full detail · Swapping in the plugin code

You've got the example plugin running through Gradle. Now we turn it into the
tracker.

**The approach:** we'll *rename* the three example files rather than deleting
them, then replace their contents. IntelliJ keeps the file name and the class
name in sync automatically when you rename, which removes the most common way
this goes wrong. Then we add two brand-new files.

Work through in order. Don't try to run anything until step F.

---

## What we're building toward

| Template file | Becomes | How |
| --- | --- | --- |
| `ExamplePlugin.java` | `EverykillTrackerPlugin.java` | rename |
| `ExampleConfig.java` | `EverykillTrackerConfig.java` | rename |
| `ExamplePluginTest.java` | `EverykillTrackerLauncher.java` | rename |
| — | `XpSession.java` | create new |
| — | `GistUploader.java` | create new |

Plus two small edits to config files at the end.

---

## A · Rename the package (main)

1. In the left sidebar, expand **src** → **main** → **java**
2. You'll see **com.example** — click the arrow to expand it if it isn't already
3. **Right-click** on `com.example`
4. In the menu, hover **Refactor**, then click **Rename...**
5. A dialog appears. If it asks *"Rename package or directory?"*, choose
   **Rename package**
6. The text box shows `example`. Change it to just: `everykill`

   > It shows only the last part, not the full `com.example`. Type `everykill`,
   > not `com.everykill`.

7. Click **Refactor**

The sidebar should now show **com.everykill**.

---

## B · Rename the package (test)

Same again, in the other tree:

1. Expand **src** → **test** → **java**
2. Right-click `com.example` → **Refactor** → **Rename...**
3. Change `example` to `everykill`
4. Click **Refactor**

---

## C · Rename the three files

### C1 · ExamplePlugin

1. Under `src/main/java/com/everykill`, right-click **ExamplePlugin**
2. **Refactor** → **Rename...**
3. Replace the text with: `EverykillTrackerPlugin`
4. Click **Refactor**

If a panel appears at the bottom saying *"Refactoring Preview"* or listing
usages, click **Do Refactor**.

### C2 · ExampleConfig

Same steps, rename to: `EverykillTrackerConfig`

### C3 · ExamplePluginTest

This one lives under `src/test/java/com/everykill`.

Rename it to: `EverykillTrackerLauncher`

**You should now have:**

```
src/main/java/com/everykill/
├── EverykillTrackerPlugin.java
└── EverykillTrackerConfig.java

src/test/java/com/everykill/
└── EverykillTrackerLauncher.java
```

The files still contain example code. That's fine, we replace it next.

---

## D · Replace the contents of each file

For each of the three, the process is identical:

1. **Double-click** the file in the sidebar to open it
2. Click anywhere in the code editor
3. Press **Ctrl+A** (Cmd+A on Mac) to select everything
4. Press **Delete**
5. Open my corresponding file, select all, copy
6. Click back in the empty IntelliJ editor and press **Ctrl+V**

Do this for:

| IntelliJ file | Paste from my file |
| --- | --- |
| `EverykillTrackerPlugin.java` | `EverykillTrackerPlugin.java` |
| `EverykillTrackerConfig.java` | `EverykillTrackerConfig.java` |
| `EverykillTrackerLauncher.java` | `EverykillTrackerLauncher.java` |

**The first line of each pasted file should read `package com.everykill;`** — if it
says `package com.example;` then step A or B didn't take, and you should go back
and redo it.

Red underlines will appear. Ignore them entirely until step F.

---

## E · Create the two new files

### E1 · XpSession

1. **Right-click** the `com.everykill` package under **src/main/java**
2. **New** → **Java Class**
3. A small box appears. Type exactly: `XpSession`
4. Press **Enter**
5. IntelliJ creates the file with a couple of skeleton lines
6. **Ctrl+A**, **Delete**, then paste my `XpSession.java` contents

### E2 · GistUploader

Exactly the same, in the same package, named: `GistUploader`

Then paste my `GistUploader.java`.

**Both go in `src/main/java`, not the test folder.**

Your tree should now be:

```
src/main/java/com/everykill/
├── GistUploader.java
├── XpSession.java
├── EverykillTrackerConfig.java
└── EverykillTrackerPlugin.java

src/test/java/com/everykill/
└── EverykillTrackerLauncher.java
```

---

## F · Edit build.gradle

This is the step that decides whether the client launches at all.

1. Open **build.gradle** (it's at the very bottom of the file tree, outside the
   `src` folders)
2. Near the top, find this line:

```groovy
def pluginMainClass = 'com.example.ExamplePluginTest'
```

3. Change it to:

```groovy
def pluginMainClass = 'com.everykill.EverykillTrackerLauncher'
```

4. A few lines below, find:

```groovy
group = 'com.example'
```

5. Change it to:

```groovy
group = 'com.everykill'
```

6. A yellow bar may appear at the top right saying Gradle needs reloading —
   click the **elephant / refresh** icon on it

> **Launcher, not Plugin.** `pluginMainClass` points at the class with the
> `main()` method. Getting this wrong gives a "main class not found" error that
> looks unrelated to what you changed.

---

## G · Edit runelite-plugin.properties

1. Open **runelite-plugin.properties**, same level as build.gradle
2. Select all, delete, and paste this in:

```
displayName=Everykill
author=Everykill
description=Tracks XP rates, location, gear and loot to a JSON snapshot
tags=xp,rate,slayer,loot,tracker
version=1.0.0
plugins=com.everykill.EverykillTrackerPlugin
build=standard
```

> Note this one wants **Plugin**, the opposite of build.gradle. That's genuinely
> inconsistent in RuneLite's design, not a mistake in these instructions.

---

## H · Run it

Same way that worked before — Gradle panel → **Tasks** → **other** → double-click
**run**.

### If it compiles and launches

The client opens. Once you're logged in, click the wrench icon and search for
**Everykill**. Tick it on.

### If it fails to compile

Expected, and this is where I need to do some work. Look at the **Build** panel
at the bottom of IntelliJ.

**What I need from you:** the lines that begin with `error:`. Something like:

```
C:\...\EverykillTrackerPlugin.java:34: error: cannot find symbol
import net.runelite.api.InventoryID;
                       ^
  symbol:   class InventoryID
```

Copy that text and paste it to me. Three or four such lines is plenty — they
usually share one root cause. I'll send back corrected files.

Don't try to fix them yourself unless you want to; my code met the real RuneLite
API for the first time just now, and a few mismatches are entirely expected.

---

## Quick sanity checklist

Before running, confirm:

- [ ] Both `com.example` packages renamed to `com.everykill`
- [ ] Three files renamed, two files created — five total
- [ ] Every file's first line reads `package com.everykill;`
- [ ] `GistUploader` and `XpSession` are in **main**, not test
- [ ] `pluginMainClass` = `com.everykill.EverykillTrackerLauncher`
- [ ] `plugins=` in properties = `com.everykill.EverykillTrackerPlugin`
- [ ] `group` = `com.everykill`
