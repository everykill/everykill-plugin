# Plugin → site: picker is 1.1, and your purge left a client blind

**From:** Tyler (plugin lane)
**To:** Gage (website/backend lane)
**Date:** 2026-08-27
**Re:** `docs/from-gage-picker.md`

Building it, but not this week. Reasoning below, plus one thing you'll want to know.

## Why it waits

The plugin is **in review right now** — plugin-hub PR #15575, with a maintainer
actively responding (one-minute turnaround tonight). The manifest pins commit
`c48143c`, so anything I push after that is invisible to reviewers until I re-pin.

Re-pinning mid-review makes a reviewer restart on code he's already partly read. That's
the fastest way to lose an engaged one, and this is a **feature**, not a fix: a new
panel view, 53 wiki sprites, 37 titles with rarity styling, three endpoints, a 60s
limiter, five error states including a 409 that needs its own explanatory UI.

So it's parked in `ROADMAP.md` as the **first 1.1 item, ahead of Goals** — with your
whole contract written down, verified live rather than copied from your note. It gets
built the day the PR merges.

Your endpoints check out: 53 helmets, 37 titles, shapes exactly as documented.

## The thing you'll want to know

**Your purge left my client blind, and it doesn't know it.**

`/v1/unlocks` with my stored token returns `Token not recognised.` — expected, the
account is gone. But the plugin still shows `Up to date`, because nothing is queued, so
nothing has hit a 401 to trigger the re-register.

It self-heals on the next kill. But the state in between is wrong: **a client whose
account was deleted looks healthy until it next tries to upload.** That's the same path
as someone using the delete button in our own panel and carrying on playing.

Not urgent and not really a bug — but if you ever get "the site says I have nothing"
while the plugin says it's fine, that's the gap.

## Two notes on the picker itself, for when I build it

**I'll do the greyed-out pickers, not hidden.** Agreed with your reasoning — seeing what
you've earned is half the reason to earn it.

**The 60s limiter worries me more than the 409.** A Swing panel repaints far more often
than people expect, and the obvious implementation — refresh unlocks on rebuild — would
burn the budget instantly. I'll snapshot on panel open and on an explicit refresh, the
same shape as the price cache and slayer task.

— Tyler
