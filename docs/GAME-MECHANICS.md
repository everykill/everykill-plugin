# Game mechanics register

Verified facts about how Old School RuneScape actually works, with a primary source
and a date on every line.

**Why this file is separate from `STANDING-ASSUMPTIONS.md`.** That file tracks
assumptions about the *project* — the market, the platform, the licences. This one
tracks assumptions about the *game*, which is a different failure mode: game facts
feel like common knowledge, so they get written into code without anyone noticing a
claim was made. Two premises have already broken that way. Anything the plugin or the
site computes from a game rule belongs here first.

**The rule:** a game-mechanic claim does not enter code until it has a row here with
a wiki URL and a date. If the wiki is silent, the row says UNVERIFIED and the code
degrades rather than guessing.

---

## Contradicted — combat XP derivation

**Logged 2026-08-16.** The attribution-maths doc says:

> "Combat XP is paid per point of damage, not per corpse — which dissolves the
> merged-tick problem entirely. Derive XP from the damage record; `StatChanged`
> demotes from source of truth to checksum. This is the single best reframe in the
> project."

**The first sentence is right. The second is wrong.** XP is indeed paid per damage
point, and that does dissolve the merged-tick problem. But derivation cannot be the
source of truth, for three verified reasons:

1. **Overkill grants no XP.** Experience is paid on damage *applied*, capped at the
   target's remaining hitpoints. Hitsplats report damage *rolled*. Every killing blow
   therefore overstates, and the bias is one-directional — it does not average out.
   *"rolling 15 damage would normally grant 60 experience, but if an enemy has only 1
   hitpoint, you would receive only 4 experience"* — [Gemstone Crab](https://oldschool.runescape.wiki/w/Gemstone_Crab)
2. **The per-monster bonus cannot be computed.** There is a published formula, but
   manual overrides ignore it. Verified: Corporeal Beast and Abyssal demon both match
   the formula exactly; **Vorkath computes to +20% against a listed +0%**. The value
   must be read from a table, and that table is the P0 Wiki Bucket work that does not
   exist yet.
3. **Rounding is undocumented.** XP is stored in tenths and 1.33 per damage cannot be
   represented in tenths. No primary source describes the conversion.

**The correction, now implemented:** the roles swap. **The client's XP updates are the
measurement — exact, from the game, already accounting for overkill, bonuses and
rounding. Damage is only the allocator**, answering which monster the XP came from
when several were being hit. This needs no multiplier table, has no overkill error and
no rounding drift, so it is strictly better than the original plan. A derived figure
survives only as a checksum that can flag divergence, never as a published number.

---

## Combat experience

All rows verified 2026-08-16 against the OSRS Wiki. Re-check per OSRS update batch.

| Fact | Value | Source |
|---|---|---|
| Base rate | **4 XP per point of damage** (melee and ranged), **2** for magic. Per damage point, not per hit | [Combat](https://oldschool.runescape.wiki/w/Combat) |
| Hitpoints | **1.33 XP per damage**, every style | [Hitpoints](https://oldschool.runescape.wiki/w/Hitpoints) |
| Melee Accurate / Aggressive / Defensive | 4 to Attack / Strength / Defence + 1.33 HP | [Combat Options](https://oldschool.runescape.wiki/w/Combat_Options) |
| Melee Controlled | 1.33 each to Attack, Strength, Defence + 1.33 HP | [Combat](https://oldschool.runescape.wiki/w/Combat) |
| Ranged Accurate / Rapid | 4 Ranged + 1.33 HP | [Combat Options](https://oldschool.runescape.wiki/w/Combat_Options) |
| Ranged Longrange | 2 Ranged + 2 Defence + 1.33 HP | [Combat](https://oldschool.runescape.wiki/w/Combat) |
| Magic standard | 2 Magic per damage + **the spell's flat base XP** + 1.33 HP | [Combat spells](https://oldschool.runescape.wiki/w/Combat_spells) |
| Magic defensive | 1.33 Magic + 1 Defence per damage + base cast XP + 1.33 HP. **Totals 2.33, not 4** — the asymmetry is real | [Combat spells](https://oldschool.runescape.wiki/w/Combat_spells) |
| Splashing | Base cast XP is still paid; damage XP is not | [Combat spells](https://oldschool.runescape.wiki/w/Combat_spells) |
| **Overkill** | **Grants no XP.** Capped at the target's remaining HP | [Gemstone Crab](https://oldschool.runescape.wiki/w/Gemstone_Crab) |
| Poison damage | **No XP** (but full Slayer XP, even if the poison was not yours) | [Poison](https://oldschool.runescape.wiki/w/Poison) |
| Dwarf multicannon | **2 Ranged per damage, zero Hitpoints XP** | [Dwarf multicannon](https://oldschool.runescape.wiki/w/Dwarf_multicannon) |
| Combat dummies | No XP at all | [Combat dummy](https://oldschool.runescape.wiki/w/Combat_dummy) |
| Barbarian Assault | *"The minigame does not provide any combat experience for any action"* | [Barbarian Assault](https://oldschool.runescape.wiki/w/Barbarian_Assault) |
| Nightmare Zone | Reduces the **base cast** XP of (nz) runes only; damage XP unaffected. NMZ bosses carry normal per-NPC bonuses | [Nightmare Zone](https://oldschool.runescape.wiki/w/Nightmare_Zone) |
| XP storage | Signed 32-bit int treated as **fixed-point with one decimal** — tracked in tenths | [Experience](https://oldschool.runescape.wiki/w/Experience) |
| Slayer XP | Granted **per kill**, equal to the monster's hitpoints — **not** per damage. Must never go through a damage-proportional allocator | [Poison](https://oldschool.runescape.wiki/w/Poison), [Slayer](https://oldschool.runescape.wiki/w/Slayer) |

### Per-monster XP bonus

Real, and exposed by the wiki as infobox field **`xpbonus`** — *"The bonus experience
gained in combat (hitpoints, melee/ranged/magic) from attacking this monster. Express
as a number (e.g. 2.5 for 2.5%)"* — [Template:Infobox Monster](https://oldschool.runescape.wiki/w/Template:Infobox_Monster).

```
multiplier      = 1 + (1/40) * floor( 39 * AverageLevel * (AverageDefBonus + StrengthBonus + AttackBonus) / 200000 )
AverageLevel    = floor( (Attack + Strength + Defence + min(Hitpoints, 2000)) / 4 )
AverageDefBonus = floor( (StabDefence + SlashDefence + CrushDefence) / 3 )
```

Applies to Attack, Strength, Defence, Ranged, Magic, Hitpoints **and Slayer**. Range
is roughly **0.025x to 2.875x** ([Experience multiplier](https://oldschool.runescape.wiki/w/Experience_multiplier)).

- Formula reproduces the wiki exactly for [Corporeal Beast](https://oldschool.runescape.wiki/w/Corporeal_Beast) (+55%) and [Abyssal demon](https://oldschool.runescape.wiki/w/Abyssal_demon) (+0%)
- **It does not for [Vorkath](https://oldschool.runescape.wiki/w/Vorkath)** — computes +20%, infobox says +0%. Manual override or unfilled field, UNVERIFIED which
- Negative bonuses exist: [Gemstone Crab](https://oldschool.runescape.wiki/w/Gemstone_Crab) is **−12.5%**
- *"Some monsters have manually defined experience bonuses that ignore the formula"* — Great Olm gives none despite its stats

**Consequence:** never compute this from stats. Read the per-NPC value, and treat the
wiki field as editor-entered data that may be unfilled.

### UNVERIFIED — wiki is silent, do not build on these

- How 1.33/damage converts into tenths per hit. **This is the main source of drift in
  any derived figure**
- Whether Vengeance, ring of recoil, thrall or venom damage grants XP
- Powered staff (trident, sanguinesti) numeric XP rates, and whether they pay a base
  cast component
- Whether the player's attack style affects cannon XP
- Damage caps and immunity phases generally

---

## Kill and death mechanics

| Fact | Value | Source | Verified |
|---|---|---|---|
| `ActorDeath` fires at health-ratio-zero, not actual death | Holds — RuneLite issues #12453, #15394, #16479 all still open | RuneLite | 2026-08-14 |
| Transform-death monsters never get flagged dead | Gargoyles, rockslugs, desert lizards, zygomites — finished with an item | wiki + core `LootManager` hardcodes exactly this list | 2026-08-16 |
| Core `LootManager` posts no loot event when the ground is empty | `if (!allItems.isEmpty())` — the structural reason lootless kills are invisible ecosystem-wide | `game/LootManager.java:345` | 2026-08-16 |
| Zygomite `_CAP` variants | Excluded on plausibility, never tested — needs 57 Slayer | — | UNVERIFIED |
| Whether `ActorDeath` can fire twice on one regenerating NPC | Unknown | — | UNVERIFIED |

---

## How to add a row

1. Fetch the actual wiki page. A search snippet is not a source — that mistake is what
   started this whole discipline.
2. Quote it verbatim where the wording carries weight.
3. Date it, and set a re-check cadence (most game mechanics: per OSRS update batch).
4. If the wiki does not say, write UNVERIFIED. Do not infer from a related page.
5. If a row contradicts something already in a doc, log the contradiction at the top of
   this file and fix every doc that depended on it — not just this one.
