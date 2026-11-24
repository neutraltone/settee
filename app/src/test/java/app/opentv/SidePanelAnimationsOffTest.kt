/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.SidePanel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Side Panel with the system's animations removed. Apart from [SidePanelSlideTest] because the
 * animation scale belongs to the compose rule, and so to the whole class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SidePanelAnimationsOffTest {

    @get:Rule
    val compose = createComposeRule(effectContext = AnimationsOff)

    private var open by mutableStateOf(false)

    // Its very first frame is drawn before the animation has had a frame to finish on, so it's
    // checked from the second: one frame off screen, which nobody sees.
    @Test
    fun `with the system's animations removed a side panel is in place at once`() {
        compose.setTvContent {
            if (open) SidePanel(onBack = { open = false }) { PanelRow("Subtitles", onClick = {}) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        open = true
        // The clock is held, so nothing else tells Compose the state changed.
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        // The panel's window is attached by the main looper, which a held clock doesn't run.
        compose.waitForIdle()
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val early = compose.onNodeWithText("Subtitles").getUnclippedBoundsInRoot().left
        compose.mainClock.advanceTimeBy(1_000)

        assertThat(early).isEqualTo(compose.onNodeWithText("Subtitles").getUnclippedBoundsInRoot().left)
    }
}
