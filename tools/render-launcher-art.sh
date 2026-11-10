#!/usr/bin/env bash
# Writes every copy of Settee's mark: tools/launcher-art/mark.py writes the vector drawables and the
# SVGs, then this renders the launcher icon PNGs (for API 24-25, which has no adaptive icons) and the
# TV banner from those SVGs. The banner's "Settee" is already outlined (outline_wordmark.py), so no
# font is needed. Needs python3, librsvg (rsvg-convert) and ImageMagick 7: ImageMagick's own SVG
# renderer draws the mark's curves badly, so it only post-processes.
#
#   tools/render-launcher-art.sh
set -euo pipefail

here="$(dirname "$0")"
art="$here/launcher-art"
res="$here/../app/src/main/res"

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
render_icon() { # density, size in px
  mkdir -p "$res/mipmap-$1"
  rsvg-convert -w "$2" -h "$2" "$art/settee-icon.svg" |
    magick - -strip "PNG32:$res/mipmap-$1/ic_launcher.png"
}

render_icon xhdpi 160
render_icon xxhdpi 240
render_icon xxxhdpi 320

# The launcher wants the banner opaque, so it stays RGB with no alpha channel.
rsvg-convert -w 320 -h 180 "$art/settee-banner.svg" |
  magick - -alpha off -strip -define png:color-type=2 "PNG24:$res/mipmap-xhdpi/banner.png"
