# 001 — Replace the 700 ms screen crossfade with the 300 ms dip

- **Status**: TODO
- **Issue**: neutraltone/settee#424
- **Commit**: 2d4c3a78
- **Severity**: HIGH
- **Category**: Performance / Easing & duration (the motion is already specified, just not built)
- **Estimated scope**: 1 new file (~40 lines), 2 edited files (~30 lines), Stick measurement

## Problem

`docs/LOOK.md` → *Motion and cost* says:

> Screen changes dip through the background in about 300 ms: the old screen darkens, the screen
> swaps, and the new one comes up. Only one screen is drawn at a time [...] Today's 700 ms crossfade
> drew both screens see-through and cost 21-45 ms a frame on the way back to Home.
>
> Presses during a dip go through.

That isn't built. The `NavHost` sets no transitions, so Navigation Compose 2.10.2 uses its
defaults: `fadeIn(tween(700))` and `fadeOut(tween(700))`, playing **at the same time**.

```kotlin
// app/src/main/java/app/opentv/MainActivity.kt:419 (current)
NavHost(navController = navController, startDestination = start) {
    providerRoutes(navController, sourcesViewModel)

    mainShellRoute()
    ...
```

Making it worse, the main screen (Home, Live, Movies, Shows, Recordings) isn't drawn inside the
`NavHost` at all. `KeptShell` draws it **beside** the NavHost, and its NavHost route is empty:

```kotlin
// app/src/main/java/app/opentv/KeptShell.kt:57 (current)
fun NavGraphBuilder.mainShellRoute() = composable(Routes.MAIN) {}
```

```kotlin
// app/src/main/java/app/opentv/KeptShell.kt:71-76 (current)
Box(Modifier.fillMaxSize()) {
    if (main != null && !isPlayerRoute(top?.destination?.route)) {
        key(main.id) { ShellLayer(main, covered = top != main, saveable, shell) }
    }
    navHost()
}
```

So when Settings (or a film's page, Search, Profiles…) opens over Home, the NavHost fades an
*empty box* out while Home stays fully opaque underneath. Settings fades in over a solid Home for
700 ms, with both screens drawn at once. Back does the reverse. Whatever transitions the NavHost
gets, the main screen has to dip too, or nothing changes on the busiest route in the app.

`ShellLayer` keeps the main screen placed while its back stack entry is at least `STARTED`, which
Navigation keeps it at for the whole length of a transition:

```kotlin
// app/src/main/java/app/opentv/KeptShell.kt:114-115 (current)
// Placed while it's on top, and while the screen over it fades in or out, as it was in the NavHost.
val placed = lifecycle.isAtLeast(Lifecycle.State.STARTED)
```

## Target

One dip of 300 ms for every screen change, push and pop:

| Phase | Time | What's drawn |
| --- | --- | --- |
| Out | 0–150 ms | Old screen fades 1 → 0 alpha, `tween(150, easing = EaseOut)` |
| In | 150–300 ms | New screen fades 0 → 1 alpha, `tween(150, delayMillis = 150, easing = EaseOut)` |

`EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)`.

The two phases never overlap, so only one screen is ever drawn at a time. What shows between them
is the solid `SetteeColors.Background` (`0xFF1A1416`) that `MainActivity` already draws behind
everything (`MainActivity.kt:126`, `Box(Modifier.fillMaxSize().background(SetteeColors.Background))`).

The main screen gets **exactly** the same dip because it reads the alpha from the NavHost's own
transition for its empty `Routes.MAIN` destination. There's no second, parallel timer to drift.

Presses during the dip go through: the incoming screen is composed (and takes the Highlight) at
the start of the transition, as it is today. Nothing here may delay or block focus.

**No dip at app start.** `AnimatedContent` doesn't animate its first content, so a NavHost that
starts on `Routes.MAIN` draws Home straight away. That matches `LOOK.md` ("Nothing at app start").

**Remove animations.** Compose scales these tweens by the system's animator duration scale, so
with animations removed the screen just cuts. That's correct for Settee: the aerial TV is the
only motion that ignores the setting (`LOOK.md` → *Waiting*).

**If it misses the budget, it doesn't animate.** `LOOK.md`: "Anything that can't pass doesn't
animate [...] There's no slower animation as a middle ground." The fallback is a plain cut
(`EnterTransition.None` / `ExitTransition.None`), never a longer or partial dip.

## Repo conventions to follow

- Design tokens live in `app/src/main/java/app/opentv/ui/design/` as `object`s named `Settee…`
  (`SetteeColors.kt`, `SetteeShapes.kt`, `SetteeType.kt`). There's no motion token yet. Add
  `SetteeMotion.kt` there. `ui/design` must never import `app.opentv.data` (`DesignBoundaryTest`);
  `androidx.compose.animation.*` is fine.
- Every `.kt` file starts with the repo's GPL header block. Copy it from `SetteeShapes.kt`.
- Comments explain *why* an obvious approach was rejected, not what the code does. Exemplar:
  the block comment at the top of `KeptShell.kt` (lines 44-55).
- Draw-phase reads go in `Modifier.graphicsLayer { … }` lambdas so a changing value redraws
  without recomposing or re-laying out. Exemplar: `ui/design/HighlightSurface.kt:156-158`.
- `CompositionLocal`s used by the shell are declared in `ui/` and provided in `KeptShell.kt`
  (`LocalShellCovered`, `LocalShellReturn`). Follow that for the new one.

## Steps

1. **Create `app/src/main/java/app/opentv/ui/design/SetteeMotion.kt`** (GPL header first):

   ```kotlin
   package app.opentv.ui.design

   import androidx.compose.animation.EnterTransition
   import androidx.compose.animation.ExitTransition
   import androidx.compose.animation.core.CubicBezierEasing
   import androidx.compose.animation.core.FiniteAnimationSpec
   import androidx.compose.animation.core.tween
   import androidx.compose.animation.fadeIn
   import androidx.compose.animation.fadeOut

   /** Settee's motion values (docs/LOOK.md, Motion and cost). */
   object SetteeMotion {
       /** Starts fast and settles, so the response to a press shows in its first frames. */
       val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

       /** Each half of a screen change: the old screen out, then the new one in. */
       const val DIP_HALF_MILLIS = 150

       /*
        * Out, then in, never both: drawing two screens see-through at once was Navigation's default
        * 700 ms crossfade, which cost 21-45 ms a frame on a Stick on the way back to Home.
        */
       fun dipOutSpec(): FiniteAnimationSpec<Float> = tween(DIP_HALF_MILLIS, easing = EaseOut)

       fun dipInSpec(): FiniteAnimationSpec<Float> =
           tween(DIP_HALF_MILLIS, delayMillis = DIP_HALF_MILLIS, easing = EaseOut)

       fun screenDipOut(): ExitTransition = fadeOut(dipOutSpec())

       fun screenDipIn(): EnterTransition = fadeIn(dipInSpec())
   }
   ```

2. **Give the NavHost the dip.** In `app/src/main/java/app/opentv/MainActivity.kt:419`, replace

   ```kotlin
   NavHost(navController = navController, startDestination = start) {
   ```

   with

   ```kotlin
   NavHost(
       navController = navController,
       startDestination = start,
       enterTransition = { SetteeMotion.screenDipIn() },
       exitTransition = { SetteeMotion.screenDipOut() },
       popEnterTransition = { SetteeMotion.screenDipIn() },
       popExitTransition = { SetteeMotion.screenDipOut() },
   ) {
   ```

   and add `import app.opentv.ui.design.SetteeMotion`. Leave every `composable(...)` call unchanged.
   Before editing, confirm with `grep -rn "enterTransition\|exitTransition" app/src/main/java` that
   no destination sets its own transitions. If one does, STOP and report.

3. **Add the shared alpha holder.** In `app/src/main/java/app/opentv/KeptShell.kt`, below
   `mainShellRoute`, add:

   ```kotlin
   /**
    * How far through its dip the main screen is, from 0 (gone) to 1 (drawn). Set by the empty
    * [Routes.MAIN] destination from the NavHost's own transition, so the main screen, drawn outside
    * the NavHost, dips in step with it rather than on a timer of its own that could drift.
    */
   @Stable
   class ShellDip {
       // 0 until the MAIN destination first sets it: that's in the same frame the shell first draws,
       // and a 1 here would flash Home for a frame when it arrives behind a screen dipping out.
       var alpha by mutableFloatStateOf(0f)
   }

   val LocalShellDip = staticCompositionLocalOf<ShellDip?> { null }
   ```

   Imports to add: `androidx.compose.runtime.Stable`, `androidx.compose.runtime.mutableFloatStateOf`,
   `androidx.compose.runtime.staticCompositionLocalOf`.

4. **Drive it from the empty MAIN destination.** Replace

   ```kotlin
   fun NavGraphBuilder.mainShellRoute() = composable(Routes.MAIN) {}
   ```

   with

   ```kotlin
   fun NavGraphBuilder.mainShellRoute() = composable(Routes.MAIN) {
       val dip = LocalShellDip.current ?: return@composable
       // The same specs the NavHost gives this destination, run on its own enter/exit transition.
       val alpha by transition.animateFloat(
           transitionSpec = {
               if (targetState == EnterExitState.Visible) SetteeMotion.dipInSpec() else SetteeMotion.dipOutSpec()
           },
           label = "shellDip",
       ) { if (it == EnterExitState.Visible) 1f else 0f }
       SideEffect { dip.alpha = alpha }
   }
   ```

   `transition` is the `AnimatedVisibilityScope.transition` of the `AnimatedContentScope` that
   `composable { }` gives its content. Imports to add: `androidx.compose.animation.EnterExitState`,
   `androidx.compose.animation.core.animateFloat`, `androidx.compose.runtime.SideEffect`,
   `app.opentv.ui.design.SetteeMotion`. (`getValue` is already imported.)

5. **Provide the holder and apply the alpha.** In `KeptShell(...)`:

   - Add `val dip = remember { ShellDip() }` next to `val saveable = rememberSaveableStateHolder()`.
   - Wrap the `navHost()` call: `CompositionLocalProvider(LocalShellDip provides dip) { navHost() }`.
   - Pass `dip` to `ShellLayer`: `ShellLayer(main, covered = top != main, saveable, dip, shell)`, and
     add the parameter `dip: ShellDip` to `ShellLayer` after `saveable`.

   In `ShellLayer`, add `.graphicsLayer { alpha = dip.alpha }` to the outer `Box`'s modifier chain,
   **directly after** `.placedOnlyIf(placed)`:

   ```kotlin
   Box(
       Modifier
           .fillMaxSize()
           .placedOnlyIf(placed)
           .graphicsLayer { alpha = dip.alpha }
           .onFocusChanged { hasFocus = it.hasFocus }
           ...
   ```

   Import `androidx.compose.ui.graphics.graphicsLayer`. The alpha is read only inside the
   `graphicsLayer` lambda, so a frame of the dip redraws the layer without recomposing or
   measuring the main screen.

6. **Leave the focus logic alone.** `ShellLayer`'s `LaunchedEffect(covered)` and its
   `focusProperties { onEnter = { if (coveredNow) cancelFocusChange() } }` already do what the dip
   needs: the remote can't reach the shell while it's covered, and the Highlight goes back the
   moment it's uncovered, before the dip in has finished. Don't add any wait on the alpha.

7. **Format.** Run `tools/check/check.py quick` (ktlintFormat + detekt) and fix anything it reports.
   If detekt flags something new, fix it. Don't add it to `app/detekt-baseline.xml`.

## Boundaries

- Do NOT change any destination's content, routes, back-stack handling or `BackNavigation.kt`.
- Do NOT change when the shell is let go under a player (`isPlayerRoute`, `KeepHomeLive`). Opening a
  player from Home will still cut Home away at once, then dip the player in. That's existing
  behaviour and out of scope.
- Do NOT add a dip to the Side Panel, the rail, the Categories Sidebar or anything inside a screen.
- Do NOT use `Crossfade`, `AnimatedContent` or `slideIn*`/`scaleIn*` anywhere. Fade only.
- Do NOT add blur, dimming overlays or a scrim. The dip shows `SetteeColors.Background`, which is
  already drawn.
- Do NOT add dependencies. `navigation-compose` 2.10.2 and Compose animation are already on the
  classpath.
- Do NOT run the full test suite, `./gradlew test` or `lintDebug` locally (CLAUDE.md: CI runs them,
  and a local run can swap the shared Mac for hours). `tools/check/check.py quick` is the only
  local Gradle run.
- If any current-code excerpt above doesn't match the file (drift since 2d4c3a78), STOP and report
  instead of improvising.

## Verification

- **Mechanical**:
  - `tools/check/check.py quick` exits 0.
  - Push the branch and open the PR (title e.g. `feat(#424): moving between screens dips
    through the background instead of a slow crossfade`). CI's **Test and assemble** must pass. Watch `KeptShellTest` and `HomeBackTest`: they cover
    the highlight returning on Back and Home's first frame having its rows. `assertIsDisplayed` checks
    placement, not alpha, so they should pass unchanged. If one fails, the dip has changed *when*
    something is placed or focused, which this plan must not do. Fix that, never the test's timing.
- **Feel check** (debug build on the Stick, then emulator for slow motion):
  - Home → Settings → Back, Home → a film's page → Back, Movies → a show's page → Back. Each change
    goes dark briefly, then the new screen comes up. At no point are both screens visible.
  - Press Down straight after OK on a card. The press lands on the film's page (not lost, not on
    Home) even though the page is still dipping in.
  - Press Back straight after OK. You land back on Home with the Highlight on the card you left.
  - Slow motion: on an emulator, `adb shell settings put global animator_duration_scale 10`, repeat
    Home → Settings → Back, and watch frame by frame. Home must reach 0 before Settings starts
    rising, and the rail must not flash at full alpha on the first frame back. Reset with
    `adb shell settings put global animator_duration_scale 1`.
  - Remove animations: `adb shell settings put global animator_duration_scale 0`. Screen changes
    are plain cuts, and the aerial TV still animates while a player buffers. Reset afterwards.
  - Players: open a channel and a film. The picture's `SurfaceView` isn't part of the Compose alpha,
    so video can appear before the controls finish dipping in. Note whether that looks wrong on the
    Stick and report it. Don't try to fix it in this plan.
  - Cold start straight onto Home: no dip, Home is just there.
- **Budget** (release build on the Stick, `uk.neutraltone.settee.pr`; see `docs/PERFORMANCE.md`).
  For each movement, one at a time:
  1. `adb shell dumpsys gfxinfo uk.neutraltone.settee.pr reset`
  2. Do the one movement (e.g. Back from Settings to Home), then wait 1 s.
  3. `adb shell dumpsys gfxinfo uk.neutraltone.settee.pr framestats > dip-<movement>.txt`

  Measure Home → Settings, Settings → Home (Back), Home → a film's page, film's page → Home (Back).
  Leave out the first two frames after the press and the one frame where layout changes size.
  Every other frame must be under 16.7 ms, and the new screen's first frame must come within
  300 ms. Record the median and worst frame per movement in the PR description, next to the
  21-45 ms the crossfade cost.
  **If any movement misses:** don't lengthen or soften the dip. Set all four NavHost lambdas to
  `{ EnterTransition.None }` / `{ ExitTransition.None }`, keep `ShellDip` (it then jumps 0 → 1 in
  one frame, which is a cut), say so in the PR with the numbers, and update `LOOK.md`'s
  *Screen changes* line to say what was measured.
- **Docs**: if it passes, edit `docs/LOOK.md` → *Motion and cost* → *Screen changes* to replace
  "but it hasn't been measured: measure it when it's built. Today's 700 ms crossfade…" with the
  measured numbers.
- **Done when**: no screen change draws two screens at once (slow-motion check), presses during
  the dip land on the new screen, CI is green, and the four Stick movements pass the budget, or
  the dip has been turned into a cut with the numbers that justify it.
