#!/usr/bin/env bash
# Writes every copy of Settee's mark: tools/launcher-art/mark.py writes the vector drawables and the
# SVGs, then this renders the launcher icon PNGs (for API 24-25, which has no adaptive icons) and the
# TV banner from those SVGs, and the debug build's badged copies of both into its own source set
# (#244). The words are already outlined (outline_wordmark.py), so no font is needed. Needs python3,
# librsvg (rsvg-convert) and ImageMagick 7: ImageMagick's own SVG renderer draws the mark's curves
# badly, so it only post-processes. CI has neither, so the last step records the hashes of what it
# rendered from and to in launcher-art/rendered.sha256, which test_mark.py checks.
#
#   tools/render-launcher-art.sh
set -euo pipefail

here="$(dirname "$0")"
art="$here/launcher-art"
res="$here/../app/src/main/res"
debug_res="$here/../app/src/debug/res"

# The committed PNGs came from these versions. Others antialias a little differently, so the PNGs
# would change with no change to the artwork: don't commit that churn on its own.
rendered_with_rsvg='2.63.4'
rendered_with_magick='7.1.2-8'
rsvg=$(rsvg-convert --version | awk 'NR==1 {print $3}')
magick=$(magick -version | awk 'NR==1 {print $3}')
if [[ $rsvg != "$rendered_with_rsvg" || $magick != "$rendered_with_magick" ]]; then
  echo "warning: rendering with rsvg-convert $rsvg and ImageMagick $magick, not" \
    "$rendered_with_rsvg and $rendered_with_magick: unchanged art may still give new PNGs" >&2
fi

python3 "$art/mark.py"

# Only the tile's corners are transparent: lint wants a launcher icon not to fill its square.
render_icons() { # svg, res dir
  for density in xhdpi:160 xxhdpi:240 xxxhdpi:320; do
    mkdir -p "$2/mipmap-${density%:*}"
    rsvg-convert -w "${density#*:}" -h "${density#*:}" "$art/$1" |
      magick - -strip "PNG32:$2/mipmap-${density%:*}/ic_launcher.png"
  done
}

# The launcher wants the banner opaque, so it stays RGB with no alpha channel.
render_banner() { # svg, res dir
  mkdir -p "$2/mipmap-xhdpi"
  rsvg-convert -w 320 -h 180 "$art/$1" |
    magick - -alpha off -strip -define png:color-type=2 "PNG24:$2/mipmap-xhdpi/banner.png"
}

render_icons settee-icon.svg "$res"
render_icons settee-icon-debug.svg "$debug_res"
render_banner settee-banner.svg "$res"
render_banner settee-banner-debug.svg "$debug_res"

(
  cd "$here/.."
  shasum -a 256 tools/launcher-art/settee-{icon,banner}{,-debug}.svg \
    app/src/{main,debug}/res/mipmap-*/{ic_launcher,banner}.png
) >"$art/rendered.sha256"
