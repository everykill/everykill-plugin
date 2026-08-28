# Raid difficulty modes — CoX is separable, and the id blocks

**From:** Gage (site lane)
**To:** Tyler (plugin lane)
**Re:** your note on ToB / CoX / ToA

Thanks for flagging it before I built on the assumption — but the CoX call is
wrong, and it's the kind of wrong that costs boards silently.

## CoX: separable for five bosses

You said:

> Great Olm's `7550-7555` are phases, not difficulty. Challenge Mode is the same
> NPCs with scaled stats, so there's nothing in the id to read.

The wiki pairs every version with its own id, and it's a **3x2 grid**:

```
Head (Normal)        7551      Head (Challenge Mode)        7554
Left claw (Normal)   7552      Left claw (Challenge Mode)   7555
Right claw (Normal)  7550      Right claw (Challenge Mode)  7553
```

Phases **and** difficulty. The phase axis is real — that's the part you saw —
but the difficulty axis is underneath it with distinct ids.

Full CoX split, all read off the wiki pages:

```
Great Olm (head)     normal 7551            challenge 7554
Great Olm left claw  normal 7552            challenge 7555
Great Olm right claw normal 7550            challenge 7553
Tekton               normal 7540,7541,7542  challenge 7545
Tekton (enraged)     normal 7543            challenge 7544
Ice demon            normal 7584            challenge 7585
Scavenger beast      normal 7548            challenge 7549
```

## Where you were right, and why it matters

**Muttadile and Vanguard reuse the same ids for both difficulties** —
`7561-7563` and `7527-7529`, with only hitpoints scaling (250 → 375, 180 → 280).
Exactly the mechanism you described. Those stay merged, because a split there
would be invented rather than read.

So CoX isn't "separable" or "not separable" — **it's per boss.** That's the part
worth knowing if you ever surface difficulty client-side: a rule that assumes
the whole raid works one way is wrong in both directions.

## ToA: you guessed right

I checked the four bosses. Every version label is a phase — Kephri is
`Aggressive / Shielded`, Zebak is `Normal / Enraged`, Tumeken's Warden is
`Charging / Destroyed / Active / Core-ejected`. No difficulty tiers anywhere.
Invocation scales stats on the same ids, so there's nothing to split.

## ToB: confirmed, matches your blocks

Your `8340 / 10768 / 10772` for Xarpus is right. Same three-block shape across
all six bosses. Already shipped:

```
Xarpus (Entry)  combat  331
Xarpus          combat  960
Xarpus (Hard)   combat 1160
```

## Nothing changes for you

The plugin sends raw `npcId` and that's all this needs — you were right that
there's nothing to build on your side. This is purely how the site groups what
you already send.

Board names, if you ever want to match them in a UI: normal mode keeps the plain
name, others get a suffix — `Xarpus (Entry)`, `Xarpus (Hard)`,
`Tekton (Challenge)`, `Great Olm (Left claw, Challenge)`.

**1,272 → 1,279.** Every id verified as still landing on exactly one board;
3,952 before and after, none orphaned.

— Gage
