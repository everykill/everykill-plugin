# Plugin → site: the brand mark

**From:** Tyler (plugin lane)
**To:** Gage (website/backend lane)
**Date:** 2026-08-25

Delk picked a logo. Everything's in `brand/` in the plugin repo — **use the SVG,
not a PNG**, so the site and the plugin render the identical file instead of two
things that look nearly the same.

## The mark

Four tally uprights, struck through. Counting kills is the product, and a tally
is the one counting glyph everybody already reads. The strike doubles as the
"five" mark.

Rust on `--panel` with a `--line` border — your own `.card`, so it reads as part
of the UI rather than a sticker on top of it. Delk's call, and the right one: the
solid-rust version got loud on light backgrounds.

## Files

| | |
|---|---|
| `everykill-mark.svg` | **the one you want.** Card version, bordered. |
| `everykill-mark-round.svg` | round crop, for avatars |
| `everykill-mark-plain.svg` | no border |
| `everykill-mark-bare.svg` | strokes only, transparent — for placing on an existing background |
| `everykill-1024.png` … `-48.png` | raster, if you need it |
| `everykill-discord-512.png` / `-1024.png` | round, for the Discord icon |

Inline, if that's easier than fetching the file:

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64"><rect x="1" y="1" width="62" height="62" rx="11.43" fill="#16181d" stroke="#23262d" stroke-width="2"/><g stroke="#d94f2b" stroke-width="4.6" stroke-linecap="round"><line x1="18" y1="20" x2="18" y2="44"/><line x1="27" y1="20" x2="27" y2="44"/><line x1="36" y1="20" x2="36" y2="44"/><line x1="45" y1="20" x2="45" y2="44"/><line x1="13" y1="41" x2="50" y2="23"/></g></svg>
```

## Replacing `.brand-mark`

Your current mark is `EK` in a rust square — 28px, 5px radius, mono 700. The new
one is a drop-in at the same size; the SVG's radius is the same 5/28 ratio, so it
scales honestly.

The `EK` version is fine as a favicon and I'd keep it there if you like it, but it
can't stand alone as an avatar — it needs the word next to it to mean anything.
The tally doesn't.

## Two things worth knowing

**The geometry lives in three places and must not drift.** `brand/brandmark.py`
(SVGs), `brand/BrandPng.java` (PNGs), and `TallyMark` in the plugin panel. Same
numbers in each, constants at the top of each. If the site hand-rolls a fourth
copy we'll have four logos in a year.

**Don't recolour it.** The mark is rust. There's no blue version, no light-mode
variant that inverts to white — the dark tile already carries itself on a light
background, which is exactly why Delk picked this one over solid rust.

## The numbers, if you ever need to redraw it

64-unit box, scaled once:

```
uprights   x = 18, 27, 36, 45      (9px pitch)
           y = 20 → 44
strike     (13, 41) → (50, 23)
stroke     4.6, round caps
radius     5/28 of the size
```

Stroke is 4.6 rather than a round 5 for a reason: four uprights at 9px pitch leave
4.4px of gap at 5px weight, and the round caps close that up at small sizes.

And the strike is deliberately *not* corner-to-corner. It was, at first — it landed
on the same y as the outer uprights' end caps and fused with them into a blob.
Invisible at 64px, obvious at 512. If you redraw it, keep the strike crossing the
middle of the uprights rather than their tips.

— Tyler
