# Boss encounter classes — who can be in the fight

**Why this file exists.** On 2026-08-24 three game-mechanic claims were asserted from
memory in one session and the wiki contradicted all three within a minute — the cannon
XP rate, a tag-stealing scenario that does not exist, and "you and a mate duo Vorkath",
which is impossible because Vorkath is instanced. The third one was load-bearing: it was
the example justifying a whole ranking rule, and it was sent to the site agent twice.

The lesson is not "check Vorkath". It is that **kill ownership depends on the encounter
class**, and that class is a property of each boss that has to be looked up, not guessed.

Everything here is from the wiki's own classification on
[Boss](https://oldschool.runescape.wiki/w/Boss), read 2026-08-24.

---

## The wiki's headline claim, which contradicts an earlier assumption

> *"Most bosses (e.g. bosses like the Corporeal Beast) also reside in a multi-way area;
> very few reside in single-way areas or instances."*

An earlier row in `GAME-MECHANICS.md` said the opposite — "most bosses are instanced".
**That row was wrong and is corrected.** Counting the wiki's own section lists:

| Class | Count | Can others be in the fight? |
|---|---|---|
| World bosses | ~49 | **yes** — spawn in the open, multi-way |
| Wilderness bosses | ~29 | **yes** — plus PKers |
| Instanced bosses | ~44 | **no** — private lair per player |
| Slayer bosses | ~38 | mixed — most instanced or single-way |
| Skilling bosses | 9 | **yes** — group activities |
| Sporadic bosses | 10 | mixed |

So contested kills are **not** a rare edge case. They are roughly half the boss list.

---

## What this means for kill ownership

Three distinct mechanics, and they must not be collapsed into one rule:

**1. Ordinary monsters and world bosses — most damage wins.**
> *"The player who has done the most damage will see the drop before the other players."*
> — [Drops](https://oldschool.runescape.wiki/w/Drops)

One winner. Everyone else gets nothing, regardless of contribution.

**2. Team bosses — a minimum threshold, then proportional shares.**
> *"In order to obtain drops from Nex, the player must deal a set amount of minimum
> damage."* … *"the share players receive … is based on the player's total damage to Nex,
> her minions, and any reavers"* … *"Big bones are only dropped for the MVP, the player who
> dealt the most damage."* — [Nex](https://oldschool.runescape.wiki/w/Nex)

Multiple winners. A player at 17% of total damage is fully legitimate. **A majority rule
would exclude every member of an even six-person team.**

**3. Instanced bosses — nobody else is there.** Ownership is not a question. Vorkath,
Zulrah, Phosani's Nightmare, Yama, Amoxliatl, Phantom Muspah, Doom of Mokhaiotl.

---

## Named lists, for lookup rather than recall

**Instanced — solo by construction:** The Nightmare (Phosani's), Royal Titans, Yama,
Zulrah, Vorkath, Brutus, Obor, Bryophyta, Amoxliatl, Phantom Muspah, Doom of Mokhaiotl,
Duke Sucellus / The Whisperer / Vardorvis / The Leviathan (The Forgotten Four).

**World — open, multi-way, contestable:** Barrows brothers, Gemstone Crab, Scurrius,
Giant Mole, Deranged Archaeologist, Dagannoth Kings (Supreme / Rex / Prime), Sarachnis,
Blood/Blue/Eclipse Moon, Kalphite Queen, God Wars Dungeon generals (Kree'arra, K'ril,
Graardor, Zilyana), Corporeal Beast.

**Wilderness — contestable plus PK risk:** Chaos Fanatic, Crazy Archaeologist, Scorpia,
King Black Dragon, Chaos Elemental, Revenant maledictus, Calvar'ion / Vet'ion,
Spindel / Venenatis, Artio / Callisto.

**Skilling — group by design:** Tempoross, Wintertodt, Zalcano, Hespori.

**Slayer — mostly private or single-way:** Grotesque Guardians, Abyssal Sire, Kraken,
Cerberus, Araxxor, Thermonuclear smoke devil, Alchemical Hydra.

---

## Rules that follow

1. **Never reason about team play from a solo boss.** Vorkath and Zulrah cannot teach you
   anything about contested kills.
2. **Never reason about solo play from a team boss.** Nex's threshold rule does not apply
   to a goblin.
3. **The encounter class is a per-boss fact.** Look it up. There is no general rule that
   covers Zulrah and Nex at once.
4. **Do not invent a damage threshold.** The game uses a per-boss "set amount" that the
   wiki does not publish. Any number chosen here would be fabricated, which is the trap
   already recorded twice for `DEATH_CONFIRM_TICKS`.

---

## Still unverified

- The actual minimum-damage threshold at Nex, and whether other team bosses use one.
- Whether GWD generals use most-damage or a threshold — they are multi-way and teamed
  in practice, but the mechanic has not been read.
- Whether Corporeal Beast, explicitly named by the wiki as the multi-way example, uses
  most-damage or something else.

**Do not fill these in from memory.** Read the boss's own wiki page and cite it.
