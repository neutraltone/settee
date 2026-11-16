# 005 — "Still watching" fades the picture down instead of cutting to dark

- **Status**: TODO
- **Issue**: neutraltone/settee#457
- **Commit**: 2d4c3a78
- **Severity**: LOW
- **Category**: Missed opportunities / Preventing a jarring change
- **Estimated scope**: 1 edited file (~12 lines), plus `SetteeMotion.kt` if it doesn't have `EaseOut` yet
- **Depends on**: nothing. Touches the same file as plan 003 (#455), `ui/vod/NextEpisodeOverlay.kt`,
  but a different function. If both are open at once, merge carefully rather than overwrite.

## Problem

After three episodes in a row play with nobody touching the remote, the player pauses and asks
"Are you still watching?", with Keep watching (highlighted) and Stop. The picture is covered by an
80% dark dim **in one frame**. The person it's for is most likely half-asleep in front of the TV,
and the room suddenly going dark is the most jarring way to ask.

```kotlin
// app/src/main/java/app/opentv/ui/vod/NextEpisodeOverlay.kt:165-184 (current)
/** "Are you still watching?" centred over a dimmed picture, with Keep watching highlighted. */
@Composable
private fun StillWatchingPrompt(onContinue: () -> Unit, onStop: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Dim), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 560.dp)) {
            Text(stringResource(R.string.player_still_watching), SetteeType.ScreenTitle)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OverlayButton(
                    stringResource(R.string.player_continue_watching),
                    onContinue,
                    Modifier.focusRequester(focus),
                )
                OverlayButton(stringResource(R.string.player_stop), onStop)
            }
        }
    }
}
```

```kotlin
// app/src/main/java/app/opentv/ui/vod/NextEpisodeOverlay.kt (bottom of file, current)
// A plain dim over the picture: LOOK.md rules out blur.
private val Dim = SetteeColors.Background.copy(alpha = 0.8f)
```

The prompt is chosen by `is Overlay.StillWatching ->` in `NextEpisodeOverlay`
(`NextEpisodeOverlay.kt:95-98`). `Overlay.StillWatching` is a
`data class StillWatching(val streamStopped: Boolean)` (`player/NextEpisodeCountdown.kt:34`). When
the provider connection is let go, a new instance with `streamStopped = true` arrives, but the
prompt stays in composition, since it's the same branch in the same place. So anything keyed to
entering composition runs once per appearance.

## Target

When the prompt appears, the dim and the prompt fade up together:

- **Property:** `graphicsLayer` `alpha` 0 → 1 on the whole prompt `Box`, so the dim and the text and
  buttons fade as one.
- **No offscreen buffer:** set `compositingStrategy = CompositingStrategy.ModulateAlpha`. The
  default (`Auto`) would render the full-screen box into an offscreen buffer while alpha is below 1,
  which is a 1080p buffer on a Stick. `ModulateAlpha` multiplies each draw's alpha instead. The
  text is drawn over the dim, so mid-fade the text is very slightly see-through against it, which
  is invisible at this speed. Note this in a comment.
- **Duration and curve:** 300 ms, `EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)`.
- **No movement.** Fade only: there's nothing to come from, and the prompt is centred like a modal.
- **The Highlight is on Keep watching from the first frame,** as today. OK during the fade
  carries on at once.
- **Once per appearance.** The `streamStopped` change must not restart it.
- **Leaving is instant,** as today: Keep watching removes the prompt in one frame, Stop leaves the
  player, and Back stops.
- **Remove animations:** Compose scales the tween by the system's animator duration scale, so at
  0 the prompt appears at once, as today.
- **If it misses the budget** (`LOOK.md` → *Motion and cost*: every frame under 16.7 ms on a release
  build on the Stick; it isn't a response to a press, so every frame counts), it doesn't animate.
  Set the duration to 0, never longer or softer.

## Repo conventions to follow

- Motion values live in `app/src/main/java/app/opentv/ui/design/SetteeMotion.kt`
  (`object SetteeMotion`), shared with plans 001-004.
- An `Animatable` run once from a `LaunchedEffect` is how this repo already fades things in.
  Exemplar: `ui/vod/RestingBackdrop.kt:80-87`.
- Per-frame values are read only inside `Modifier.graphicsLayer { … }`. Exemplar:
  `ui/design/HighlightSurface.kt:156-158`.
- `LOOK.md` rules out blur and see-through screens. A dim was already decided for this prompt (the
  `Dim` comment), and this plan only fades it in.
- Comments explain *why* an obvious approach was rejected, not what the code does.

## Steps

1. **`EaseOut` in `SetteeMotion`.** Open `app/src/main/java/app/opentv/ui/design/SetteeMotion.kt`.
   - If it already has `val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)`, use it.
   - If the file exists without it, add inside `object SetteeMotion`:

     ```kotlin
     /** Starts fast and settles, so the response to a press shows in its first frames. */
     val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
     ```

   - If the file doesn't exist, create it with the GPL header (copy from `ui/design/SetteeShapes.kt`)
     and:

     ```kotlin
     package app.opentv.ui.design

     import androidx.compose.animation.core.CubicBezierEasing

     /** Settee's motion values (docs/LOOK.md, Motion and cost). */
     object SetteeMotion {
         /** Starts fast and settles, so the response to a press shows in its first frames. */
         val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
     }
     ```

2. **Constant.** At the bottom of `ui/vod/NextEpisodeOverlay.kt`, next to `Dim`, add:

   ```kotlin
   private const val STILL_WATCHING_FADE_MILLIS = 300
   ```

   (If plan 003 has landed, `CARD_ENTER_MILLIS` and `CARD_RISE` are already near
   `CARD_WIDTH`. Leave them alone.)

3. **Run the fade.** In `StillWatchingPrompt`, after the existing
   `LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }`, add:

   ```kotlin
   // Whoever this is for is likely half-asleep: the room going dark in one frame is the harshest way
   // to ask. Keyed to the prompt arriving, not to the stream being let go, which changes it in place.
   val fade = remember { Animatable(0f) }
   LaunchedEffect(Unit) { fade.animateTo(1f, tween(STILL_WATCHING_FADE_MILLIS, easing = SetteeMotion.EaseOut)) }
   ```

   Leave the focus `LaunchedEffect` exactly as it is, and leave it first.

4. **Apply it.** Change the `Box` line from

   ```kotlin
   Box(Modifier.fillMaxSize().background(Dim), contentAlignment = Alignment.Center) {
   ```

   to

   ```kotlin
   Box(
       Modifier
           .fillMaxSize()
           .graphicsLayer {
               alpha = fade.value
               // Each draw faded on its own, not the whole screen drawn offscreen and faded: that
               // would be a full-screen buffer on a Stick. The words sit on the dim, so mid-fade
               // they're a touch see-through against it, which nobody sees in 300 ms.
               compositingStrategy = CompositingStrategy.ModulateAlpha
           }
           .background(Dim),
       contentAlignment = Alignment.Center,
   ) {
   ```

   The `graphicsLayer` must come **before** `.background(Dim)` so the dim is inside the faded layer.

   Imports to add (skip any already there): `androidx.compose.animation.core.Animatable`,
   `androidx.compose.animation.core.tween`, `androidx.compose.ui.graphics.graphicsLayer`,
   `androidx.compose.ui.graphics.CompositingStrategy`, `app.opentv.ui.design.SetteeMotion`.

5. **Document it.** In `docs/LOOK.md` → *Player*, change

   ```markdown
   - **Still watching:** centred over a dimmed picture, with Keep watching and Stop.
   ```

   to

   ```markdown
   - **Still watching:** centred over a dimmed picture, with Keep watching and Stop. The dim and the prompt fade up together over 300 ms, with the Highlight already on Keep watching, and go at once.
   ```

6. **Format.** Run `tools/check/check.py quick` (ktlintFormat + detekt) and fix what it reports.
   Don't add anything to `app/detekt-baseline.xml`.

## Boundaries

- Do NOT change `Dim`'s colour or strength, or add blur.
- Do NOT move, scale or slide the prompt. Fade only.
- Do NOT animate the prompt leaving.
- Do NOT touch `NextEpisodeCardView`, `OverlayButton`, `NextEpisodeOverlay`'s `when`, or the view
  model (`VodPlayerViewModel`, `NextEpisodeCountdown`).
- Do NOT key the animation to `Overlay.StillWatching` or `streamStopped`.
- Do NOT add dependencies.
- Do NOT run `./gradlew test`, `lintDebug` or the full suite locally (CLAUDE.md: CI runs them).
  `tools/check/check.py quick` is the only local Gradle run.
- If any current-code excerpt above doesn't match the file (drift since 2d4c3a78), STOP and report
  instead of improvising.

## Verification

- **Mechanical**:
  - `tools/check/check.py quick` exits 0.
  - Push and open the PR with the issue's number (e.g. `feat(#457): "Still watching" fades the
    picture down instead of cutting to dark`). CI's **Test and assemble** must pass, especially
    `NextEpisodeOverlayTest`'s
    `after 3 unattended episodes the player asks whether anyone is still watching`,
    `Keep watching plays the Next Episode` and
    `Stop leaves the player with the Next Episode not started`. The tests advance the clock
    automatically. If one fails, find out why rather than changing the motion.
  - The PR's screenshot table should show `vod_player_still_watching` unchanged. A lighter dim
    there means the capture ran mid-fade. Report that.
- **Feel check** (on the Stick, with a show of short episodes and Next Episode auto-play on):
  - Let three episodes run without touching the remote. When the fourth would start, the picture
    dims smoothly and the question fades up with it. Nothing snaps.
  - Press OK the instant it starts fading. Playback carries on.
  - Leave it until the stream is let go (`streamStopped`). The prompt must not flicker or fade
    again.
  - Slow motion: on an emulator, `adb shell settings put global animator_duration_scale 10`. The
    dim and the words rise together, the words never show before the dim, and there's no
    rectangle or banding where the text sits. Reset with
    `adb shell settings put global animator_duration_scale 1`.
  - Remove animations (`animator_duration_scale 0`): the prompt appears at once.
- **Budget** (release build, `uk.neutraltone.settee`, on the Stick): get to the prompt as above,
  `adb shell dumpsys gfxinfo uk.neutraltone.settee reset` just before it's due, then
  `adb shell dumpsys gfxinfo uk.neutraltone.settee framestats > still-watching.txt` a second after.
  Every frame of the fade must be under 16.7 ms. Put the median and worst frame in the PR.
  **If it misses:** set `STILL_WATCHING_FADE_MILLIS = 0`, say so in the PR with the numbers, and
  leave the `LOOK.md` sentence from step 5 out.
- **Done when**: the dim and prompt fade up together once per appearance with the Highlight on Keep
  watching from the first frame, leaving is instant, CI is green and the screenshot is unchanged,
  and the Stick numbers are in the PR (or the fade is off, with the numbers that justify it).
