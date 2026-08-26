# Everykill brand

One mark: four tally uprights, struck through. Rust on the site's panel colour.

## Files

| | |
|---|---|
| `everykill-1024.png` | source of truth for anything raster |
| `everykill-512.png` | general use |
| `everykill-256.png`, `-128.png` | smaller raster |
| `everykill-48.png` | Plugin Hub icon (also copied to `/icon.png`) |
| `everykill-discord-512.png`, `-1024.png` | round crop, for avatars |
| `everykill-mark.svg` | the card version — use this on the site |
| `everykill-mark-round.svg` | round crop |
| `everykill-mark-plain.svg` | no border |
| `everykill-mark-bare.svg` | strokes only, transparent — for placing on any background |
| `everykill-nav-64.png` | the sidebar glyph, big — source for `panel_icon.png` |

## Regenerating

```bash
java brand/BrandPng.java brand                                  # every PNG
python brand/brandmark.py brand                                 # every SVG
java brand/NavIcon.java src/main/resources/panel_icon.png 20    # sidebar glyph
```

Both read the same numbers. Change the geometry in one and the other drifts, so
change both — they're each ~20 lines and the constants sit at the top.

## The numbers

Authored in a **64-unit box** and scaled once, so every export is identical
rather than re-derived per size.

```
uprights   x = 18, 27, 36, 45      (9px pitch)
           y = 20 → 44
strike     (13, 41) → (50, 23)
stroke     4.6, round caps
radius     5/28 of the size        (matches .brand-mark)
```

Colours, from `everykill-site/styles.css`:

```
--panel  #16181d    plate
--line   #23262d    border
--acc    #d94f2b    the marks
```

## Two things that were wrong, so they don't come back

**The strike used to run corner to corner.** It landed on the same y as the
outer uprights' round caps and fused with them into a blob — obvious at 512,
invisible at 64. It's shallower now and crosses nearer the middle of each
upright, which is where a real tally strike lands anyway.

**Stroke is 4.6, not 5.** Four uprights at 9px pitch leave 4.4px of gap at 5px
weight; the caps closed that up at small sizes. 4.6 keeps daylight between them
at 48px.

## The sidebar icon is deliberately different

`src/main/resources/panel_icon.png` is the tally **with no plate** — bare strokes
on transparency.

RuneLite draws sidebar buttons on its own dark strip, and every core plugin ships
a bare glyph. A plugin that brings its own tile looks like a sticker stuck onto
the toolbar rather than part of it.

It's also **cropped to the ink and scaled to fill**. The marks only occupy
x 13–50, y 20–44 of the 64-box; without the plate around them that's a lot of
dead space, and scaling the whole box would render the glyph about 60% the size
of every neighbouring icon.

## Rules

- **Don't retype the geometry.** The plugin panel draws this mark from the same
  constants (`TallyMark` in `EverykillPanel`). Three copies of a logo is three
  logos.
- **The bare SVG is for placing on existing backgrounds**, not for recolouring.
  The mark is rust. It doesn't have a blue version.
- **Don't scale the PNGs up.** 1024 is the source; anything larger should be
  rendered from the SVG.
