#!/usr/bin/env python3
"""Outlines "Settee" in Young Serif 3.003, kerned, into settee-wordmark.svg (#157).

Run only when the wordmark changes: its output is committed, so mark.py and the PNG render need
no font. Young Serif is pinned at 3.003, the exact Google Fonts file (see docs/LOOK.md), because
upstream has since redrawn it much thinner; the hash check refuses any other file.

Needs fontTools and uharfbuzz (pip install fonttools uharfbuzz) and the font from
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

SHA256 = "42bb2602364b28569fdce5eeb6ab3d81cf8090ba81b211f1229ed69f33155523"
WORD = "Settee"
SIZE = 57.6  # px: 18% of the banner's 320, as the prototype sets it


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def main():
    font_path = Path(sys.argv[1])
    data = font_path.read_bytes()
    if hashlib.sha256(data).hexdigest() != SHA256:
        sys.exit(f"{font_path} isn't Young Serif 3.003 from Google Fonts (sha256 {SHA256})")

    # HarfBuzz applies the font's kerning, as the browser did in the prototype.
    face = hb.Face(data)
    hb_font = hb.Font(face)
    buf = hb.Buffer()
    buf.add_str(WORD)
    buf.guess_segment_properties()
    hb.shape(hb_font, buf, {"kern": True, "liga": False})

    font = TTFont(font_path)
    glyphs = font.getGlyphSet()
    order = font.getGlyphOrder()
    k = SIZE / font["head"].unitsPerEm
    pen = SVGPathPen(glyphs, ntos=fmt)
    bounds = BoundsPen(glyphs)
    x = 0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        name = order[info.codepoint]
        ox, oy = x + pos.x_offset, pos.y_offset
        glyphs[name].draw(TransformPen(pen, (k, 0, 0, -k, ox * k, -oy * k)))
        glyphs[name].draw(TransformPen(bounds, (1, 0, 0, 1, ox, oy)))
        x += pos.x_advance
    _, ymin, _, ymax = bounds.bounds
    advance = x * k
    top, height = -ymax * k, (ymax - ymin) * k

    out = Path(__file__).resolve().parent / "settee-wordmark.svg"
    out.write_text(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 {fmt(top)} {fmt(advance)} {fmt(height)}" '
        f'data-advance="{fmt(advance)}">\n'
        f'  <!-- "{WORD}" in Young Serif 3.003 (SIL OFL), kerned and outlined at {SIZE}px by\n'
        "       outline_wordmark.py, baseline at y=0. mark.py places it on the banner. -->\n"
        f'  <path d="{pen.getCommands()}"/>\n'
        "</svg>\n")


if __name__ == "__main__":
    main()
