# Compose for TV vs Material 3 for a TV design system, and what costs frames on a Fire TV Stick

Research for [#85](https://github.com/neutraltone/settee/issues/85), part of the map
[#83 "Settee gets its own look"](https://github.com/neutraltone/settee/issues/83). Researched 2026-10-29.

## Answer in brief

- **tv-material is a focus-state toolkit, not a look.** What it adds over Material 3 is a focus model
  built into every component: each clickable `Surface` (and the Button, Card, Chip, ListItem,
  NavigationDrawerItem, Tab built on it) takes per-state **scale, border, glow, shape, colour**
  (`focused`, `pressed`, `selected`, `disabled`). Material 3 mobile components show focus only as a 10%
  state layer, which is invisible at ten feet. Settee already hand-rolls the focus look on Material 3
  (about 30 `onFocusChanged` sites).
- **It is stable but small, and the shiny parts are gone.** `tv-material` 1.1.0 and `tv-foundation`
  1.0.0 went stable on 2026-05-06. `ImmersiveList` was removed (1.0.0-beta01), the TV lazy layouts were
  deprecated and then deleted in favour of plain `LazyRow`/`LazyColumn` with `BringIntoViewSpec`, and
  `Carousel` and all Chips are still `@ExperimentalTvMaterial3Api`. Google's direction is "fold TV
  behaviour into core Compose foundation, keep tv-material as a thin component layer."
- **Settee depends on tv-material but imports nothing from it** (0 `androidx.tv` imports;
  `tv-foundation` is in the version catalog but not a dependency). Everything on screen is
  `androidx.compose.material3` (36 files).
- **On a Fire TV Stick (API 28, 1 GB, PowerVR GE8300) the frame killers are:** `Modifier.blur` (silently
  a no-op below API 31, so it can't be part of the look), alpha < 1 on layers (forces an offscreen
  buffer), large or animated shadows/glows, animating *layout* (widths, sizes) instead of
  `graphicsLayer`, reading animated state during composition, and oversized image decodes in shelves.
- **Recommendation:** build Settee's design system as its **own thin layer on Compose foundation +
  Material 3 theming tokens**, borrowing tv-material's *ideas* (a per-state `scale/border/glow/shape`
  focus spec) rather than its components. Drop the unused `tv-material` dependency, or adopt it whole;
  don't mix the two. Details and reasons below.

## 1. What tv-material offers over Material 3

### Components

The public API of `androidx.tv:tv-material` (from `tv/tv-material/api/current.txt` on androidx-main)
has these composables: `Surface`, `Button`, `OutlinedButton`, `WideButton`, `IconButton`,
`OutlinedIconButton`, `Card`, `ClassicCard`, `CompactCard`, `WideClassicCard`,
`StandardCardContainer`, `WideCardContainer`, `ListItem`, `DenseListItem`, `NavigationDrawer`,
`ModalNavigationDrawer`, `NavigationDrawerItem`, `Tab`, `TabRow`, `Carousel`, `Checkbox`,
`TriStateCheckbox`, `RadioButton`, `Switch`, the four Chips, `Text`, `Icon`, and its own
`MaterialTheme`/`ColorScheme`/`Typography`/`Shapes`.
Source: [androidx tv-material api/current.txt](https://github.com/androidx/androidx/blob/androidx-main/tv/tv-material/api/current.txt).

Notable absences compared with Material 3: no text field, dialog, slider, progress indicators, snackbar
or top app bar. Settee uses `OutlinedTextField`, `AlertDialog`, `Slider`, `LinearProgressIndicator` and
`CircularProgressIndicator` (grep below), so a pure tv-material app is not possible; mixing is
unavoidable if tv-material is adopted.

### The focus model (the real value)

Every interactive tv-material component is built on a clickable/selectable `Surface` that takes
state-keyed styling objects:

| Parameter | What it does | Default |
| --- | --- | --- |
| `scale: ClickableSurfaceScale` | animated scale per state | `focusedScale = 1.1f` (`SurfaceDefaults.kt`) |
| `border: ClickableSurfaceBorder` | border per state | none unless set |
| `glow: ClickableSurfaceGlow` | diffused coloured shadow (`Glow(elevationColor, elevation)`) | `Glow.None` |
| `shape: ClickableSurfaceShape` | shape per state | |
| `colors: ClickableSurfaceColors` | container/content colour per state | |

Source: [Surface.kt](https://github.com/androidx/androidx/blob/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/Surface.kt),
[SurfaceDefaults.kt](https://github.com/androidx/androidx/blob/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/SurfaceDefaults.kt),
[Glow.kt](https://github.com/androidx/androidx/blob/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/Glow.kt).

Implementation details that matter for a low-end stick
([SurfaceImpl.kt](https://github.com/androidx/androidx/blob/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/SurfaceImpl.kt),
[SurfaceScale.kt](https://github.com/androidx/androidx/blob/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/SurfaceScale.kt),
[SurfaceGlow.kt](https://github.com/androidx/androidx/blob/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/SurfaceGlow.kt)):

- **Glow is `Paint.setShadowLayer`** drawn behind the shape, and is only applied on API 28+
  (`ifElse(API_28_OR_ABOVE, Modifier.tvSurfaceGlow(...))`; the KDoc says "glow is disabled for API levels
  below 28 as it is not supported by the underlying OS"). So it works on Fire OS 7/8 sticks but not on
  the Fire TV Stick 4K 1st gen (Fire OS 6, API 25).
- **Scale uses the parameter form** `graphicsLayer(scaleX = animatedScale, scaleY = animatedScale)`, so
  the animated value is read in composition and the Surface recomposes on every frame of the focus
  animation. The Compose docs say to "prefer the lambda version of this modifier when performing
  animations" ([Graphics modifiers](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers)).
  Settee's own `ChannelCard` already does it the cheaper way (`graphicsLayer { scaleX = scale }`).
- Each Surface also animates its `zIndex` on focus (so the glow isn't overlapped by the next item in a
  row) and wraps content in two `graphicsLayer`s (shape clip; disabled alpha).
- Default focus scale is 1.1, the top of Google's TV design range of 1.025 / 1.05 / 1.1, which "can vary
  based on their size" ([TV focus system](https://developer.android.com/design/ui/tv/guides/styles/focus-system)).
  The same page puts glow at 2-32 dp and tonal elevation at +1 to +5.

By contrast, Material 3 mobile components indicate focus with a state layer at
`FocusStateLayerOpacity = 0.1f`
([StateTokens.kt](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/StateTokens.kt)):
no scale, no border, no glow. Google's own words: the mobile Material library "is not optimized for the
unique style of interactions on Android TV", and "mixing Compose Material with Compose Material from
Compose for TV can result in unexpected behavior" because each has its own `MaterialTheme`
([Use Jetpack Compose on Android TV](https://developer.android.com/training/tv/playback/compose)).

### Larger patterns

| Pattern | Status | Source |
| --- | --- | --- |
| `ImmersiveList` (hero backdrop that follows the focused card) | **Removed** in tv-material 1.0.0-beta01 (2024-05-01); Google points to a sample to build it yourself | [Release notes](https://developer.android.com/jetpack/androidx/releases/tv) |
| `TvLazyRow` / `TvLazyColumn` / TV grids, `pivotOffsets` | Deprecated in tv-foundation 1.0.0-alpha11 (2024-07-10), removed in alpha12; use foundation `LazyRow`/`LazyColumn` (1.7+) and `BringIntoViewSpec` via `LocalBringIntoViewSpec` | [Release notes](https://developer.android.com/jetpack/androidx/releases/tv), [Create scrollable layouts](https://developer.android.com/training/tv/playback/compose/lists) |
| `Carousel` (featured hero) | Still `@ExperimentalTvMaterial3Api`; "some carousel features have been dropped for the beta launch due to necessary APIs being experimental" | `current.txt`, release notes |
| Chips (Assist/Filter/Input/Suggestion) | Still experimental | `current.txt` |
| `NavigationDrawer` / `ModalNavigationDrawer` | Stable since 1.0.0-beta01. Width change is `animateContentSize` (a layout animation) | [NavigationDrawer.kt](https://github.com/androidx/androidx/blob/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3/NavigationDrawer.kt) |
| Focus `onEnter`/`onExit`, `focusRestorer`, `focusGroup` | Now in core Compose UI/foundation, not TV libraries | release notes (`FocusProperties.enter/exit` → `onEnter/onExit`) |

### Stability and direction

- Current: `androidx.tv:tv-material:1.1.0` and `androidx.tv:tv-foundation:1.0.0`, both stable on
  2026-05-06. Settee pins `tvCompose = "1.1.0"`, i.e. current.
- tv-material 1.0.1 (2025-07-16) fixed `NavigationDrawerItem` height and "moved compositing strategy to
  `Text`, which fixed ExoPlayer rendering on lower API levels". Relevant to Settee if adopted: a
  tv-material `Text` over a `PlayerSurface` behaved badly before 1.0.1.
- Cadence is slow: 1.1.0 took 16 months from alpha01 (2025-01) to stable. 33 API entries still carry
  `@ExperimentalTvMaterial3Api`.
- Direction, inferred from the release history rather than a stated roadmap (the release page states
  none): TV-specific *infrastructure* (lazy layouts, pivots, focus enter/exit) has moved into core
  Compose, and *opinionated layouts* (ImmersiveList, parts of Carousel) were dropped. What remains in
  tv-material is a set of Surface-based components with a TV focus spec. The overview page still tells
  new apps to use tv-material "instead of" material3.

## 2. How Settee uses each library today

From a grep of `app/src/main` on `main` (68deb72):

- **tv-material:** declared (`implementation(libs.androidx.tv.material)` in `app/build.gradle.kts`),
  **zero imports**. `tv-foundation` 1.0.0 is in `gradle/libs.versions.toml` but not wired as a
  dependency. Both are dead weight today.
- **Material 3:** 36 files. Most used: `Text` (35 files), `MaterialTheme` (34), `Icon` (17),
  `OutlinedButton`/`Button` (14 each), `CircularProgressIndicator` (12), `TextButton` (9), `Card` (8),
  `OutlinedTextField`, `LinearProgressIndicator`, `IconButton` (7), `Switch` (6), `AlertDialog` (3),
  plus one each of `Surface`, `Slider`, `RadioButton`, `FilterChip`.
- **Theme** (`ui/theme/Theme.kt`): M3 `darkColorScheme`/`lightColorScheme` with a single blue accent
  and a scaled-up `Typography`; no shapes, no focus tokens, no spacing tokens.
- **Focus visuals are hand-rolled** per screen: `var focused` + `Modifier.onFocusChanged` in ~30
  places across 18 files, each choosing its own border/colour/scale. Only `HomeScreen.ChannelCard`
  animates scale (1.06, via the lambda `graphicsLayer`, which is the cheap form).
- **Already-tuned perf choices:** Coil loader with 25% memory cache, 256 MB disk cache,
  `crossfade(false)` and `allowRgb565(true)` (`OpenTvApp.kt`); lazy lists mostly pass `key =`
  (24 of 31 lazy layouts). No `blur`, no animated alpha.
- **Watch items:** `VodDetailScreens` draws a static `shadow(12.dp)` under the poster (fine, it doesn't
  animate); the `MainScreen` rail animates its **width** with `animateDpAsState`, which re-measures the
  rail and the content beside it every frame of the expand.
- No baseline profile module.

## 3. What drops frames on a low-end stick, with mitigations

### The target hardware

From [Amazon's Fire TV streaming media player specs](https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html):

| Device | RAM | GPU | OS / API |
| --- | --- | --- | --- |
| Fire TV Stick 3rd gen (2020), Lite, HD (2024) | 1 GB | IMG GE8300 | Fire OS 7 / API 28 |
| Fire TV Stick 4K 1st gen (2018) | 1.5 GB | IMG GE8300 | Fire OS 6 / API 25 |
| Fire TV Stick 4K Max 1st gen (2021) | 2 GB | IMG GE9215 | Fire OS 7 / API 28 |
| Fire TV Stick 4K / 4K Max 2nd gen (2023) | 2 GB | GE9215 | Fire OS 8 / API 30 |
| Fire TV Stick HD (2026), 4K Select (2025), 4K 3rd gen (2026) | 1 GB | ARM G310 | **Vega OS** (not Android; Settee can't run) |

So the floor to design for is **API 28, 1 GB RAM, a GE8300**, and no Android Fire TV stick is on API 31+.
Google's TV memory guide budgets a 1 GB device at **30-40 MB of graphics memory** and at most 200 MB
for Anon+Swap+Graphics, and warns that memory pressure causes direct reclaim, "potentially causing
audio and video frame drops and UI glitches", on any thread including main
([Optimize memory usage](https://developer.android.com/training/tv/playback/memory)).

### Patterns, ranked by risk

| Pattern | Why it costs | Mitigation |
| --- | --- | --- |
| **`Modifier.blur` / RenderEffect** (frosted panels, blurred backdrops) | Only supported on Android 12+; "attempts to use this Modifier on older Android versions will be ignored" ([Blur.kt](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/draw/Blur.kt)). On API 31+ a RenderEffect always forces an offscreen buffer. | Never part of the design language. Fake depth with a **scrim gradient** over the backdrop, or a pre-blurred low-res image (decode at ~1/8 size and let it upscale). |
| **Alpha < 1 on a layer** (fading cards, dimmed unfocused rows, animated alpha) | With the default strategy the whole layer is drawn to an offscreen buffer ([Graphics modifiers](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers)). Fill-rate is the GE8300's weak spot at 1080p. | Prefer colour changes (`onSurface` at a lower-contrast token) over alpha. If alpha is needed on non-overlapping content, `CompositingStrategy.ModulateAlpha`. Don't dim a whole shelf with alpha. |
| **Large or animated shadows / glows** | `shadow()` and tv-material's glow are blurred shadow layers (setShadowLayer is hardware-accelerated for shapes only from API 28). Animating elevation re-renders the blur every frame; a big radius on many cards multiplies it. | Glow only on the **one focused item**, small radius (Google's range starts at 2 dp), and switch it on rather than animating radius. Static shadows on a single hero element are fine. A border or outline is close to free and reads better at ten feet. |
| **Animating layout** (rail width, `animateContentSize`, card size on focus) | Re-measures and re-places the subtree (and siblings) every frame. | Animate `graphicsLayer { scaleX/translationX }` instead. For the rail: overlay the expanded rail over content (translate, don't resize), or snap the width. |
| **Reading animated state in composition** | Recomposes per frame; docs: "prefer lambda-based modifiers when you pass in frequently changing state" ([Compose phases](https://developer.android.com/develop/ui/compose/performance/phases), [best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)). tv-material's own `tvSurfaceScale` does this. | Read in `graphicsLayer {}`, `offset {}`, `drawBehind {}`. Keep one focus-state boolean per item; derive the rest in draw. |
| **Recomposition on focus that escapes the item** | Hoisting "focused item" into screen state (e.g. to drive a hero backdrop) recomposes the whole screen on each D-pad press. | Keep focus state inside the item; publish the focused id to a backdrop via a small dedicated state read only by the backdrop composable; debounce backdrop image swaps (~150-300 ms) so fast scrolling doesn't decode every poster. |
| **Image decoding in lazy rows** | Full-size poster/backdrop decodes blow the 30-40 MB graphics budget and cause GC/reclaim stutter; the guide says "don't load images with higher resolution than the device UI resolution" and "avoid loading placeholder images", and recommends hardware bitmaps. | Already partly done (Coil caches, RGB_565, no crossfade). Keep: decode at the rendered size (Coil sizes from constraints; give tiles fixed sizes), keep hardware bitmaps on (Coil default on API 26+), colour placeholders not image placeholders, stable `key`s and `contentType` on every lazy list. |
| **Nested lazy rows in a lazy column** | Composing a new row on scroll can exceed a frame on slow CPUs. | Upgrade-only fix: Compose 1.10 turned on "pausable composition in lazy prefetch" by default, which lets composition pause and resume next frame "to significantly reduce jank" ([Compose December '25](https://android-developers.googleblog.com/2025/12/whats-new-in-jetpack-compose-december.html)); Settee's BOM 2026.09.00 has it. Keep row items cheap; hoist `CompositionLocal` reads out of items. |
| **Cold-start and first-scroll jank** | JIT before ART compiles hot paths. | A baseline profile; Google's TV onboarding FAQ points to baseline profiles as the main performance lever, with the JetStream sample as the reference ([tv/onboarding.md](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-javascriptengine-release/tv/onboarding.md)). |
| **Focus scale itself** | Cheap when done as a `graphicsLayer` transform (no re-raster of content unless the layer is invalidated). Text scaled by a layer blurs slightly. | Keep scale ≤ 1.05-1.1 on cards and 1.0 on text rows/buttons (use colour + border for those). |

## 4. Recommendation for #83

1. **Don't adopt tv-material components wholesale.** It can't cover Settee's screens (no text field,
   dialog, slider or progress), so the app would run two `MaterialTheme`s, which Google warns against.
   Its signature effect (glow) is a shadow layer, and its scale reads animated state in composition.
   What's valuable is the *shape of the API*: one per-state focus spec.
2. **Build `SetteeTheme` on Compose foundation, keeping M3 `MaterialTheme` as the token carrier** for
   the M3 widgets Settee still needs (text fields, dialogs, sliders). Add Settee's own tokens
   (focus spec, spacing, shapes, elevation-as-colour) via `CompositionLocal`s, and one
   `Modifier.setteeFocus(spec)` / `FocusSurface` that replaces the ~30 hand-rolled `onFocusChanged`
   blocks. Implement scale/translate in `graphicsLayer {}` lambdas, border via `drawWithContent`/`border`,
   and glow as an opt-in, single-item, non-animated effect.
3. **Design-language guardrails for a 1 GB API 28 stick:** no blur, no alpha-dimmed shelves, no
   animated layout, one glow at a time, colour/border-led focus, scrims instead of frosted glass, and
   images decoded at tile size.
4. **Remove the unused `tv-material` dependency** (and the `tv-foundation` catalog entry) unless the
   design system decides to wrap it. Revisit only if Google stabilises `Carousel` and the decision is
   to use it.
5. **Measure before and after**: add a baseline profile and check frame times on the 1 GB stick
   (`adb shell dumpsys gfxinfo uk.neutraltone.settee.debug` / Macrobenchmark), since the real
   numbers for this GPU are not published anywhere primary.

## Sources

- [Compose for TV release notes](https://developer.android.com/jetpack/androidx/releases/tv)
- [Use Jetpack Compose on Android TV](https://developer.android.com/training/tv/playback/compose)
- [Create scrollable layouts (TV)](https://developer.android.com/training/tv/playback/compose/lists)
- [TV focus system design guide](https://developer.android.com/design/ui/tv/guides/styles/focus-system)
- [Optimize memory usage (TV)](https://developer.android.com/training/tv/playback/memory)
- [Compose graphics modifiers](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers)
- [Compose phases](https://developer.android.com/develop/ui/compose/performance/phases) and [performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)
- [What's new in Jetpack Compose, December '25](https://android-developers.googleblog.com/2025/12/whats-new-in-jetpack-compose-december.html)
- [Compose for TV onboarding FAQ](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-javascriptengine-release/tv/onboarding.md)
- [Fire TV streaming media player specifications](https://developer.amazon.com/docs/device-specs/device-specifications-fire-tv-streaming-media-player.html)
- androidx source on `androidx-main`: [tv-material](https://github.com/androidx/androidx/tree/androidx-main/tv/tv-material/src/main/java/androidx/tv/material3) (`Surface.kt`, `SurfaceImpl.kt`, `SurfaceScale.kt`, `SurfaceGlow.kt`, `SurfaceDefaults.kt`, `Glow.kt`, `NavigationDrawer.kt`, `api/current.txt`), [Blur.kt](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/draw/Blur.kt), [material3 StateTokens.kt](https://github.com/androidx/androidx/blob/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/StateTokens.kt)
