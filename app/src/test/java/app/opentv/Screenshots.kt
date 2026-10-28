/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.opentv.ui.theme.OpenTvTheme
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.request.SuccessResult
import coil.test.FakeImageLoaderEngine
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.provideRoborazziContext
import org.junit.rules.ExternalResource

/*
 * Screenshot assertions for UI tests (#44). A screenshot is an extra line in an existing UI test,
 * not a separate suite: the test class adds
 *
 *   @Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
 *   @GraphicsMode(GraphicsMode.Mode.NATIVE)
 *   @get:Rule val artwork = PlaceholderArtwork()
 *
 * renders with setTvContent and calls captureScreen("<screen>_<state>") where it asserts a state.
 * captureScreen does nothing in a plain test run; ./gradlew recordRoborazziDebug writes the PNGs to
 * app/build/outputs/roborazzi/, which is what the Screenshots workflow compares on each PR.
 *
 * Video never plays under Robolectric, so a player's area renders blank.
 */

/** The Fire TV Stick's display: 1920x1080 landscape at xhdpi (960x540dp), television UI mode, D-pad. */
const val TV_1080P = RobolectricDeviceQualifiers.Television1080p

private val screenNamePattern = Regex("[a-z0-9_]+")

/**
 * Renders [content] as the app shows it on a TV: the dark theme on the theme's background, in
 * keyboard input mode. A remote never puts Android in touch mode, and in touch mode clickable
 * things can't take focus, so a focus highlight would never show.
 */
fun ComposeContentTestRule.setTvContent(content: @Composable () -> Unit) {
    // The SideEffect below only reaches this window; a dialog opens in a window of its own, which
    // would otherwise start in touch mode with nothing able to take focus.
    InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    setTvScreen(content)
}

private fun ComposeContentTestRule.setTvScreen(content: @Composable () -> Unit) = setContent {
    val inputMode = LocalInputModeManager.current
    SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
    OpenTvTheme(darkTheme = true) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Surface stretches its child to fill it; the Box lets content keep its own size.
            Box { content() }
        }
    }
}

/**
 * Captures every window on screen, dialogs included, as `<name>.png`. The name becomes the file and
 * the row in the PR's table, so it's lower_snake_case: `<screen>_<state>`.
 */
fun captureScreen(name: String) {
    require(screenNamePattern.matches(name)) { "Screenshot names are lower_snake_case: $name" }
    captureScreenRoboImage("${provideRoborazziContext().outputDirectory}/$name.png")
}

/**
 * Makes every Coil image load succeed at once with a flat placeholder, so screenshots never depend
 * on the network. Each URL gets its own colour, so a screenshot still shows which artwork went where.
 */
class PlaceholderArtwork : ExternalResource() {
    override fun before() {
        val engine = FakeImageLoaderEngine.Builder()
            .default { chain ->
                SuccessResult(
                    drawable = ColorDrawable(colourFor(chain.request.data)),
                    request = chain.request,
                    dataSource = DataSource.MEMORY,
                )
            }
            .build()
        Coil.setImageLoader(
            ImageLoader.Builder(ApplicationProvider.getApplicationContext())
                .components { add(engine) }
                .build(),
        )
    }

    override fun after() = Coil.reset()

    // Mid-tones only: dark enough for white overlay text, light enough to tell from the background.
    private fun colourFor(data: Any): Int {
        val hue = (data.toString().hashCode() and Int.MAX_VALUE) % HUES
        return android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), SATURATION, VALUE))
    }

    private companion object {
        const val HUES = 360
        const val SATURATION = 0.45f
        const val VALUE = 0.6f
    }
}
