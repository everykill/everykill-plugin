"""Everykill brand mark — the tally, on a panel card.

One geometry, all the sizes. Rust strokes on --panel with a --line border,
which is the site's own .card component rather than a sticker sitting on top
of the UI.

Everything is derived from the same numbers, so a tweak lands everywhere at
once instead of drifting across six exported files.
"""
import io
import os
import subprocess
import sys

# everykill-site styles.css, read not remembered
BG = "#0b0c0e"      # --bg
PANEL = "#16181d"   # --panel
LINE = "#23262d"    # --line
ACC = "#d94f2b"     # --acc  "rs-adjacent rust, not jagex gold"

# the mark, in a 64-unit box. four uprights at 9px pitch, struck through.
UPRIGHTS = [18, 27, 36, 45]
TOP, BOT = 20, 44
SLASH = (13, 41, 50, 23)
STROKE = 4.6

# .brand-mark is 5px radius on 28px. same ratio, so it scales honestly.
RADIUS_RATIO = 5 / 28


def svg(size, *, round_crop=False, bordered=True, transparent=False):
    """The mark at one size. Coordinates stay in the 64-box; the viewBox scales."""
    r = 64 * RADIUS_RATIO

    if transparent:
        plate = ""
    elif round_crop:
        # discord crops to a circle anyway. drawing the circle ourselves means
        # the border follows the crop instead of being clipped square-cornered.
        plate = (
            f'<circle cx="32" cy="32" r="31" fill="{PANEL}" '
            f'stroke="{LINE}" stroke-width="2"/>'
            if bordered
            else f'<circle cx="32" cy="32" r="32" fill="{PANEL}"/>'
        )
    elif bordered:
        plate = (
            f'<rect x="1" y="1" width="62" height="62" rx="{r:.2f}" '
            f'fill="{PANEL}" stroke="{LINE}" stroke-width="2"/>'
        )
    else:
        plate = f'<rect width="64" height="64" rx="{r:.2f}" fill="{PANEL}"/>'

    marks = "".join(
        f'<line x1="{x}" y1="{TOP}" x2="{x}" y2="{BOT}"/>' for x in UPRIGHTS
    )
    x1, y1, x2, y2 = SLASH
    marks += f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}"/>'

    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
        f'viewBox="0 0 64 64">'
        f"{plate}"
        f'<g stroke="{ACC}" stroke-width="{STROKE}" stroke-linecap="round">'
        f"{marks}</g></svg>"
    )


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    io.open(path, "w", encoding="utf-8", newline="\n").write(text)
    return path


def png(svg_path, png_path, size):
    """SVG -> PNG. Tries rsvg/inkscape/magick, else reports what to install."""
    for cmd in (
        ["rsvg-convert", "-w", str(size), "-h", str(size), svg_path, "-o", png_path],
        ["inkscape", svg_path, "-w", str(size), "-h", str(size), "-o", png_path],
        ["magick", "-background", "none", "-density", "600", svg_path,
         "-resize", f"{size}x{size}", png_path],
    ):
        try:
            r = subprocess.run(cmd, capture_output=True, timeout=60)
            if r.returncode == 0 and os.path.exists(png_path):
                return cmd[0]
        except (FileNotFoundError, subprocess.TimeoutExpired):
            continue
    return None


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "brand"

    targets = [
        ("everykill-mark.svg", 64, dict()),
        ("everykill-mark-round.svg", 64, dict(round_crop=True)),
        ("everykill-mark-plain.svg", 64, dict(bordered=False)),
        ("everykill-mark-bare.svg", 64, dict(transparent=True)),
    ]
    for name, size, kw in targets:
        print(" ", write(os.path.join(out, name), svg(size, **kw)))

    pngs = [
        ("everykill-1024.png", "everykill-mark.svg", 1024),
        ("everykill-512.png", "everykill-mark.svg", 512),
        ("everykill-discord-512.png", "everykill-mark-round.svg", 512),
        ("everykill-256.png", "everykill-mark.svg", 256),
        ("everykill-48.png", "everykill-mark.svg", 48),
    ]
    tool = None
    for name, src, size in pngs:
        tool = png(os.path.join(out, src), os.path.join(out, name), size)
        if tool is None:
            print("\nno svg rasteriser found (rsvg-convert / inkscape / magick).")
            print("svgs are written; render them however you like.")
            break
        print(f"  {out}/{name}  via {tool}")
