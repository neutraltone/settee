# Fire TV design and rendering constraints

Research for [#86](https://github.com/neutraltone/settee/issues/86), part of the map
[#83](https://github.com/neutraltone/settee/issues/83) ("Settee gets its own look"). Gathered October 2026
from Amazon's developer docs and Android's docs. Where a claim has no primary source it is marked
**unverified**.

## Answer in brief

- **Design for a 960 x 540 dp canvas at xhdpi (1920 x 1080 px)** and keep focused items and text out of
  the outer 5% of each edge (about 48 x 27 dp). Body text at least 14 sp. Focus must be visible at all
  times; every actionable element must be reachable by D-pad. Prefer cool, less saturated colours.
- **No Fire TV Stick that runs Settee has API 31**, so `RenderEffect` and Compose `Modifier.blur` are
  out: the newest sticks run Fire OS 8 (API 30), where `blur` is silently ignored. Any "frosted glass"
  look has to be faked (pre-blurred bitmaps, scrims, gradients).
- **The floor is a 1 GB, quad-core 1.7 GHz stick with an IMG GE8300 GPU** (Lite, 3rd gen,
  HD 2024, 4K 1st gen with 1.5 GB). Amazon's KPI for a foreground app playing 1080p is under 300 MB
  PSS, and a 60 fps UI has 16.7 ms per frame. Cheap: solid fills, small alpha scrims, scale/translate
  animations on `graphicsLayer`. Costly: full-screen translucent layers stacked over video, large soft
  shadows on many cards, full-resolution backdrops decoded at 4K.
- **The newest 2025/2026 sticks (4K Select, HD 2026, 4K 3rd gen 2026) run Vega OS, not Android**, and
  can't run Settee at all. They are not a design target.
- **Launcher assets:** a sideloaded app gets nothing from the Appstore, so the APK's own `android:icon`
  and `android:banner` are all the launcher has. Settee ships only an adaptive icon in
  `mipmap-anydpi-v26`, so on API 24-25 (Fire OS 6: Fire TV Stick 4K 1st gen, Basic Edition) there is
  no `ic_launcher` resource at all; a legacy PNG/vector fallback is needed. The Appstore (if ever used)
  wants a 1280 x 720 PNG Fire TV icon with no transparency.

## 1. Fire TV Stick hardware and Fire OS versions

From Amazon's [Fire TV Streaming Media Player specifications][specs] and the
[Fire OS overview][fireos]:

| Model (year) | OS (Android API) | SoC / CPU | GPU | RAM | OpenGL ES |
|---|---|---|---|---|---|
| Stick Basic Edition (2017) | Fire OS 6 (25)¹ | MediaTek 8127D, 4x 1.3 GHz | Mali-450 MP4 | 1 GB | 2.0 |
| Stick 4K 1st gen (2018) | Fire OS 6 (25) | MT8695, 4x 1.7 GHz | IMG GE8300 | 1.5 GB DDR4 | 3.2 |
| Stick Lite 1st gen (2020) | Fire OS 7 (28) | MT8695D, 4x 1.7 GHz | IMG GE8300 | 1 GB DDR4 | 3.2 |
| Stick 3rd gen (2020) | Fire OS 7 (28) | MT8695D, 4x 1.7 GHz | IMG GE8300 | 1 GB DDR4 | 3.2 |
| Stick 4K Max 1st gen (2021) | Fire OS 7 (28) | MT8696, 4x 1.8 GHz | IMG GE9215 750 MHz | 2 GB DDR4 | 3.2 |
| Stick 4K 2nd gen (2023) | Fire OS 8 (30) | MT8696D, 4x A55 1.7 GHz | GE9215 650 MHz | 2 GB LPDDR4 | 3.2 |
| Stick 4K Max 2nd gen (2023) | Fire OS 8 (30) | MT8696T, 4x A55 2.0 GHz | GE9215 850 MHz | 2 GB LPDDR4 | 3.2 |
| Stick HD (2024) | Fire OS 7 (28) | MT8695D, 4x 1.7 GHz | IMG GE8300 | 1 GB DDR4 | 3.2 |
| Stick 4K Plus (2025) | Fire OS 8 (30) | MT8696D, 4x A55 1.7 GHz | GE9215 650 MHz | 2 GB LPDDR4 | 3.2 |
| Stick 4K Select (2025) | **Vega OS 2.0** | MT8698, 4x A55 1.7 GHz | Mali G310 v2 | 1 GB | 3.1 |
| Stick HD (2026) | **Vega OS 2.0** | MT8698D, 4x A55 1.7 GHz | Mali G310 v2 | 1 GB | 3.1 |
| Stick 4K 3rd gen (2026) | **Vega OS 2.0** | MT8698E, 4x A55 1.7 GHz | Mali G310 v2 | 1 GB | 3.1 |

¹ The specs page lists the Basic Edition as Fire OS 5 (API 22); the Fire OS overview lists it under
Fire OS 6. Either way it is old and slow; Fire OS 5 is below Settee's minSdk 24. The 2nd-gen stick
(2016-2019) and 1st-gen stick (2014) are Fire OS 5 (API 22) and can't install Settee.

**Fire OS → Android mapping** ([Fire OS overview][fireos]): Fire OS 5 = Android 5.1 (22); Fire OS 6 =
7.1 (25); Fire OS 7 = 9 (28); Fire OS 8 = 10/11 (29/30); Fire OS 14 = 12-14 (31-34); Fire OS 16 =
15/16 (35/36). Fire OS 14 so far ships only on some 2025-2026 third-party Fire TV televisions; no Fire
TV Stick runs it.

**Vega OS** devices run React Native for Vega apps ([Vega overview][vega]); they do not run Android
APKs. Press reports confirm sideloading Android apps is not possible on them
([AFTVnews][aftv-vega]). Amazon has moved its new sticks to Vega, so the Android stick lineup that
Settee can target is effectively frozen at the table's Fire OS 6-8 rows.

**Practical floor:** design for the 1 GB / GE8300 / API 28 sticks (Lite, 3rd gen, HD 2024), which are
also the most numerous recent cheap sticks. The primary device (a Fire TV Stick, per CLAUDE.md) is in
this class or the GE9215 class.

## 2. Rendering limits and what they rule out

### Blur

- `android.graphics.RenderEffect` (which backs real-time blur) was added in API 31
  ([RenderEffect reference][rendereffect]).
- Compose `Modifier.blur`: "The blur effect is only supported on Android 12 and later. Attempts to use
  this modifier on older Android versions are ignored." ([Compose: customise an image][compose-blur])
- No Android Fire TV Stick has API 31 (section 1). **Real-time blur is ruled out** on every stick.
  Alternatives that work at API 24: a backdrop pre-blurred once on the CPU/at decode time and cached
  (costs memory, see bitmaps), a dark scrim/gradient over a dimmed image, or a low-resolution image
  scaled up (cheap pseudo-blur via bilinear filtering).

### Translucency and overdraw

- Amazon gives a 60 fps target, "a 16.67ms per frame budget", and notes the sticks are sealed HDMI
  enclosures with no active cooling, so sustained GPU load leads to thermal throttling
  ([Amazon blueprint: performance][amzn-perf]). It gives no overdraw or translucency limits.
- Alpha itself is cheap; the cost is fill rate. Every full-screen translucent layer is another full
  1920 x 1080 pass on a GE8300-class GPU, and `graphicsLayer { alpha = ... }` on a composable that
  overlaps itself renders it offscreen first (an extra buffer). A UI layered over live video (player
  overlays, EPG over a preview) already composites the video surface. **Keep translucent layers few and
  small: one scrim over video, not stacks of glass panels.** Measure with "Debug GPU overdraw" and
  `dumpsys gfxinfo` on the 1 GB stick, not the emulator.

### Shadows

- Compose `Modifier.shadow` uses platform elevation shadows (RenderNode). Coloured shadows
  (`ambientColor` / `spotColor`) need API 28 ([View.setOutlineSpotShadowColor][spotcolor]); on API
  24-25 they fall back to black.
- Elevation shadows are GPU-drawn per node; a handful (e.g. the focused card) is fine, but a large soft
  shadow on every card in a scrolling row multiplies fill cost. **Prefer shadow only on the focused
  item**, or a baked 9-patch/gradient glow. The repo already uses one 12 dp shadow on the VOD detail
  poster (`ui/vod/VodDetailScreens.kt`).

### Large bitmaps and memory

- Amazon's KPI: foreground memory (PSS total) under 300 MB while playing 1080p or lower, under 400 MB
  for 4K; background under 100 MB ([Measure KPIs for Fire TV apps][kpis]). On a 1 GB stick the system
  kills apps under pressure ([Amazon blueprint: performance][amzn-perf]).
- A 1920 x 1080 ARGB_8888 bitmap is 8.3 MB; a 3840 x 2160 one is 33 MB. The UI render surface is
  1920 x 1080 at xhdpi regardless of output resolution ([Display and Layout][layout]), so **never decode
  backdrops above 1080p**, and size posters to their on-screen dp size (Coil does this when given a
  size). Full-bleed hero backdrops per screen are affordable only one or two at a time.
- 4K-capable sticks still lay out UI at 960 x 540 dp; whether the UI framebuffer is 4K on 4K sticks is
  not stated in Amazon's docs (**unverified**), so 1080p assets are the right target.

### Animations

- Scale/translate/alpha on `graphicsLayer` (as `ChannelCard` in `HomeScreen.kt` already does) only
  re-composites the layer and is cheap. Animating size, padding or anything that relayouts a row, or
  animating a blur/shadow radius, is not.

## 3. Amazon's UI guidelines

From [Design and User Experience Guidelines][ux] and [Display and Layout][layout]:

- **Canvas:** design for 1920 x 1080 px, 320 dpi (xhdpi), which is 960 x 540 dp, screen size `large`.
  720p and 480p outputs keep the same render surface and dp size and are scaled. Orientation is always
  landscape. Resource qualifiers that match: `television`, `xhdpi`, `sw540dp`, `notouch`, `dpad`.
- **Overscan / safe area:** "avoid placing any of your app's UI elements within the outer 5% of any
  edge"; the focused item and text belong in the inner 90%. At 960 x 540 dp that is 48 dp left/right
  and 27 dp top/bottom. Users can also adjust overscan in Settings; Developer Options can show it.
  Backgrounds and video may bleed to the edge.
- **Text:** minimum 14 sp body text (about 28 px at 1080p). More line spacing than desktop; short
  lines and short descriptions. System font is Helvetica Neue (Fire OS's own UI; apps may use their
  own).
- **Colour:** TV screens are higher contrast with a narrower gamut, so use less saturated colours;
  cool hues (blue, purple, grey) work better than warm (red, orange). No contrast ratios are given.
- **Layout:** large, well-spaced elements; few menus/buttons/images per screen; most important first.
- **Focus:** "clearly show which element has focus at all times"; the user returning after looking
  away should still see where they are. Select should briefly show a selected state. In 1D lists focus
  may wrap from the last item to the first.
- **Navigation:** every actionable element must be reachable with the D-pad; Left from a content row
  returns to the global navigation menu.
- **Remote keys** ([Remote Control Input][remote]): Home and the microphone are system events apps
  can't intercept. Back (`KEYCODE_BACK`), Menu (`KEYCODE_MENU`), Select/D-pad, Play/Pause, Rewind and
  Fast Forward are delivered to the app; Rewind/FF may be missing on some remotes. Volume, power and
  app-launch buttons never reach the app.

Google's [Android TV design guidance][tv-icons] applies too, since Fire OS is Android; Amazon's UX
page links to it.

## 4. Launcher assets

### What Settee ships today

- `AndroidManifest.xml`: `android:icon="@mipmap/ic_launcher"`, `android:banner="@drawable/banner"`,
  both `LAUNCHER` and `LEANBACK_LAUNCHER` intent filters, leanback and touchscreen declared
  not-required.
- `res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`: adaptive icons, background
  `@color/window_background` (#07080C), foreground `drawable/ic_launcher_foreground.xml` (a vector TV
  with aerial in #7C93FF/#4B5DB8, kept inside the 66 dp centre of the 108 dp canvas).
- `res/drawable/banner.xml`: a 320 x 180 dp vector of the same TV glyph on #07080C, **no app name**,
  with a comment saying to replace it before a public release.
- **There are no `mipmap-<density>` or default `mipmap/` icons.** With minSdk 24, devices on API 24-25
  (Fire OS 6: Stick 4K 1st gen, Basic Edition, Fire TV Cube 1st gen) have no `ic_launcher` resource to
  resolve, so they show a generic icon or fail to load it. Adaptive icons need API 26
  ([TV app icon guidelines][tv-icons]).

### Requirements

- **Android TV / Fire OS (APK resources)** ([TV app icon guidelines][tv-icons]): `android:icon` and
  `android:banner` are both mandatory for TV. Banner is 16:9, 320 x 180 px at xhdpi (160 x 90 mdpi up
  to 640 x 360 xxxhdpi); for API 25 and lower an xhdpi 320 x 180 px resource is enough. **The banner
  must include the app name as text** (and a localised banner per language if desired). Launcher icon
  1:1, 160 x 160 px at xhdpi. Adaptive icons (API 26+) and an optional adaptive banner use two layers
  with a 72 x 72 dp safe zone; no borders, no cropping of the logo. Android TV does not theme icons, so
  no monochrome layer is needed.
- **Fire TV is xhdpi**, so xhdpi raster assets (or vectors) are what the device actually uses
  ([Display and Layout][layout]).
- **Amazon Appstore listing** ([Add Appstore details][appstore]): Fire TV App Icon 1280 x 720 px PNG,
  no transparency, key art inside the central 882 x 448 px (badges cover the rest), no borders or
  rounded corners, art to the edge. Fire TV background 1920 x 1080 JPG/24-bit PNG, subject in the
  central-right 1214 x 830 px. Screenshots 1920 x 1080, 3-10. Plus 114 x 114 and 512 x 512 PNG
  tablet icons.
- **Sideloaded apps** (how Settee is distributed: GitHub releases and the Downloader code): Amazon's
  docs don't describe where the Fire TV home screen gets a sideloaded app's tile. Community reports
  say it falls back to the APK's own resources and that these look low-resolution because Amazon has no
  hosted art for them ([AFTVnews][aftv-icons], **unverified** against Amazon docs). Whether the Fire OS
  launcher renders a vector `android:banner` as well as a PNG is also **unverified**; a 320 x 180 px
  `mipmap-xhdpi` PNG banner is the safe choice. Check on the stick: the "Your Apps" row and app
  library.

## Implications for the look (#83)

1. No blur, no glassmorphism that depends on live backdrop blur. Fake depth with scrims, gradients
   and pre-blurred/downscaled images.
2. Few translucent layers; shadow or glow on the focused item only; animate with `graphicsLayer`.
3. Lay out on 960 x 540 dp with 48/27 dp safe margins, 14 sp minimum body text, a focus state that
   is unmistakable from across the room, cool desaturated palette.
4. Images decoded at their display size, backdrops at most 1080p, keep the app well under 300 MB PSS
   during 1080p playback.
5. New launcher art should include: an adaptive icon plus legacy xhdpi (or `mipmap/` vector) fallback
   for API 24-25, and a 320 x 180 px banner with the word "Settee" in it.
6. The target device range is Fire OS 6-8 (API 25-30) and is not going to grow: new Amazon sticks run
   Vega OS.

[specs]: https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html
[fireos]: https://developer.amazon.com/docs/fire-tv/fire-os-overview.html
[vega]: https://developer.amazon.com/docs/vega/0.21/vega-overview.html
[aftv-vega]: https://www.aftvnews.com/these-are-the-fire-tvs-that-dont-support-sideloading-or-downloader-due-to-vega-os/
[rendereffect]: https://developer.android.com/reference/android/graphics/RenderEffect
[compose-blur]: https://developer.android.com/develop/ui/compose/graphics/images/customize
[spotcolor]: https://developer.android.com/reference/android/view/View#setOutlineSpotShadowColor(int)
[amzn-perf]: https://developer.amazon.com/apps-and-games/blogs/2026/07/blueprint-series-pt3-performance
[kpis]: https://developer.amazon.com/docs/app-testing/measure-kpis-fire-tv-apps.html
[layout]: https://developer.amazon.com/docs/fire-tv/display-and-layout.html
[ux]: https://developer.amazon.com/docs/fire-tv/design-and-user-experience-guidelines.html
[remote]: https://developer.amazon.com/docs/fire-tv/remote-input.html
[tv-icons]: https://developer.android.com/design/ui/tv/guides/system/tv-app-icon-guidelines
[appstore]: https://developer.amazon.com/docs/app-submission/appstore-details.html
[aftv-icons]: https://www.aftvnews.com/how-to-use-custom-icons-for-sideloaded-apps-like-kodi-on-the-amazon-fire-tv/
