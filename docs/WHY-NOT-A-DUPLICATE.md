# Why Everykill isn't a duplicate

For the Hub PR, and for anyone who asks. Keep it short — reviewers read a lot of these.

## The one-liner

**The official hiscores rank about 90 bosses. Everykill ranks everything else.**

There are more than 1,300 monsters in the game. Nobody knows who's killed the most Rockslugs
because nothing counts them.

## What the plugins we're compared to actually do

| Plugin | What it does | Where we differ |
|---|---|---|
| **Collection Log Luck** | Your luck on collection log slots, locally | Log items only. No kill counts, no ranking, no server |
| **Dry Rate Tracker** (26k) | Dry streaks on raid drops | Raids. We do every monster — and don't touch raid splits, which they do properly |
| **Bossing Info** | Reference data and PBs for bosses | The ~90 that already have hiscores. Ours is the long tail |
| **Loot Lookup** (153k) | Drop tables in-client | They own that surface. We don't show drop tables |
| **Dink** (66k) | Discord webhooks | We send it events instead of rebuilding it |

## The actual difference

Those all answer *"how am I doing?"* from your own data.

Everykill answers **"how am I doing compared to everyone else?"** — which needs a
shared denominator, so it needs a server, so it's a different kind of plugin. That's
also why the honesty work matters: a rank built on kills we weren't sure about is
worse than no rank.

Two things that fall out of that and nobody else has:

**Ironman loot rules are applied properly.** Outside damage voids an ironman's drop
entirely — measured, 9 clean kills gave loot, 8 contested gave none. A contested
ironman kill is recorded as `unknown`, not as a dry kill. Counting it would quietly
inflate every ironman's dry streak on the board.

**Kills we couldn't attribute are excluded, not counted as zero.** `unknown ≠ empty`.
Most trackers can't afford the distinction because nothing downstream depends on it.
Ours does.

## What we deliberately didn't build

Dink for Discord. Loot Lookup for drop tables. Raid Data Tracker for splits. GP/hr is
covered three times over in-client.

We surveyed the Hub before starting and cut everything already solved. What's left is
the part nobody's built: **a ranked, honest denominator for every monster in the
game.**
