/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import app.opentv.ui.design.AerialTv
import app.opentv.ui.design.AerialTvSize
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AerialTvTest {

    /** The system's animation scale at 0, as "Remove animations" sets it. */
    private object AnimationsOff : MotionDurationScale {
        override val scaleFactor = 0f
    }

    @get:Rule
    val compose = createComposeRule(effectContext = AnimationsOff)

    /** What the indicator looks like at each step of [stepMillis], [steps] times. */
    private fun frames(size: AerialTvSize, steps: Int = 12, stepMillis: Long = 250): List<Int> {
        compose.mainClock.autoAdvance = false
        compose.setTvContent { AerialTv("Loading", Modifier.testTag("tv"), size) }
        return (1..steps).map {
            compose.mainClock.advanceTimeBy(stepMillis)
            val image = compose.onNodeWithTag("tv").captureToImage()
            IntArray(image.width * image.height).also { image.readPixels(it) }.contentHashCode()
        }
    }

    @Test
    fun `the full size keeps moving with the system's animations turned off`() {
        assertThat(frames(AerialTvSize.Full).distinct().size).isGreaterThan(1)
    }

    @Test
    fun `the small size keeps moving with the system's animations turned off`() {
        assertThat(frames(AerialTvSize.Small).distinct().size).isGreaterThan(1)
    }

    @Test
    fun `a screen reader hears what is loading`() {
        compose.setTvContent { AerialTv("Loading the film") }

        compose.onNodeWithContentDescription("Loading the film").assertExists()
    }
}
