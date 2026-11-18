# 003 — The Next Episode card rises into the picture instead of popping in

- **Status**: TODO
- **Issue**: neutraltone/settee#455
- **Commit**: 2d4c3a78
- **Severity**: LOW
- **Category**: Missed opportunities / Preventing a jarring change
- **Estimated scope**: 1 edited file (~15 lines), plus `SetteeMotion.kt` if it doesn't have `EaseOut` yet
- **Depends on**: nothing. Shares `ui/design/SetteeMotion.kt` with plans 001 (#424) and 002 (#454).

## Problem

Fifteen seconds before an episode ends, the Next Episode card (a still, the episode, a countdown,
and Play now beside Watch credits) appears in the bottom right **in one frame**, over a picture
the viewer is watching. It's a 340 dp block cutting into the end of the episode. Settee is
meant to be calm (`docs/LOOK.md` → *Feel*), and this is the one overlay that turns up without
the viewer pressing anything, so they have no reason to expect it.

```kotlin
// app/src/main/java/app/opentv/ui/vod/NextEpisodeOverlay.kt:83-94 (current)
Box(modifier.fillMaxSize()) {
    when (val showing = overlay) {
        is Overlay.Card -> next?.let { card ->
            BackHandler { viewModel.cancel() }
            NextEpisodeCardView(
                card = card,
                showing = showing,
                onPlay = { scope.launch { viewModel.ok() } },
                onCancel = viewModel::cancel,
                modifier = Modifier.align(Alignment.BottomEnd).padding(48.dp),
            )
        }
```

```kotlin
// app/src/main/java/app/opentv/ui/vod/NextEpisodeOverlay.kt:109-126 (current)
@Composable
private fun NextEpisodeCardView(
    card: VodPlayerViewModel.NextEpisodeCard,
    showing: Overlay.Card,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier
            .width(CARD_WIDTH)
            .background(SetteeColors.Surface, SetteeShapes.Card)
            .clip(SetteeShapes.Card),
    ) {
```

`Overlay.Card` is a `data class Card(val secondsLeft: Int, val counting: Boolean)`
(`player/NextEpisodeCountdown.kt:31`), so a new instance arrives every second of the countdown.
`NextEpisodeCardView` stays in composition across those, since it's the same `when` branch in the
same place. So anything keyed to entering composition runs once per appearance, not once per second.

## Target

When the card appears, it fades and rises into place:

- **Properties:** `graphicsLayer` `alpha` 0 → 1 and `translationY` 24 dp → 0, together, on the
  card only.
- **Duration and curve:** 250 ms, `EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)`, so it
  starts moving straight away and settles.
- **Once per appearance,** keyed to `NextEpisodeCardView` entering composition. Countdown ticks
  (a new `Overlay.Card` each second) must not restart it.
- **The Highlight is on Play now from the first frame,** as today. OK during the rise plays the
  next episode at once. Nothing waits for the animation.
- **Leaving is instant.** Play now, Watch credits, Back and the countdown running out all remove the
  card in one frame, as today. Each is either the response to a press or the next episode
  replacing everything, and a fade-out would only be in the way.
- **"Still watching" is out of scope.** It's a separate prompt with its own dim.
- **Remove animations:** Compose scales the tween by the system's animator duration scale, so at
  0 the card appears in one frame, as today.
- **If it misses the budget** (`LOOK.md` → *Motion and cost*: after the first two frames, every
  frame under 16.7 ms on a release build on the Stick), the card doesn't animate. Set the
  duration to 0, never longer or softer.

The fade does draw the card see-through over the picture for 250 ms. That's allowed: `LOOK.md`'s
rule is against two *screens* see-through at once and alpha-dimmed shelves, not one small card
arriving over video. It costs one offscreen layer the size of the card (340 dp wide).

## Repo conventions to follow

- Motion values live in `app/src/main/java/app/opentv/ui/design/SetteeMotion.kt`
  (`object SetteeMotion`), shared with plans 001 and 002.
- Per-frame values are read only inside `Modifier.graphicsLayer { … }`, so a frame redraws the
  layer without recomposing or measuring. Exemplar: `ui/design/HighlightSurface.kt:156-158`.
- An `Animatable` run once from a `LaunchedEffect` is how this repo already fades artwork in.
  Exemplar: `ui/vod/RestingBackdrop.kt:80-87` (`val alpha = remember { Animatable(0f) }` …
  `alpha.animateTo(1f, tween(ART_FADE_MILLIS))`).
- Comments explain *why* an obvious approach was rejected, not what the code does.

## Steps

1. **`EaseOut` in `SetteeMotion`.** Open `app/src/main/java/app/opentv/ui/design/SetteeMotion.kt`.
   - If it already has `val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)` (plan 001 merged),
     use it as it is.
   - If the file exists without `EaseOut`, add inside `object SetteeMotion`:

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

2. **Add the card's constants.** In `ui/vod/NextEpisodeOverlay.kt`, next to `private val CARD_WIDTH = 340.dp`:

   ```kotlin
   private const val CARD_ENTER_MILLIS = 250
   private val CARD_RISE = 24.dp
   ```

3. **Run the entrance.** In `NextEpisodeCardView`, after the existing
   `LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }` line, add:

   ```kotlin
   // It arrives unasked over a picture someone is watching, so it eases in rather than cutting
   // into it. Keyed to the card arriving, not to [showing], which changes every second of the countdown.
   val enter = remember { Animatable(0f) }
   LaunchedEffect(Unit) { enter.animateTo(1f, tween(CARD_ENTER_MILLIS, easing = SetteeMotion.EaseOut)) }
   ```

   Leave the focus `LaunchedEffect` exactly as it is, and leave it first. The Highlight must be
   asked for before, and independently of, the animation.

4. **Apply it.** In the same function's `Column`, add a `graphicsLayer` as the **first** modifier
   after `modifier`:

   ```kotlin
   Column(
       modifier
           .graphicsLayer {
               alpha = enter.value
               translationY = (1f - enter.value) * CARD_RISE.toPx()
           }
           .width(CARD_WIDTH)
           .background(SetteeColors.Surface, SetteeShapes.Card)
           .clip(SetteeShapes.Card),
   ) {
   ```

   Imports to add (skip any already there): `androidx.compose.animation.core.Animatable`,
   `androidx.compose.animation.core.tween`, `androidx.compose.ui.graphics.graphicsLayer`,
   `app.opentv.ui.design.SetteeMotion`.

5. **Document it.** In `docs/LOOK.md` → *Player*, change the bullet

   ```markdown
   - **Up Next:** a card in the bottom right with a still, the episode, a countdown, Play now and Watch credits.
   ```

   to

   ```markdown
   - **Up Next:** a card in the bottom right with a still, the episode, a countdown, Play now and Watch credits. It fades and rises 24 dp into place over 250 ms, with the Highlight already on Play now, and goes at once.
   ```

6. **Format.** Run `tools/check/check.py quick` (ktlintFormat + detekt) and fix what it reports.
   Don't add anything to `app/detekt-baseline.xml`.

## Boundaries

- Do NOT animate the card leaving. All four ways out stay instant.
- Do NOT touch `StillWatchingPrompt`, `Dim`, `OverlayButton`, the countdown `ProgressBar`, or the
  view model (`VodPlayerViewModel`, `NextEpisodeCountdown`).
- Do NOT key the animation to `showing` or `card`. That would restart it every second.
- Do NOT move the card, change its size, padding (48 dp) or alignment.
- Do NOT add dependencies.
- Do NOT run `./gradlew test`, `lintDebug` or the full suite locally (CLAUDE.md: CI runs them).
  `tools/check/check.py quick` is the only local Gradle run.
- If any current-code excerpt above doesn't match the file (drift since 2d4c3a78), STOP and report
  instead of improvising.

## Verification

- **Mechanical**:
  - `tools/check/check.py quick` exits 0.
  - Push and open the PR with the issue's number (e.g. `feat(#455): the next episode card eases
    into the picture instead of popping up`). CI's **Test and assemble** must pass, in particular
    `NextEpisodeOverlayTest`. Its tests advance the test clock automatically, so the card is in
    place by the time they assert or capture. If one fails, find out why rather than changing
    the motion to suit it.
  - The PR's screenshot table should show `vod_player_next_episode_card`,
    `vod_player_next_episode_card_waiting` and `vod_player_next_episode_card_watch_credits`
    unchanged. A half-faded card in a screenshot means the capture ran mid-animation. Report that.
    Don't shorten the animation.
- **Feel check** (on the Stick, with a show whose episodes have artwork):
  - Seek to about 20 s before an episode's end. At 15 s, the card fades up into the bottom right.
    It doesn't jump.
  - Watch a whole countdown. The card must not flicker or re-rise once a second.
  - Press OK the instant it starts appearing. The next episode plays.
  - Press Right then OK (Watch credits) during the rise. The card goes at once and the credits
    play on.
  - Slow motion: on an emulator, `adb shell settings put global animator_duration_scale 10`. The
    card moves straight up 24 dp while it fades in, from its final place's bottom edge, not from
    the corner of the screen. Reset with `adb shell settings put global animator_duration_scale 1`.
  - Remove animations (`animator_duration_scale 0`): the card appears in one frame, as before.
- **Budget** (release build, `uk.neutraltone.settee.pr`, on the Stick, during playback):
  1. Seek to about 20 s before the end and let it play.
  2. `adb shell dumpsys gfxinfo uk.neutraltone.settee.pr reset` at about 17 s.
  3. After the card has settled, `adb shell dumpsys gfxinfo uk.neutraltone.settee.pr framestats > card-enter.txt`.

  The card isn't a response to a press, so no frames are left out. Every frame from the card's
  first to its last must be under 16.7 ms. Put the median and worst frame in the PR.
  **If it misses:** set `CARD_ENTER_MILLIS = 0`, say so in the PR with the numbers, and change the
  `LOOK.md` bullet from step 5 to say the card appears at once.
- **Done when**: the card fades and rises in once per appearance with the Highlight already on
  Play now, all four ways out are instant, CI is green and the screenshots are unchanged, and the
  Stick numbers are in the PR (or the entrance is off, with the numbers that justify it).
