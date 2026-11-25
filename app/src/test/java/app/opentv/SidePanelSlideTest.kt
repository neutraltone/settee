/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.opentv.ui.design.ConfirmPanel
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.SidePanel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** A Side Panel coming in from the right edge (#454), with the clock held to watch it move. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SidePanelSlideTest {

    @get:Rule
    val compose = createComposeRule()

    private var open by mutableStateOf(false)
    private var rows by mutableStateOf(listOf("Subtitles", "Audio"))

    @Test
    fun `a side panel slides in from the right edge and settles in place`() {
        compose.panel()

        compose.open()
        val early = compose.rowLeft()
        compose.mainClock.advanceTimeBy(100)
        val midway = compose.rowLeft()
        compose.mainClock.advanceTimeBy(1_000)
        val settled = compose.rowLeft()

        assertThat(early).isGreaterThan(midway)
        assertThat(midway).isGreaterThan(settled)
        compose.onNodeWithText("Subtitles").assertIsInSidePanel()
    }

    @Test
    fun `presses during the slide move the highlight on the panel`() {
        compose.panel()

        compose.open()
        compose.onNodeWithText("Subtitles").assertIsFocused()
        compose.pressMidSlide(KeyEvent.KEYCODE_DPAD_DOWN)

        compose.onNodeWithText("Audio").assertIsFocused()
        compose.assertWasStillSliding("Audio")
    }

    @Test
    fun `Back during the slide closes the panel on the next frame`() {
        compose.panel()

        compose.open()
        compose.pressMidSlide(KeyEvent.KEYCODE_BACK)

        assertThat(open).isFalse()
        compose.onNodeWithText("Subtitles").assertDoesNotExist()
    }

    @Test
    fun `a confirmation's highlight starts on keep while it slides`() {
        compose.panel { ConfirmPanel(confirm = "Remove", keep = "Keep", onConfirm = {}, onKeep = { open = false }) }

        compose.open()

        compose.onNodeWithText("Keep").assertIsFocused()
        compose.assertWasStillSliding("Keep")
    }

    @Test
    fun `new content in an open panel doesn't slide it in again`() {
        compose.panel()
        compose.open()
        compose.mainClock.advanceTimeBy(1_000)
        val settled = compose.rowLeft()

        compose.changeWithClockHeld { rows = listOf("Subtitles", "Audio", "Quality") }
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("Quality").assertExists()
        assertThat(compose.rowLeft()).isEqualTo(settled)
    }

    private fun ComposeContentTestRule.panel(content: @Composable () -> Unit = { rowsPanel() }) {
        setTvContent { if (open) content() }
        holdClock()
    }

    @Composable
    private fun rowsPanel() {
        SidePanel(onBack = { open = false }) { rows.forEach { PanelRow(it, onClick = {}) } }
    }

    /** Opens the panel and draws its first frames, where the Highlight is placed, with the clock held there. */
    private fun ComposeContentTestRule.open() {
        changeWithClockHeld { open = true }
        attachNewWindow()
    }

    /** Checks the panel hadn't come to rest yet, by letting it. */
    private fun ComposeContentTestRule.assertWasStillSliding(label: String) {
        val now = rowLeft(label)
        mainClock.advanceTimeBy(1_000)
        assertThat(now).isGreaterThan(rowLeft(label))
    }

    private fun ComposeContentTestRule.pressMidSlide(keyCode: Int) {
        val panel = checkNotNull(ShadowDialog.getLatestDialog()) { "No Side Panel is open" }
        changeWithClockHeld {
            runOnUiThread {
                panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                panel.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
        }
    }
}

private fun ComposeContentTestRule.rowLeft(label: String = "Subtitles") =
    onNodeWithText(label).getUnclippedBoundsInRoot().left
