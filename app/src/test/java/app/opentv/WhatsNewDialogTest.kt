/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.update.NotesHeading
import app.opentv.update.NotesSection
import app.opentv.update.VersionNotes
import app.opentv.update.WhatsNewDialog
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WhatsNewDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val notes = listOf(
        VersionNotes(
            "0.4.0",
            listOf(
                NotesSection(NotesHeading.HeadsUp, listOf("add your providers again after this update")),
                NotesSection(NotesHeading.NewFeatures, listOf("pick up where you left off on any series from Home")),
            ),
        ),
        VersionNotes(
            "0.3.0",
            listOf(
                NotesSection(NotesHeading.BugFixes, listOf("the guide no longer jumps back to the top")),
                NotesSection(NotesHeading.PerformanceImprovements, listOf("the guide opens faster")),
            ),
        ),
    )

    private var dismissed = 0

    private fun show() = compose.setContent {
        WhatsNewDialog(installedVersion = "0.4.0", notes = notes, onDismiss = { dismissed++ })
    }

    @Test
    fun `shows each version's notes under the viewer's headings`() {
        show()

        compose.onNodeWithText("What's new in 0.4.0").assertExists()
        compose.onNodeWithText("0.3.0").assertExists()
        compose.onNodeWithText("Heads up").assertExists()
        compose.onNodeWithText("New Features").assertExists()
        compose.onNodeWithText("Bug Fixes").assertExists()
        compose.onNodeWithText("Performance Improvements").assertExists()
        compose.onNodeWithText("pick up where you left off on any series from Home", substring = true).assertExists()
    }

    @Test
    fun `the highlight starts on the dismiss button`() {
        show()

        compose.onNodeWithText("Got it").assertIsFocused()
    }

    @Test
    fun `selecting the dismiss button dismisses it`() {
        show()

        compose.onNodeWithText("Got it").performClick()

        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `back dismisses it`() {
        show()

        val dialog = ShadowDialog.getLatestDialog()
        compose.runOnUiThread {
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()

        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun `down on the remote scrolls long notes while the highlight stays on the dismiss button`() {
        val long = listOf(
            VersionNotes("0.4.0", listOf(NotesSection(NotesHeading.NewFeatures, (1..40).map { "change number $it" }))),
        )
        compose.setContent { WhatsNewDialog(installedVersion = "0.4.0", notes = long, onDismiss = {}) }
        compose.onNodeWithText("change number 40", substring = true).assertIsNotDisplayed()

        compose.onNodeWithText("Got it").performKeyInput { repeat(40) { pressKey(Key.DirectionDown) } }

        compose.onNodeWithText("change number 40", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Got it").assertIsFocused()
    }
}
