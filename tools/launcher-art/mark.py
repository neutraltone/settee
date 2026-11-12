#!/usr/bin/env python3
"""Writes every copy of Settee's mark from one description of it (#153, #157).

Outputs, relative to the repo root:
  tools/launcher-art/settee-icon.svg     the mark in its coral tile: source of the API 24-25 PNGs
  tools/launcher-art/settee-banner.svg   the TV banner: the mark beside the outlined wordmark
  app/src/main/res/drawable/ic_launcher_foreground.xml   adaptive icon, inside the safe circle
  app/src/main/res/drawable/ic_launcher_monochrome.xml   themed-icon layer: the silhouette
  app/src/main/res/drawable/ic_notification.xml          notification small icon: the silhouette
  app/src/main/res/drawable/ic_settee_mark.xml           the tile and mark, for screens
  app/src/main/res/drawable/ic_waiting_tv.xml            the aerial TV's still parts, full size
  app/src/main/res/drawable/ic_waiting_tv_small.xml      the aerial TV's still parts, at text height
  app/src/main/java/app/opentv/ui/design/AerialTvShape.kt  where Compose draws its moving parts

The wordmark comes from settee-wordmark.svg (see outline_wordmark.py), so this needs no font and
only the standard library. tools/render-launcher-art.sh runs it before rendering the PNGs, and
test_mark.py fails when a committed file no longer matches what this writes.

    python3 tools/launcher-art/mark.py [repo root]
"""
import math
import re
import sys
from pathlib import Path

# Kept in step with @color/settee_coral and @color/settee_plum, which test_mark.py checks. The
# drawables use those resources; the SVGs can't, so they carry the hex.
CORAL, PLUM = "#FF8F6B", "#1A1416"
# SetteeColors.Text: "the tile beside the name in cream" on a dark screen (docs/LOOK.md, Identity).
CREAM = "#F8EFEA"
CORAL_RES, PLUM_RES = "@color/settee_coral", "@color/settee_plum"
CREAM_RES = "@color/settee_cream"

# The mark, in the #153 prototype's own 100-unit space.
AERIAL_ENDS = [((50, 30), (37, 13)), ((50, 30), (63, 13))]
AERIAL_WIDTH = 4.5
KNOBS = [(36.5, 11.5, 4), (63.5, 11.5, 4)]
LEGS = [(15, 74, 9, 14, 2.5), (76, 74, 9, 14, 2.5)]
BODY = (21, 26, 58, 52, 11)
ARMS = [(5, 45, 24, 36, 12), (71, 45, 24, 36, 12)]
BASE = (16, 62, 68, 19, 0)
SCREEN = (28, 33, 44, 28, 5)
EYES = [(38.5, 45, 4.4), (61.5, 45, 4.4)]
SMILE, SMILE_WIDTH = "M45,50Q50,55 55,50", 3.4
SLIT = (31, 67, 38, 3.5, 1.75)

# The aerial TV that shows Settee is waiting (#170): the mark's back on its own, as a little TV,
# in cream so it reads on the dark screen. It stops just under the screen, where the seat begins.
# Coral arcs rise from each knob along its aerial, so the view reaches above the mark's top.
WAIT_BODY = (21, 26, 58, 42, 11)
WAIT_ORIGIN, WAIT_VIEW = (11, -8), 78
WAIT_ARC_RADII, WAIT_ARC_WIDTH, WAIT_ARC_SWEEP = (8, 12, 16), 3, 70
# At text height the face would be a smudge and thin aerials vanish: no face, thicker aerials, one arc.
WAIT_SMALL_AERIAL_WIDTH, WAIT_SMALL_KNOB = 7, 5.5
WAIT_SMALL_ARC_RADIUS, WAIT_SMALL_ARC_WIDTH = 11, 5

SAFE_RADIUS = 32  # a unit inside the adaptive icon's 33 (of 108), so antialiasing never meets the mask
WRAP = 100


def fmt(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def rrect(x, y, w, h, r):
    if r == 0:
        return f"M{fmt(x)},{fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}z"
    a = f"a{fmt(r)},{fmt(r)} 0 0 1"
    return (f"M{fmt(x + r)},{fmt(y)}h{fmt(w - 2 * r)}{a} {fmt(r)},{fmt(r)}v{fmt(h - 2 * r)}"
            f"{a} {fmt(-r)},{fmt(r)}h{fmt(-(w - 2 * r))}{a} {fmt(-r)},{fmt(-r)}"
            f"v{fmt(-(h - 2 * r))}{a} {fmt(r)},{fmt(-r)}z")


def circle(cx, cy, r):
    a = f"a{fmt(r)},{fmt(r)} 0 1 0"
    return f"M{fmt(cx - r)},{fmt(cy)}{a} {fmt(2 * r)},0{a} {fmt(-2 * r)},0z"


AERIALS = "".join(f"M{a[0]},{a[1]}L{b[0]},{b[1]}" for a, b in AERIAL_ENDS)


def fill(d, colour, evenodd=False):
    return {"d": d, "fill": colour, "evenodd": evenodd}


def stroke(d, colour, width):
    return {"d": d, "stroke": colour, "width": width}


def colour_mark(plum, coral):
    """The mark in plum on coral, in the prototype's drawing order."""
    return ([stroke(AERIALS, plum, AERIAL_WIDTH)] + [fill(circle(*k), plum) for k in KNOBS]
            + [fill(rrect(*r), plum) for r in LEGS + [BODY] + ARMS + [BASE]]
            + [fill(rrect(*SCREEN), coral)] + [fill(circle(*e), plum) for e in EYES]
            + [stroke(SMILE, plum, SMILE_WIDTH), fill(rrect(*SLIT), coral)])


def silhouette(colour):
    """The mark in one colour, the screen and seat slit cut out and the face inside them.

    Android draws a notification icon and a themed icon from alpha alone, so the coral parts
    have to be holes. Separate paths that overlap would paint over each other's holes, so the
    body is split where the holes are: the TV with the screen cut out, the seat base with the
    slit cut out (each even-odd), and the armrests trimmed to the part outside both.
    """
    tv = "M32,26h36a11,11 0 0 1 11,11v26h-58v-26a11,11 0 0 1 11,-11z" + rrect(*SCREEN)
    base = rrect(*BASE) + rrect(*SLIT)
    rise = 12 - math.sqrt(12 ** 2 - 4 ** 2)  # where an armrest's round end meets the TV's side
    side = 36 - 2 * rise
    arm_l = (f"M17,45a12,12 0 0 0 -12,12v12a12,12 0 0 0 12,12a12,12 0 0 0 4,{fmt(-rise)}"
             f"v{fmt(-side)}a12,12 0 0 0 -4,{fmt(-rise)}z")
    arm_r = (f"M83,45a12,12 0 0 1 12,12v12a12,12 0 0 1 -12,12a12,12 0 0 1 -4,{fmt(-rise)}"
             f"v{fmt(-side)}a12,12 0 0 1 4,{fmt(-rise)}z")
    return ([stroke(AERIALS, colour, AERIAL_WIDTH)] + [fill(circle(*k), colour) for k in KNOBS]
            + [fill(rrect(*r), colour) for r in LEGS] + [fill(arm_l, colour), fill(arm_r, colour)]
            + [fill(tv, colour, True), fill(base, colour, True)]
            + [fill(circle(*e), colour) for e in EYES] + [stroke(SMILE, colour, SMILE_WIDTH)])


def waiting_tv(small):
    """The aerial TV's still parts. Compose draws the eyes (they blink) and the arcs on top."""
    if small:
        return ([stroke(AERIALS, CREAM_RES, WAIT_SMALL_AERIAL_WIDTH)]
                + [fill(circle(x, y, WAIT_SMALL_KNOB), CREAM_RES) for x, y, _ in KNOBS]
                + [fill(rrect(*WAIT_BODY), CREAM_RES), fill(rrect(*SCREEN), CORAL_RES)])
    return ([stroke(AERIALS, CREAM_RES, AERIAL_WIDTH)] + [fill(circle(*k), CREAM_RES) for k in KNOBS]
            + [fill(rrect(*WAIT_BODY), CREAM_RES), fill(rrect(*SCREEN), CORAL_RES),
               stroke(SMILE, PLUM_RES, SMILE_WIDTH)])


def aerial_tv_kotlin():
    """The moving parts' places, in the drawables' viewport, for ui/design/AerialTv.kt."""
    ox, oy = WAIT_ORIGIN

    def offset(x, y):
        return f"Offset({fmt(x - ox)}f, {fmt(y - oy)}f)"

    def angle(a, b):
        return fmt(math.degrees(math.atan2(b[1] - a[1], b[0] - a[0])))

    def floats(values):
        return ", ".join(f"{fmt(v)}f" for v in values)

    return "\n".join([
        "/*",
        " * This file is part of OpenTV.",
        " * Copyright (C) 2026 The OpenTV Contributors",
        " * Licensed under the GNU General Public License v3.0 or later.",
        " */",
        "package app.opentv.ui.design",
        "",
        "import androidx.compose.ui.geometry.Offset",
        "",
        "/**",
        " * Where the aerial TV's moving parts go, in the viewport of ic_waiting_tv and ic_waiting_tv_small,",
        " * so Compose draws them where the mark has them. Written by tools/launcher-art/mark.py.",
        " */",
        "internal object AerialTvShape {",
        f"    const val VIEWPORT = {fmt(WAIT_VIEW)}f",
        f"    val knobs = listOf({', '.join(offset(x, y) for x, y, _ in KNOBS)})",
        "",
        "    /** The way each knob's arcs face: along its aerial, in degrees clockwise from three o'clock. */",
        f"    val arcFacing = floatArrayOf({', '.join(angle(a, b) + 'f' for a, b in AERIAL_ENDS)})",
        f"    const val ARC_SWEEP = {fmt(WAIT_ARC_SWEEP)}f",
        f"    val arcRadii = floatArrayOf({floats(WAIT_ARC_RADII)})",
        f"    const val ARC_WIDTH = {fmt(WAIT_ARC_WIDTH)}f",
        f"    const val SMALL_ARC_RADIUS = {fmt(WAIT_SMALL_ARC_RADIUS)}f",
        f"    const val SMALL_ARC_WIDTH = {fmt(WAIT_SMALL_ARC_WIDTH)}f",
        f"    val eyes = listOf({', '.join(offset(x, y) for x, y, _ in EYES)})",
        f"    const val EYE_RADIUS = {fmt(EYES[0][2])}f",
        "}",
        ""])


def extent():
    """The mark's ink: its bounding box, and how far it reaches from the box's centre."""
    rects = LEGS + [BODY] + ARMS + [BASE]
    xs = [x for x, _, w, _, _ in rects] + [x + w for x, _, w, _, _ in rects]
    ys = [y for _, y, _, h, _ in rects] + [y + h for _, y, _, h, _ in rects]
    xs += [cx + s * r for cx, _, r in KNOBS for s in (-1, 1)]
    ys += [cy - r for _, cy, r in KNOBS]
    box = (min(xs), min(ys), max(xs), max(ys))
    centre = ((box[0] + box[2]) / 2, (box[1] + box[3]) / 2)
    reach = 0.0
    for x, y, w, h, r in rects:  # each corner's arc reaches its arc centre's distance plus r
        for px in (x + r, x + w - r):
            for py in (y + r, y + h - r):
                reach = max(reach, math.dist(centre, (px, py)) + r)
    for cx, cy, r in KNOBS:
        reach = max(reach, math.dist(centre, (cx, cy)) + r)
    for a, b in AERIAL_ENDS:
        reach = max(reach, math.dist(centre, b) + AERIAL_WIDTH / 2, math.dist(centre, a) + AERIAL_WIDTH / 2)
    return box, centre, reach


def wrap_words(text, width, indent):
    lines, line = [], ""
    for word in text.split():
        if line and len(indent) + len(line) + 1 + len(word) > width:
            lines.append(indent + line)
            line = word
        else:
            line = f"{line} {word}" if line else word
    return lines + [indent + line] if line else lines


def wrap_path(d, indent):
    """Path data split at commands, so no line runs past WRAP; XML turns the breaks into spaces."""
    parts = re.findall(r"[A-Za-z][^A-Za-z]*", d)
    lines, line = [], ""
    for p in parts:
        if line and len(indent) + len(line) + len(p) > WRAP:
            lines.append(line)
            line = p
        else:
            line += p
    lines.append(line)
    return ("\n" + indent).join(lines)


def vector(size_dp, viewport, comment, groups):
    out = ['<?xml version="1.0" encoding="utf-8"?>', "<!--"] + wrap_words(comment, WRAP, "  ") + ["-->",
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           f'    android:width="{size_dp}dp"', f'    android:height="{size_dp}dp"',
           f'    android:viewportWidth="{viewport}"', f'    android:viewportHeight="{viewport}">']
    for transform, paths in groups:
        ind = "    "
        if transform:
            scale, tx, ty = transform
            out += ["    <group", f'        android:scaleX="{fmt(scale)}"', f'        android:scaleY="{fmt(scale)}"',
                    f'        android:translateX="{fmt(tx)}"', f'        android:translateY="{fmt(ty)}">']
            ind = "        "
        for p in paths:
            out.append(f"{ind}<path")
            if "fill" in p:
                out.append(f'{ind}    android:fillColor="{p["fill"]}"')
                if p["evenodd"]:
                    out.append(f'{ind}    android:fillType="evenOdd"')
            else:
                out += [f'{ind}    android:strokeColor="{p["stroke"]}"',
                        f'{ind}    android:strokeWidth="{fmt(p["width"])}"',
                        f'{ind}    android:strokeLineCap="round"']
            body = ind + '    android:pathData="'
            out.append(body + wrap_path(p["d"], " " * len(body)) + '" />')
        if transform:
            out.append("    </group>")
    return "\n".join(out + ["</vector>"]) + "\n"


def svg_paths(paths, indent):
    out = []
    for p in paths:
        if "fill" in p:
            rule = ' fill-rule="evenodd"' if p["evenodd"] else ""
            out.append(f'{indent}<path d="{p["d"]}" fill="{p["fill"]}"{rule}/>')
        else:
            out.append(f'{indent}<path d="{p["d"]}" fill="none" stroke="{p["stroke"]}" '
                       f'stroke-width="{fmt(p["width"])}" stroke-linecap="round"/>')
    return out


def read_wordmark(art):
    """(path data, advance, ink top, ink bottom), in banner pixels with the baseline at y=0."""
    text = (art / "settee-wordmark.svg").read_text()
    x, y, w, h = (float(v) for v in re.search(r'viewBox="([^"]+)"', text)[1].split())
    advance = float(re.search(r'data-advance="([^"]+)"', text)[1])
    return re.search(r'<path d="([^"]+)"', text)[1], advance, y, y + h


def outputs(root):
    art = root / "tools/launcher-art"
    res = root / "app/src/main/res/drawable"
    box, centre, reach = extent()
    files = {}

    # Adaptive icon: scaled so the farthest point sits inside the safe circle, which leaves the
    # mark smaller than on the prototype's tile (74%) but never clipped by a round mask.
    s = SAFE_RADIUS / reach
    adaptive = (s, 54 - centre[0] * s, 54 - centre[1] * s)
    files[res / "ic_launcher_foreground.xml"] = vector(108, 108, (
        "Adaptive-icon foreground, written by tools/launcher-art/mark.py. The mark is scaled so its "
        "farthest point sits inside the safe circle (radius 33 of 108), so a round, squircle or square "
        "mask never clips the aerials or armrests. That leaves it smaller than on the prototype's "
        "tile, deliberately."), [(adaptive, colour_mark(PLUM_RES, CORAL_RES))])
    files[res / "ic_launcher_monochrome.xml"] = vector(108, 108, (
        "Adaptive-icon monochrome layer for themed icons, written by tools/launcher-art/mark.py: the "
        "mark in one colour with the screen and seat slit cut out, placed as in "
        "ic_launcher_foreground."), [(adaptive, silhouette("#FF000000"))])

    # Notification: Android keeps only the alpha. The mark fills 20dp of 24, the usual 2dp inset.
    n = 20 / max(box[2] - box[0], box[3] - box[1])
    files[res / "ic_notification.xml"] = vector(24, 24, (
        "Notification small icon, written by tools/launcher-art/mark.py. Android draws only its alpha, "
        "so a full-colour tile would be a blank square: this is the mark in one colour with the "
        "screen cut out."), [((n, 12 - centre[0] * n, 12 - centre[1] * n), silhouette("#FFFFFFFF"))])

    tile = [fill(rrect(0, 0, 100, 100, 22), CORAL_RES)]
    files[res / "ic_settee_mark.xml"] = vector(48, 100, (
        "Settee's mark in its coral tile at 74%, as the app icon draws it, written by "
        "tools/launcher-art/mark.py."), [(None, tile), ((0.74, 13, 13), colour_mark(PLUM_RES, CORAL_RES))])

    wait = (None, -WAIT_ORIGIN[0], -WAIT_ORIGIN[1])
    files[res / "ic_waiting_tv.xml"] = vector(48, WAIT_VIEW, (
        "The aerial TV's still parts, written by tools/launcher-art/mark.py: the mark's back as a "
        "little TV, with its face on the screen. AerialTv draws the eyes and the arcs over it."),
        [((1, *wait[1:]), waiting_tv(small=False))])
    files[res / "ic_waiting_tv_small.xml"] = vector(24, WAIT_VIEW, (
        "The aerial TV at text height, written by tools/launcher-art/mark.py: no face and thicker "
        "aerials, so it still reads that small. AerialTv draws its one arc over it."),
        [((1, *wait[1:]), waiting_tv(small=True))])
    files[root / "app/src/main/java/app/opentv/ui/design/AerialTvShape.kt"] = aerial_tv_kotlin()

    files[art / "settee-icon.svg"] = "\n".join(
        ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">',
         "  <!-- Written by mark.py: the mark in its coral tile at 74%, the API 24-25 launcher icon. -->",
         f'  <rect width="100" height="100" rx="22" fill="{CORAL}"/>',
         '  <g transform="translate(13 13) scale(.74)">'] + svg_paths(colour_mark(PLUM, CORAL), "    ")
        + ["  </g>", "</svg>"]) + "\n"

    # Banner, laid out as the prototype: the mark 30% of the width, a 4% gap, then the word, the
    # whole row centred, and the word's ink centred on the banner's middle.
    word, advance, top, bottom = read_wordmark(art)
    mark_w, gap = 96, 12.8
    left = (320 - (mark_w + gap + advance)) / 2
    word_x, word_y = left + mark_w + gap, 90 - (top + bottom) / 2
    files[art / "settee-banner.svg"] = "\n".join(
        ['<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180" viewBox="0 0 320 180">',
         "  <!-- Written by mark.py: the TV banner, the mark beside settee-wordmark.svg. -->",
         f'  <rect width="320" height="180" fill="{CORAL}"/>',
         f'  <g transform="translate({fmt(left)} 42) scale(.96)">'] + svg_paths(colour_mark(PLUM, CORAL), "    ")
        + ["  </g>", f'  <path transform="translate({fmt(word_x)} {fmt(word_y)})" d="{word}" fill="{PLUM}"/>',
           "</svg>"]) + "\n"

    # The phone page's lockup, for a dark screen: the tile beside the name in cream, the word's ink
    # centred on the tile. A Kotlin constant rather than an asset, so every page can include it
    # without a Context, and inline because the page's server stops listening once it has replied.
    scale = 1.25
    lock_x = 100 + 24
    lock_y = 50 - (top + bottom) * scale / 2
    width = lock_x + advance * scale
    lockup = "\n".join(
        [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {fmt(width)} 100" role="img" aria-label="Settee">',
         f'<rect width="100" height="100" rx="22" fill="{CORAL}"/>',
         '<g transform="translate(13 13) scale(.74)">']
        + [line.strip() for line in svg_paths(colour_mark(PLUM, CORAL), "")]
        + ["</g>", f'<path transform="translate({fmt(lock_x)} {fmt(lock_y)}) scale({fmt(scale)})" fill="{CREAM}" d="'
           + wrap_path(word, "") + '"/>', "</svg>"])
    files[root / "app/src/main/java/app/opentv/pairing/PairingLockup.kt"] = "\n".join([
        "/*",
        " * This file is part of OpenTV.",
        " * Copyright (C) 2026 The OpenTV Contributors",
        " * Licensed under the GNU General Public License v3.0 or later.",
        " */",
        "package app.opentv.pairing",
        "",
        "/** Settee's mark beside its name, for the phone page. Written by tools/launcher-art/mark.py. */",
        'internal const val PAIRING_LOCKUP = """',
        wrap_long(lockup),
        '"""',
        ""])
    return files


def wrap_long(text):
    """Breaks any line over WRAP at a space, which SVG treats as any other space."""
    out = []
    for line in text.split("\n"):
        while len(line) > WRAP:
            cut = line.rfind(" ", 0, WRAP)
            if cut <= 0:
                break
            out.append(line[:cut])
            line = line[cut + 1:]
        out.append(line)
    return "\n".join(out)


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
    for path, text in outputs(root).items():
        path.write_text(text)


if __name__ == "__main__":
    main()
