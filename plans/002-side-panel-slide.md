# 002 — Side Panels slide in from the right edge

- **Status**: TODO
- **Issue**: neutraltone/settee#454
- **Commit**: 2d4c3a78
- **Severity**: MEDIUM
- **Category**: Missed opportunities / Physicality & origin
- **Estimated scope**: 1 new file (~45 lines), 2 edited files (~10 lines), Stick measurement
- **Depends on**: 001 if it has merged (reuse its `SetteeMotion.kt`). Otherwise this plan creates
  that file itself (step 1).

## Problem

Every question, choice and notice in Settee opens in a Side Panel down the right of the screen
(`docs/LOOK.md` → *Side Panel*). It appears in one frame: nothing on screen, then a 400 dp solid
column, with no hint of where it came from. The panel lives on the right edge, so it should come
from the right edge. That's spatial consistency, and it's what makes Back (which closes it) read
as putting it away.

There are two families, and they're drawn differently:

**1. `SidePanel`, `ConfirmPanel` and `NotesPanel`** (`ui/design/SidePanel.kt`, about 26 callers:
settings, options, cast, quality, errors…). Each is a `Dialog` window with no dim. The panel itself is
`PanelColumn`:

```kotlin
// app/src/main/java/app/opentv/ui/design/SidePanel.kt:89-103 (current)
@Composable
private fun PanelColumn(rows: FocusRequester, scrolls: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .width(SidePanelWidth)
            .fillMaxHeight()
            .background(SetteeColors.Surface)
            .then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = PANEL_PADDING, vertical = SetteeSpace.ScreenTopBottom)
            .focusRequester(rows)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        content = content,
    )
}
```

`SidePanelWidth = 400.dp` (`SidePanel.kt:255`).

**2. `PlayerChoicePanel`** (`ui/player/PlayerControlBar.kt:350`): Subtitles, Audio, Quality and
Aspect ratio over the live player (`PlayerScreen.kt:1018`), tracks over the film and episode player
(`ui/vod/VodPlayerScreen.kt:735`), and OpenSubtitles results (`ui/vod/OpenSubtitlesResults.kt:100`).
It's drawn in the player's own tree, not in a window, and is 360 dp wide:

```kotlin
// app/src/main/java/app/opentv/ui/player/PlayerControlBar.kt:361-375 (current)
    val start = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
    Column(
        modifier
            // Left to itself, Compose spends the first Back on taking focus off the row, so the
            // panel would stay up with nothing highlighted.
            .onPreviewKeyEvent { event ->
                val isBack = event.key == Key.Back || event.key == Key.Escape
                if (isBack && event.type == KeyEventType.KeyUp) onClose()
                isBack
            }
            .fillMaxHeight()
            .width(ChoicePanelWidth)
            .background(SetteeColors.Surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
            .padding(horizontal = 20.dp, vertical = SetteeSpace.ScreenTopBottom),
    ) {
```

## Target

When a panel opens, it slides in from the screen's end edge (right, or left in a right-to-left
language) by its own full width:

- **Property:** `graphicsLayer.translationX` only, from `+width` (just off screen) to `0`.
  Measured from the layer's own `size.width`, so the same code serves 400 dp and 360 dp.
- **Duration and curve:** 200 ms, `EaseDrawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)`, the
  iOS-style drawer curve: it moves fast at first and settles gently.
- **No fade.** The panel stays fully opaque while it moves. A see-through panel would draw what's
  under it through it, which `LOOK.md` forbids ("Never: blur, alpha-dimmed shelves, … two screens
  drawn see-through at once"), and it would cost an offscreen layer.
- **Closing is instant.** Back removes the panel in one frame, as today. It's the response to a
  press, and nothing should stand between Back and the screen underneath.
- **Presses go through.** The Highlight is placed on the panel's first frame, exactly as today
  (`SidePanelWindow` asks for focus after one `withFrameNanos`). Up, Down and OK act on the rows
  while the panel is still moving. Nothing waits for the slide to end.
- **Plays once per opening.** A panel whose content changes while it's open (a row ticking, an
  error replacing rows) doesn't slide again. The slide is keyed to the panel entering
  composition.
- **Remove animations:** Compose scales the tween by the system's animator duration scale, so at
  0 the panel just appears, as today.
- **If it misses the budget** (`LOOK.md` → *Motion and cost*: every frame after the first two
  under 16.7 ms on a release build on the Stick), it doesn't animate. Set the duration to 0,
  never longer or softer.

## Repo conventions to follow

- Motion values live in `app/src/main/java/app/opentv/ui/design/SetteeMotion.kt` (`object
  SetteeMotion`), added by plan 001. Add new values there, not in the panels.
- `ui/design` never imports `app.opentv.data` (`DesignBoundaryTest`).
- Values that change every frame are read only inside `Modifier.graphicsLayer { … }`, so a frame
  redraws the layer without recomposing or measuring. Exemplar: `ui/design/HighlightSurface.kt:156-158`
  (`graphicsLayer { scaleX = look.scale … }`).
- Every `.kt` file starts with the repo's GPL header block. Copy it from `ui/design/SetteeShapes.kt`.
- Comments explain *why* an obvious approach was rejected, not what the code does.

## Steps

1. **Motion values.** Open `app/src/main/java/app/opentv/ui/design/SetteeMotion.kt`.
   - If it exists (plan 001 merged), add inside `object SetteeMotion`:

     ```kotlin
     /** A panel coming in from an edge: fast off the mark, settling gently. */
     val EaseDrawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

     /** How long a Side Panel takes to slide in. It closes at once. */
     const val PANEL_SLIDE_MILLIS = 200
     ```

   - If it doesn't exist, create it (GPL header first) with exactly this:

     ```kotlin
     package app.opentv.ui.design

     import androidx.compose.animation.core.CubicBezierEasing

     /** Settee's motion values (docs/LOOK.md, Motion and cost). */
     object SetteeMotion {
         /** A panel coming in from an edge: fast off the mark, settling gently. */
         val EaseDrawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

         /** How long a Side Panel takes to slide in. It closes at once. */
         const val PANEL_SLIDE_MILLIS = 200
     }
     ```

2. **Create `app/src/main/java/app/opentv/ui/design/PanelSlide.kt`** (GPL header first):

   ```kotlin
   package app.opentv.ui.design

   import androidx.compose.animation.core.Animatable
   import androidx.compose.animation.core.tween
   import androidx.compose.runtime.Composable
   import androidx.compose.runtime.LaunchedEffect
   import androidx.compose.runtime.State
   import androidx.compose.runtime.remember
   import androidx.compose.ui.Modifier
   import androidx.compose.ui.graphics.graphicsLayer
   import androidx.compose.ui.platform.LocalLayoutDirection
   import androidx.compose.ui.unit.LayoutDirection

   /**
    * How far a Side Panel has come in, from 0 (just off the screen's end edge) to 1 (in place), run
    * once when the panel opens. Pair it with [slidesInFromEnd].
    */
   @Composable
   fun rememberPanelSlide(): State<Float> {
       val progress = remember { Animatable(0f) }
       LaunchedEffect(Unit) {
           progress.animateTo(1f, tween(SetteeMotion.PANEL_SLIDE_MILLIS, easing = SetteeMotion.EaseDrawer))
       }
       return progress.asState()
   }

   /*
    * Moved, never faded: a see-through panel would draw the screen under it through it, which LOOK.md
    * rules out and which costs an offscreen layer on a Stick. Moved in the draw phase, so the panel's
    * rows aren't measured again on every frame.
    */
   /** Slides the panel in from the screen's end edge by its own width as [progress] goes 0 to 1. */
   @Composable
   fun Modifier.slidesInFromEnd(progress: State<Float>): Modifier {
       val towardsEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
       return graphicsLayer { translationX = (1f - progress.value) * size.width * towardsEnd }
   }
   ```

   If detekt or Compose lint objects to a `@Composable` `Modifier` factory, replace
   `Modifier.slidesInFromEnd(progress)` with a plain (non-`@Composable`)
   `fun Modifier.slidesInFromEnd(progress: State<Float>, rtl: Boolean): Modifier` and read
   `LocalLayoutDirection.current == LayoutDirection.Rtl` at each call site. Don't suppress the rule.

3. **Slide the `SidePanel` family.** In `ui/design/SidePanel.kt`, `PanelColumn`:
   - Add `val slide = rememberPanelSlide()` as the first line of the function.
   - Add `.slidesInFromEnd(slide)` as the **first** modifier in the chain, before `.width(SidePanelWidth)`:

     ```kotlin
     Column(
         Modifier
             .slidesInFromEnd(slide)
             .width(SidePanelWidth)
             .fillMaxHeight()
             ...
     ```

   This covers `SidePanel`, `ConfirmPanel` and `NotesPanel`, which all draw through `PanelColumn`.
   Don't touch `SidePanelWindow`'s focus code.

4. **Slide the player's panels.** In `ui/player/PlayerControlBar.kt`, `PlayerChoicePanel`:
   - Add `val slide = rememberPanelSlide()` next to `val start = …`.
   - Put `.slidesInFromEnd(slide)` directly after `modifier` and before `.onPreviewKeyEvent`:

     ```kotlin
     Column(
         modifier
             .slidesInFromEnd(slide)
             // Left to itself, Compose spends the first Back on taking focus off the row, so the
             ...
     ```

   - Add the imports `app.opentv.ui.design.rememberPanelSlide` and `app.opentv.ui.design.slidesInFromEnd`.
   - Check every caller of `PlayerChoicePanel` (`grep -rn "PlayerChoicePanel(" app/src/main/java`)
     to see whether switching from one panel to another (say Subtitles → Audio) keeps the same
     `PlayerChoicePanel` in composition. If it does, the second panel won't slide. That's fine:
     don't add a `key(...)` to force it.

5. **Document it.** In `docs/LOOK.md` → *Side Panel*, after the first paragraph ("Every question,
   choice and notice opens in a Side Panel: … There are no dialogs in the middle of the screen."),
   add a bullet at the top of the list:

   ```markdown
   - **Slides in** from the right edge by its own width in 200 ms, never faded, with the Highlight
     already on its first row so presses don't wait. Back takes it away at once.
   ```

6. **Format.** Run `tools/check/check.py quick` (ktlintFormat + detekt) and fix what it reports.
   Don't add anything to `app/detekt-baseline.xml`.

## Boundaries

- Do NOT add an exit animation. Closing stays instant.
- Do NOT fade, scale or dim anything: no `alpha`, no `setDimAmount` change, no scrim.
- Do NOT touch `SidePanelWindow`'s `Dialog`, its `DialogProperties` or its focus requests.
- Do NOT animate the channel list (`ui/player/ChannelList.kt`), the rail, the Categories Sidebar,
  the Next Episode card or "Still watching". Each is a separate decision.
- Do NOT change the panels' widths, padding, insets or Back handling.
- Do NOT add dependencies.
- Do NOT run `./gradlew test`, `lintDebug` or the full suite locally (CLAUDE.md: CI runs them).
  `tools/check/check.py quick` is the only local Gradle run.
- If any current-code excerpt above doesn't match the file (drift since 2d4c3a78), STOP and report
  instead of improvising.

## Verification

- **Mechanical**:
  - `tools/check/check.py quick` exits 0.
  - Push and open the PR with the issue's number (`feat(#454): side panels slide in from the
    edge of the screen`). CI's **Test and assemble** must pass. The panel tests run with the
    test clock advancing automatically, so they see the panel in place. The two suites that stop
    the clock and touch a panel are `DatabaseGateTest` and `RecentSearchesScreenTest`. If either
    fails because a node isn't displayed, the panel is mid-slide at the moment of the assertion:
    advance the clock past 200 ms before that assertion. Don't change the motion to suit the test.
  - The PR's screenshot table (`screenshots.yml`) should show every panel screen unchanged.
    Screenshots are taken once the UI is idle, so the panel is in place.
- **Feel check** (on the Stick, then on an emulator for slow motion):
  - Open a panel from Settings, from a card's options (Menu), a confirmation (remove a provider →
    Keep is highlighted), the release notes (`NotesPanel`), and Subtitles in a player. Each comes in
    from the right edge and settles. None fades.
  - Press Down the instant the panel starts moving. The Highlight moves to the next row, and the
    press isn't lost.
  - Press Back straight after opening. The panel is gone in one frame, with no slide out.
  - **Double animation check:** on an emulator, `adb shell settings put global animator_duration_scale 10`
    and open a `SidePanel`. There must be exactly one motion, the slide. If the `Dialog` window
    also fades or zooms in (a platform window animation), report it and STOP: it needs
    `window.setWindowAnimations(0)` next to `setDimAmount(0f)` in `SidePanelWindow`, which is
    outside this plan's boundaries until the owner agrees. Reset with
    `adb shell settings put global animator_duration_scale 1`.
  - At 10×, check the first frame: the panel must not flash in place before it jumps off screen
    and slides.
  - Remove animations (`animator_duration_scale 0`): panels appear in one frame, as before.
  - Right-to-left: switch the emulator to Arabic or Hebrew. Panels sit on the left and slide in
    from the left edge.
  - Over a playing channel: the Subtitles panel slides over the picture without the video
    stuttering.
- **Budget** (release build, `uk.neutraltone.settee.pr`, on the Stick): for a Settings panel, a
  confirmation and the live player's Subtitles panel, one at a time:
  1. `adb shell dumpsys gfxinfo uk.neutraltone.settee.pr reset`
  2. Open the panel, wait 1 s.
  3. `adb shell dumpsys gfxinfo uk.neutraltone.settee.pr framestats > slide-<panel>.txt`

  Leave out the first two frames after the press. Every other frame must be under 16.7 ms. Put the
  median and worst frame for each in the PR description. **If one misses:** set
  `PANEL_SLIDE_MILLIS = 0`, say so in the PR with the numbers, and change the `LOOK.md` bullet from
  step 5 to say panels appear at once and why.
- **Done when**: every Side Panel and player choice panel slides in from its edge in 200 ms
  without fading, presses during the slide land, Back closes at once, CI is green, and the Stick
  numbers are in the PR (or the slide is off, with the numbers that justify it).
