# 004 — "You're all set." rises in when Setup finishes

- **Status**: TODO
- **Issue**: neutraltone/settee#456
- **Commit**: 2d4c3a78
- **Severity**: LOW
- **Category**: Missed opportunities / Delight (rare, first-time moment)
- **Estimated scope**: 1 edited file (~25 lines), plus `SetteeMotion.kt` if it doesn't have `EaseOut` yet
- **Depends on**: nothing. Shares `ui/design/SetteeMotion.kt` with plans 001 (#424), 002 (#454) and 003 (#455).

## Problem

Setup is the first thing a new viewer sits through: a provider's channels, guide, movies and shows
loading, sometimes for minutes on a Fire TV Stick. When it's done, the wordplay line, the step line
and the beating progress bar are swapped for "You're all set." and a summary of the library
("1,204 channels, 6,400 movies and 1,100 shows…") **in a single frame**. The one moment of arrival
in the whole first run reads like a screen refreshing.

The swap happens in place, on the same screen, when `setup.finished` turns true:

```kotlin
// app/src/main/java/app/opentv/ui/setup/SetupScreen.kt:71-99 (current)
ScreenFrame {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(painterResource(R.drawable.ic_settee_mark), contentDescription = null, Modifier.size(96.dp))
            Spacer(Modifier.height(24.dp))
            if (setup.finished) {
                Finished(setup)
            } else {
                Text(stringResource(R.string.setup_wordplay), SetteeType.Hero)
                Spacer(Modifier.height(12.dp))
                val line = justDone?.let { countLine(it) } ?: setup.running?.let { runningLine(it, setup.found) }
                if (line != null) Text(line, SetteeType.Synopsis, color = SetteeColors.QuietText)
                Spacer(Modifier.height(20.dp))
                ProgressBar(setup.bar, Modifier.width(480.dp), working = true)
            }
            // One button that changes, not two: on a Stick, the Highlight asked for by a new
            // Take me to Settee was lost as the focused Watch live TV now left the screen.
            val action = if (setup.finished) onFinish else watchLive
            if (action != null) {
                Spacer(Modifier.height(32.dp))
                PillButton(
                    stringResource(if (setup.finished) R.string.setup_take_me_in else R.string.setup_watch_live),
                    onClick = action,
                    modifier = Modifier.startsFocused(),
                )
            }
        }
    }
}
```

```kotlin
// app/src/main/java/app/opentv/ui/setup/SetupScreen.kt:102-116 (current)
@Composable
private fun Finished(setup: Setup) {
    Text(stringResource(R.string.setup_finished_title), SetteeType.Hero)
    Spacer(Modifier.height(12.dp))
    Text(summary(setup), SetteeType.Synopsis, color = SetteeColors.QuietText, textAlign = TextAlign.Center)
    setup.failed.mapNotNull { it.failure }.forEach { failure ->
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(failure),
            SetteeType.Body,
            color = SetteeColors.QuietText,
            textAlign = TextAlign.Center,
        )
    }
}
```

The comment above the button records a settled decision: the button must stay the **same**
composable across the finish, or the Highlight is lost on a Stick. This plan doesn't touch it.

## Target

When Setup finishes while the screen is up, the finished text arrives in two beats:

| What | Properties | Duration | Delay | Curve |
| --- | --- | --- | --- | --- |
| "You're all set." | `alpha` 0 → 1, `translationY` 12 dp → 0 | 400 ms | 0 ms | `EaseOut` |
| Summary line and any failure lines | `alpha` 0 → 1, `translationY` 12 dp → 0 | 400 ms | 80 ms | `EaseOut` |

`EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)`.

- **Why 400 ms, over the usual 300 ms:** this is the rare, first-run tier, seen once per install,
  where a slightly longer beat is allowed. It never holds the viewer up: see the next point.
- **The button doesn't move, fade or wait.** It changes label in place and keeps the Highlight,
  as today. OK during the rise goes straight to Home.
- **Only when it finishes in front of the viewer.** If the screen is first built already
  finished (it's recreated, for instance), the text is simply there. That uses the same rule
  as the screen's own `justDone` count: "Not on opening: only a step finishing while the screen is up."
- **Draw phase only:** both values are read in `graphicsLayer`, so the rise doesn't measure or lay
  out anything.
- **Remove animations:** Compose scales the tweens by the system's animator duration scale, so at
  0 the text appears at once, as today.
- **If it misses the budget** (every frame under 16.7 ms on a release build on the Stick; this
  isn't a response to a press, so every frame of the rise counts), it doesn't animate. Set both
  durations to 0 and the delay to 0, never longer or softer.

**What this does not fix:** the column is centred, and the finished text isn't the same height as
the wordplay, the step line and the bar it replaces. So the mark and the button jump a little at
the finish. Animating that would mean animating layout size, which re-lays out the screen every
frame and breaks the budget (`LOOK.md` → *Rail*: "Animating the rail's width made the slide stutter
on a Stick."). The plan leaves it. The feel check asks the executor to report how big the jump is.

## Repo conventions to follow

- Motion values live in `app/src/main/java/app/opentv/ui/design/SetteeMotion.kt`
  (`object SetteeMotion`), shared with plans 001-003.
- An `Animatable` run once from a `LaunchedEffect` is how this repo already fades things in.
  Exemplar: `ui/vod/RestingBackdrop.kt:80-87`.
- Per-frame values are read only inside `Modifier.graphicsLayer { … }`. Exemplar:
  `ui/design/HighlightSurface.kt:156-158`.
- "Only while the screen is up, not on opening" is already done in this file with a value
  captured in `remember` (`var seen by remember { mutableIntStateOf(setup.done.size) }`,
  `SetupScreen.kt:63`). Do the same.
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

2. **Constants.** At the bottom of `ui/setup/SetupScreen.kt`, next to the file's other private
   constants (`COUNT_MILLIS` is one), add:

   ```kotlin
   private const val FINISH_RISE_MILLIS = 400
   private const val FINISH_SECOND_BEAT_MILLIS = 80
   private val FINISH_RISE = 12.dp
   ```

3. **Remember whether it opened finished.** In `SetupScreen`, directly after
   `var seen by remember { mutableIntStateOf(setup.done.size) }`, add:

   ```kotlin
   // Like the counts: the finish rises only when it happens in front of the viewer, not on opening.
   val openedFinished = remember { setup.finished }
   ```

   And change the call `Finished(setup)` to `Finished(setup, rises = !openedFinished)`.

4. **Rise inside `Finished`.** Replace the `Finished` function with:

   ```kotlin
   @Composable
   private fun Finished(setup: Setup, rises: Boolean) {
       val title = remember { Animatable(if (rises) 0f else 1f) }
       val rest = remember { Animatable(if (rises) 0f else 1f) }
       LaunchedEffect(Unit) {
           if (!rises) return@LaunchedEffect
           launch { title.animateTo(1f, tween(FINISH_RISE_MILLIS, easing = SetteeMotion.EaseOut)) }
           rest.animateTo(
               1f,
               tween(FINISH_RISE_MILLIS, delayMillis = FINISH_SECOND_BEAT_MILLIS, easing = SetteeMotion.EaseOut),
           )
       }
       Text(stringResource(R.string.setup_finished_title), SetteeType.Hero, modifier = Modifier.rising(title))
       Spacer(Modifier.height(12.dp))
       Column(Modifier.rising(rest), horizontalAlignment = Alignment.CenterHorizontally) {
           Text(summary(setup), SetteeType.Synopsis, color = SetteeColors.QuietText, textAlign = TextAlign.Center)
           setup.failed.mapNotNull { it.failure }.forEach { failure ->
               Spacer(Modifier.height(8.dp))
               Text(
                   stringResource(failure),
                   SetteeType.Body,
                   color = SetteeColors.QuietText,
                   textAlign = TextAlign.Center,
               )
           }
       }
   }

   /*
    * Moved and faded in the draw phase only: the column around it is centred, and animating any size
    * would re-lay out the whole screen every frame.
    */
   private fun Modifier.rising(progress: Animatable<Float, *>) = graphicsLayer {
       alpha = progress.value
       translationY = (1f - progress.value) * FINISH_RISE.toPx()
   }
   ```

   `Text` (from `app.opentv.ui.design`, `ui/design/Text.kt:25`) takes `modifier` as its third
   parameter, so pass it by name as above. Wrapping the summary and
   failures in a `Column` mustn't change their layout: the outer column already centres
   horizontally, and the spacing inside is unchanged.

   Imports to add (skip any already there): `androidx.compose.animation.core.Animatable`,
   `androidx.compose.animation.core.tween`, `androidx.compose.ui.graphics.graphicsLayer`,
   `kotlinx.coroutines.launch`, `app.opentv.ui.design.SetteeMotion`.

5. **Document it.** In `docs/LOOK.md` → *Onboarding* → *Setting up*, at the end of the
   **Finished:** bullet (after "Back does the same."), add:

   ```markdown
    When setup finishes with the screen up, "You're all set." rises 12 dp and fades in over 400 ms, and the summary follows 80 ms behind; the button doesn't move and has the Highlight throughout.
   ```

6. **Format.** Run `tools/check/check.py quick` (ktlintFormat + detekt) and fix what it reports.
   Don't add anything to `app/detekt-baseline.xml`.

## Boundaries

- Do NOT touch the `PillButton`, its `startsFocused()`, its label switch or the `action` logic.
  The comment above it records why it must stay one composable.
- Do NOT animate the mark, the column's size or position, or the switch away from the progress bar.
- Do NOT add bounce, scale, colour or any celebration beyond the two rises. Settee is calm.
- Do NOT change `summary()`, the failure wording, the `justDone` counting or the view model.
- Do NOT add dependencies.
- Do NOT run `./gradlew test`, `lintDebug` or the full suite locally (CLAUDE.md: CI runs them).
  `tools/check/check.py quick` is the only local Gradle run.
- If any current-code excerpt above doesn't match the file (drift since 2d4c3a78), STOP and report
  instead of improvising.

## Verification

- **Mechanical**:
  - `tools/check/check.py quick` exits 0.
  - Push and open the PR with the issue's number (e.g. `feat(#456): "You're all set." rises in
    when setup finishes`). CI's **Test and assemble** must pass, especially `SetupScreenTest`:
    - `once finished it sums up the library, and Take me to Settee has the highlight`
    - `when setup finishes while Watch live TV now has the highlight, Take me to Settee takes it`
      (the Highlight must survive the finish, which this plan must not affect)
    - `once finished, Back takes you in too`
    - `a guide or list that didn't load is named on the finished screen`

    If any fails, find out why. Don't change the motion to suit it.
  - The PR's screenshot table should show `setup_finished` and `setup_finished_with_failures`
    unchanged. A half-faded title means a capture ran mid-rise. Report that.
- **Feel check** (on the Stick, with a fresh install, or with every provider deleted, then a provider added):
  - Let Setup run to the end with the screen in view. "You're all set." rises and fades in, and
    the summary follows just behind it. It reads as two beats, not a stutter.
  - Press OK the instant it starts rising. Home opens straight away.
  - Note how far the mark and the button jump as the column changes height at the finish, and
    put it in the PR. That jump is out of scope here, but the owner should see it.
  - Slow motion: on an emulator, `adb shell settings put global animator_duration_scale 10`,
    using `python3 tools/stalker_mock.py` as the provider. Both parts move straight up 12 dp. The
    summary starts after the title, and both settle without overshooting. Reset with
    `adb shell settings put global animator_duration_scale 1`.
  - Remove animations (`animator_duration_scale 0`): the finished text appears at once.
- **Budget** (release build, `uk.neutraltone.settee.pr`, on the Stick): run Setup against a real or mock
  provider, `adb shell dumpsys gfxinfo uk.neutraltone.settee.pr reset` just before the last step
  ends, then `adb shell dumpsys gfxinfo uk.neutraltone.settee.pr framestats > setup-finish.txt` a
  second after. Leave out the one frame where the column changes height. Every other frame must be
  under 16.7 ms. Put the median and worst frame in the PR. **If it misses:** set
  `FINISH_RISE_MILLIS = 0` and `FINISH_SECOND_BEAT_MILLIS = 0`, say so in the PR with the numbers,
  and leave the `LOOK.md` sentence from step 5 out.
- **Done when**: the finished text rises in two beats only when Setup finishes in front of the
  viewer, the button keeps the Highlight and acts at once, CI is green and the screenshots are
  unchanged, and the Stick numbers are in the PR (or the rise is off, with the numbers that justify it).
