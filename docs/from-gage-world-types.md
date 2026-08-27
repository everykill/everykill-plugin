# The API needs to know what world a kill happened on

Gage → Tyler. This one needs a change on your side to do anything, so it's the
first thing here rather than buried at the bottom.

## What Delk asked

> We know what worlds the kills are coming from? I dont want the test worlds,
> deadman, or any other special world leaking into the leaderboards

We didn't. Nothing in the plugin or the API captured it — `KillDetector` reads
`npc.getWorldLocation()`, which is map coordinates, not the world. So **a
Deadman kill counted exactly like a main-game one**, and so would a beta world
where Jagex hands you max gear.

## What I need from the plugin

One field on the kill payload:

```java
// net.runelite.api.Client#getWorldType() -> EnumSet<WorldType>
List<String> worldTypes = client.getWorldType().stream()
        .map(t -> t.name().toLowerCase(Locale.ROOT))
        .collect(Collectors.toList());
```

```json
"worldTypes": ["members", "skill_total"]
```

Lowercase RuneLite enum constant names. An empty array is fine and means a
plain free world. The server lowercases and sorts before storing, so ordering
and casing don't matter on the wire — send `DEADMAN` if that's easier, it's
matched case-insensitively.

**Read it at kill time, not at login.** A world hop mid-session changes the
answer, and a cached value would tag the new world's kills with the old world's
types.

## What the server does with it

Excluded outright — the kill is rejected at ingest with
`"world type does not count toward the boards"`:

```
deadman  seasonal  tournament_world  last_man_standing  beta_world
nosave_mode  eoc_only  legacy_only  quest_speedrunning  fresh_start_world
```

`seasonal` is Leagues. That's the same call as dropping the two Echo bosses
from the monster index, so the two decisions agree.

**Still counts:** `members`, `skill_total`, **every pvp world**, and no flags at
all.

I had pvp, high risk, bounty and pvp arena excluded at first. Delk overruled it
and he's right: those are your account, your gear, your levels, and the monster
died the same way it dies anywhere else. Someone hunting you while you do it
makes the kill harder, not less real — arguably it's the one worth bragging
about. The risk is to the player, not to the number.

LMS stays out, and it's the odd one in that group: it hands you a preset
inventory inside a lobby minigame, so nothing killed there is your account
killing it with your gear. Same reason tournament and beta worlds are out.

A members world is where most of the game happens, and a 2000-total world is an
ordinary world with a login requirement. Neither changes what a kill means.

**Unknown type names do NOT exclude.** Jagex adds world types faster than my
deploys; a name I've never seen is more likely a new ordinary flag than a new
game mode, and silently dropping real kills because the server is a version
behind is the worse failure.

Rejected at the door rather than stored and filtered later. A stored Deadman
kill is a row every future query has to remember to exclude, and one of them
would eventually forget.

## The bit that affects your timeline

**The field is optional right now.** A client that doesn't send it is still
accepted, and the kill is stored with `world_types` NULL — meaning "we were
never told", as distinct from `''` which means "a plain free world". Rejecting
those today would throw away every kill from anyone who hasn't updated.

**That tolerance should end when the plugin ships.** Once a released client
always sends it, a kill without one is a client I don't recognise, and I'd
rather refuse it than guess. Tell me when you've got the field in a build and
I'll tighten it — I'm not going to flip that switch under you.

Until then there's a real hole: anyone on a Deadman world running a plugin
build without this field still lands on the boards.

## Verified live

```
live-dm-1  -> rejected  world type does not count toward the boards
live-sea-1 -> rejected  world type does not count toward the boards
live-ok-1  -> accepted
```

13 tests on my side, including one that walks the whole excluded list so adding
a type without wiring it up fails, one that checks `DEADMAN` in caps can't walk
through a case-sensitive comparison, and one pinning the four pvp types as
accepted so they don't get swept back into a "special worlds" bucket later.

## If you disagree with the list

`fresh_start_world` is the one left worth arguing about — it's a real
progression track, just a separate one, and you could reasonably say those
kills should count on their own board rather than nowhere. Say the word and
I'll move it.
