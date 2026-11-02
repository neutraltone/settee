#!/usr/bin/env bash
# Renders the launcher icon PNGs (for API 24-25) and the TV banner from today's glyph. A stopgap until
# the rebrand (#83) replaces the artwork. Needs ImageMagick 7 and Inter SemiBold (SIL OFL), from
# https://github.com/rsms/inter/releases/tag/v4.1 (extras/ttf/Inter-SemiBold.ttf in the zip).
#
#   tools/render-launcher-art.sh path/to/Inter-SemiBold.ttf
set -euo pipefail

font=${1:?usage: render-launcher-art.sh path/to/Inter-SemiBold.ttf}
res="$(dirname "$0")/../app/src/main/res"

background='#07080C'
# The paths are ic_launcher_foreground.xml's, in its 108-unit viewport.
glyph="fill none stroke #4B5DB8 stroke-width 4 stroke-linecap round
  path 'M44,33 L54,42' path 'M64,33 L54,42'
  stroke none fill #7C93FF
  path 'M30,42 h48 a7,7 0 0 1 7,7 v26 a7,7 0 0 1 -7,7 h-48 a7,7 0 0 1 -7,-7 v-26 a7,7 0 0 1 7,-7 z'
  fill $background path 'M48,52 l16,10 l-16,10 z'"

# Drawn large and scaled down, so the edges are smooth. Only the corners are transparent: lint wants
# a launcher icon not to fill its square.
render_icon() { # density, size in px
  mkdir -p "$res/mipmap-$1"
  magick -size 1280x1280 xc:none -fill "$background" -draw 'roundrectangle 0,0 1279,1279 208,208' \
    -draw "scale 16,16 translate -14,-17.5 $glyph" \
    -resize "$2x$2" -strip "PNG32:$res/mipmap-$1/ic_launcher.png"
}

render_icon xhdpi 160
render_icon xxhdpi 240
render_icon xxxhdpi 320

# The launcher wants the banner opaque, so it stays RGB with no alpha channel.
magick -size 1280x720 "xc:$background" \
  -draw "translate 130,202 scale 6.2,6.2 translate -23,-31 $glyph" \
  -font "$font" -pointsize 196 -fill white -gravity West -annotate +550+0 'Settee' \
  -resize 320x180 -alpha off -strip -define png:color-type=2 "PNG24:$res/mipmap-xhdpi/banner.png"
