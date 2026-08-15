# Logging into your Jagex account from the dev client

Ignore the Python script I described earlier — that method is outdated. RuneLite
added a proper mechanism for this and it's four clicks.

**What it does:** you tell the official RuneLite launcher to write your session
credentials to a file once. Your dev client then reads that file and logs in
automatically.

---

## Before you start · read this bit

That credentials file lets anything holding it log into your account **without
your password**. RuneLite's own documentation says, in bold, not to share it.

So:

- Don't paste its contents anywhere, including to me
- Don't commit it to your GitHub repository
- Delete it when you're done developing (step 6)
- If it's ever exposed, use **End sessions** under account settings on
  runescape.com to invalidate it immediately

It lives at `C:\Users\<you>\.runelite\credentials.properties`.

---

## Step 1 · Check your launcher version

You need RuneLite launcher **2.6.3 or newer**. Anything installed in the last
couple of years is fine, but if the next step's window doesn't exist, that's why.

---

## Step 2 · Open the launcher configuration window

This is a separate window from RuneLite itself.

**On Windows:**

1. Press the **Windows key**
2. Type: `RuneLite (configure)`
3. Click that entry when it appears in the results

It's a small settings window with checkboxes and two text boxes.

**On macOS**, run this in Terminal instead:

```
/Applications/RuneLite.app/Contents/MacOS/RuneLite --configure
```

---

## Step 3 · Add the client argument

1. Find the box labelled **Client arguments** — it's a multi-line text box,
   one argument per line
2. Click into it and type exactly:

```
--insecure-write-credentials
```

3. If there's already something in that box, put this on its own new line
4. Click **Save**

The window closes.

---

## Step 4 · Launch RuneLite normally, once

1. Open the **Jagex Launcher** as you always do
2. Launch **RuneLite** from it
3. Wait for it to fully load to the login screen or into the game
4. Close it

That single launch wrote `credentials.properties` into your `.runelite` folder.

**Optional check:** press Windows key, type `%userprofile%\.runelite`, press
Enter. You should see `credentials.properties` with a timestamp from just now.

---

## Step 5 · Run your dev client again

Back in IntelliJ, run the Gradle **run** task exactly as before.

This time the client picks up the saved credentials and logs you straight in —
no username or password prompt at all.

If you still see "Incorrect username or password", see the troubleshooting
section below.

---

## Step 6 · When you're finished developing

To put things back to normal:

1. Delete `C:\Users\<you>\.runelite\credentials.properties`
2. Reopen **RuneLite (configure)** and remove the
   `--insecure-write-credentials` line, then Save

Worth doing whenever you take a break from this, so the file isn't sitting around
indefinitely. It costs you thirty seconds to redo.

---

## If it doesn't work

**Still asking for a password.** The credentials file wasn't written. Confirm the
argument saved correctly in the configure window — a typo means it's silently
ignored — then launch through the Jagex Launcher again.

**"RuneLite (configure)" isn't in the Start menu.** Your launcher predates 2.6.3.
Reinstall from runelite.net and try again.

**Logged in as the wrong character.** The credentials are tied to whichever
character you last launched from the Jagex Launcher. Launch Everykill specifically,
then retry.

**Worked yesterday, not today.** Sessions expire. Repeat steps 4 and 5.

---

## Once you're in

Play for ten minutes or so, then check this file exists:

```
C:\Users\<you>\.runelite\everykill-plugin\snapshot.json
```

Open it in Notepad. You should see your skills with real XP values, a `session`
block with an `xpPerHour` figure, your current task, and any drops you picked up.

**Paste me the contents.** That's the first genuine XP-rate data we'll have had —
everything I've quoted so far has come from guides written for stronger accounts.
