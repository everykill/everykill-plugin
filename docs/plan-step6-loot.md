# Step 6 plan — loot capture

**Status:** plan only, nothing implemented. Written 2026-08-24 after reading
`LootManager` in `rlsrc` (RuneLite 1.12.36) and measuring a live session.

`spec-drop-attribution.md` predates the discovery of `ServerNpcLoot` and describes tile
coincidence as *the* core mechanic. That spec is not wrong — it accurately describes what
`LootManager` does — but it is no longer the best available route. This plan supersedes
its algorithm section. **The spec's grading rules and the denominator rule still stand
and are not revisited here.**

---

## What changed

The server tells the client what dropped, from which monster.

`ScriptID.LOOTTRACKER_ADD_LOOT` (**7192**) fires with explicit arguments:

```java
int npcId   = args[1];
int eventId = args[2];
int itemId  = args[3];
int qty     = args[4];
```

Items accumulate under an `eventId`; a change of id flushes them, and `onGameTick` flushes
whatever is pending. The result is posted as `ServerNpcLoot(NPCComposition, Collection<ItemStack>)`.

**This is not an inference.** Tile coincidence asks *"what appeared near a corpse"*;
this says *"npc 2856 dropped item 526 ×1"*. Verified present in the jars we compile
against, and observed live: **23 distinct npc ids** named by the server in one session.

`NpcLootReceived` — the tile-coincidence event — has **zero subscribers** anywhere in
core's own plugins. Everything moved to `ServerNpcLoot`.

---

## The measured fact this plan is built on

From the ironman rat session, 2026-08-24 (see FINDINGS):

| | server loot event |
|---|---|
| 9 clean kills | **9** |
| 8 contested kills | **0** |

**The server declines to fire for a kill the player was not eligible for.** For an
ironman that is the eligibility rule itself, delivered by the server, no reconstruction
needed.

---

## Architecture

Follow the pattern the codebase already uses, because it has now been verified three
times (`XpAttributor`, `SpecialCounterPlugin`, `LootManager` itself):

**accumulate on events, decide on `GameTick`.**

1. Subscribe to `ServerNpcLoot` in a new `LootDetector`, mirroring `KillDetector`.
2. Buffer each event as `(npcId, items, tick)`.
3. On `GameTick`, join buffered loot against kills that resolved this tick.
4. Attach to the `KillRecord`; emit.

**The join is by `npcId`, and that is the weak point.** `ServerNpcLoot` carries an
`NPCComposition`, not the NPC instance we tracked, so two simultaneous kills of the same
`npc_id` cannot be told apart. Same-tick, same-id collisions must produce `unknown` per
the spec's denominator rule, never a guess.

---

## The "hold the kill" change lands here

Decided earlier ("try 1") and deliberately deferred: `resolve()` currently emits
immediately, so a kill is gone before its loot arrives. It must park the record and emit
after loot resolves on the tick boundary.

This is why it was deferred — it changes `tick(int)` to `tick(int, Consumer<KillRecord>)`
and touches all 29 `KillStateMachineTest` cases, for **zero behaviour change** until loot
code exists. It now has something to hold for.

It needs no invented constant: the hold is *"finish the tick"*, one tick, because that is
when `LootManager` flushes. Delayed monsters stay explicit exceptions.

---

## What `ServerNpcLoot` does NOT solve

1. **A lootless kill and a voided kill look identical.** The script only fires when there
   is something to report, so "no event" is ambiguous between *dropped nothing*,
   *ineligible*, and *we missed it*. This is the same structural hole already recorded in
   GAME-MECHANICS for `LootManager` (*"posts no loot event when the ground is empty"*).
2. **Drops that never touch the ground** — pets, interface-delivered rewards. Unverified
   whether the script covers these.
3. **Which monsters never fire it.** Unknown. The tile-coincidence path still exists in
   core for a reason and that reason has not been established.

### `always_drops.tsv` resolves hole 1, and this is what it was pulled for

4,339 rows of guaranteed drops, gitignored, no consumer since Friday.

If a monster **always** drops bones and we recorded a kill with **no** loot event, the
kill was not dry — it was voided or missed. That is a falsifiable cross-check on our own
data, in both directions, and it does not exist in any other tracker.

Giant rats always drop bones, which is exactly why the 9/9 vs 0/8 split above is clean
evidence rather than a coincidence of drop luck.

---

## Ironman gating belongs in this step

`grep` confirms `src/main` has **no notion of account type**. The rules measured
yesterday are ironman-only and are wrong for a main — a main who deals 90% of a contested
kill *wins* the drop.

Gate on `client.getVarbitValue(VarbitID.IRONMAN)` (**1777**): `0` main, `1` iron,
`2` ultimate, `3` hardcore.

**Group Ironman is not in that varbit** — core reads
`client.getClanSettings(ClanID.GROUP_IRONMAN)` instead. A groupmate is not an outsider,
so `othersAttacks > 0` must not void a GIM's kill. Core already models the distinction:
`GroundItem.getOwnership()` → `OWNERSHIP_SELF` / `OWNERSHIP_GROUP` / `OWNERSHIP_OTHER`.
**Read that before writing the GIM branch.**

---

## Order of work

1. `LootDetector` subscribing to `ServerNpcLoot`, buffering by tick. No attribution yet.
2. Log-only verification in a client: does every kill get its loot, and does the join by
   `npc_id` hold up in a crowd?
3. The hold-the-kill change, with the 29 tests updated.
4. Attribution + loot grading per the spec's existing table.
5. Account-type gate.
6. `always_drops` cross-check — **NOT here.** `spec-reference-data.md:45` keeps the
   reference table server-side and off the client entirely. The check runs at ingest,
   where the table already lives; the client just uploads `npc_id`, the drops and the
   loot grade. See the FINDINGS entry dated 2026-08-24 for the four reasons.
7. Tile coincidence as a **fallback** for monsters the server does not report — only once
   step 2 has shown which those are.

**Step 2 gates everything after it.** Do not build attribution on an assumption about how
often the server reports; measure it first, the way the settle window was measured.

---

## Unverified, do not build on these

- Which monsters never fire `LOOTTRACKER_ADD_LOOT`.
- Whether it fires inside instances.
- Whether pets and interface-delivered rewards appear in it.
- Whether a same-tick, same-`npc_id` double kill produces one event or two.
- Whether the script fires for a main who *lost* a contested drop (it should not, but that
  is inference — only the ironman case was measured).
