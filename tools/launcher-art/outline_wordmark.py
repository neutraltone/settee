#!/usr/bin/env python3
"""Outlines the words mark.py draws as shapes, kerned, so it needs no font (#157, #244).

  settee-wordmark.svg  "Settee" in Young Serif 3.003, beside the mark on the banner
  debug-badge.svg      "DEBUG" in Alegreya Sans Bold, on the debug build's badge

Run only when a word changes: the output is committed, so mark.py and the PNG render need no font.
Young Serif is pinned at 3.003, the exact Google Fonts file (see docs/LOOK.md), because upstream
has since redrawn it much thinner; Alegreya Sans Bold is the copy the app ships in res/font. The
hash check refuses any other file.

Needs fontTools and uharfbuzz (pip install fonttools uharfbuzz) and Young Serif from
https://raw.githubusercontent.com/google/fonts/447b80922b0da6c69353c337337590dd8e24217a/ofl/youngserif/YoungSerif-Regular.ttf

    python3 tools/launcher-art/outline_wordmark.py path/to/YoungSerif-Regular.ttf
"""
import hashlib
import sys
from pathlib import Path

import uharfbuzz as hb
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

YOUNG_SERIF_SHA256 = "42bb2602364b28569fdce5eeb6ab3d81cf8090ba81b211f1229ed69f33155523"
ALEGREYA_SANS_BOLD_SHA256 = "a3055a1893759bdbd7504bb22abc583769e7974c49353176eac0b03792c9fb8e"
ALEGREYA_SANS_BOLD = Path(__file__).resolve().parents[2] / "app/src/main/res/font/alegreya_sans_bold.ttf"


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def read_font(font_path, sha256, name):
    data = font_path.read_bytes()
    if hashlib.sha256(data).hexdigest() != sha256:
        sys.exit(f"{font_path} isn't {name} (sha256 {sha256})")
    return data


def outline(font_path, data, word, size, name, out, use):
    # HarfBuzz applies the font's kerning, as the browser did in the prototype.
    face = hb.Face(data)
    hb_font = hb.Font(face)
    buf = hb.Buffer()
    buf.add_str(word)
    buf.guess_segment_properties()
    hb.shape(hb_font, buf, {"kern": True, "liga": False})

    font = TTFont(font_path)
    glyphs = font.getGlyphSet()
    order = font.getGlyphOrder()
    k = size / font["head"].unitsPerEm
    pen = SVGPathPen(glyphs, ntos=fmt)
    bounds = BoundsPen(glyphs)
    x = 0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        glyph = order[info.codepoint]
        ox, oy = x + pos.x_offset, pos.y_offset
        glyphs[glyph].draw(TransformPen(pen, (k, 0, 0, -k, ox * k, -oy * k)))
        glyphs[glyph].draw(TransformPen(bounds, (1, 0, 0, 1, ox, oy)))
        x += pos.x_advance
    _, ymin, _, ymax = bounds.bounds
    advance = x * k
    top, height = -ymax * k, (ymax - ymin) * k

    out.write_text(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 {fmt(top)} {fmt(advance)} {fmt(height)}" '
        f'data-advance="{fmt(advance)}">\n'
        f'  <!-- "{word}" in {name} (SIL OFL), kerned and outlined at {size}px by\n'
        f"       outline_wordmark.py, baseline at y=0. mark.py places it {use}. -->\n"
        f'  <path d="{pen.getCommands()}"/>\n'
        "</svg>\n")


def main():
    art = Path(__file__).resolve().parent
    young_serif = Path(sys.argv[1])
    # 57.6px is 18% of the banner's 320, as the prototype sets it.
    outline(young_serif, read_font(young_serif, YOUNG_SERIF_SHA256, "Young Serif 3.003 from Google Fonts"),
            "Settee", 57.6, "Young Serif 3.003", art / "settee-wordmark.svg", "on the banner")
    # Caps 10px tall, near enough: mark.py scales the badge to fit each copy.
    outline(ALEGREYA_SANS_BOLD, read_font(ALEGREYA_SANS_BOLD, ALEGREYA_SANS_BOLD_SHA256, "Alegreya Sans Bold"),
            "DEBUG", 15, "Alegreya Sans Bold", art / "debug-badge.svg", "on the debug build's badge")


if __name__ == "__main__":
    main()
