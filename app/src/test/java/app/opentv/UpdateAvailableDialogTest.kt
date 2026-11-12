/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.core.ViewerError
import app.opentv.update.NotesHeading
import app.opentv.update.NotesSection
import app.opentv.update.UpdateAvailableDialog
import app.opentv.update.UpdateChecker
import app.opentv.update.UpdateDownloadingPanel
import app.opentv.update.UpdateFailedPanel
import app.opentv.update.VersionNotes
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
class UpdateAvailableDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val update = UpdateChecker.Update(
        versionName = "0.5.0",
        title = "Settee 0.5.0",
        notes = VersionNotes(
            "0.5.0",
            listOf(
                NotesSection(NotesHeading.NewFeatures, listOf("pick up where you left off on any series from Home")),
                NotesSection(NotesHeading.BugFixes, listOf("subtitles stay on after a channel change")),
            ),
        ),
        apkUrl = "https://example.com/settee.apk",
        apkSizeBytes = 1000,
        releaseUrl = "https://example.com/release",
    )

    private val longUpdate = update.copy(
        notes = VersionNotes(
            "0.5.0",
            listOf(NotesSection(NotesHeading.NewFeatures, (1..40).map { "change number $it" })),
        ),
    )

    private var later = 0

    private fun show(update: UpdateChecker.Update = this.update) = compose.setTvContent {
        UpdateAvailableDialog(update, installedVersion = "0.4.0", onInstall = {}, onLater = { later++ })
    }

    @Test
    fun `it opens as a Side Panel with the highlight on Update`() {
        show()

        compose.onNodeWithText("Settee 0.5.0 is available. You have 0.4.0.").assertExists()
        compose.onNodeWithText("Update").assertIsFocused().assertIsInSidePanel()
        captureScreen("update_available_dialog")
    }

    @Test
    fun `down on Update scrolls long notes while the highlight stays on it`() {
        show(longUpdate)
        compose.onNodeWithText("• change number 1").assertIsDisplayed()

        compose.onNodeWithText("Update").performKeyInput { repeat(3) { pressKey(Key.DirectionDown) } }

        compose.onNodeWithText("• change number 1").assertIsNotDisplayed()
        compose.onNodeWithText("Update").assertIsFocused()
    }

    @Test
    fun `once the notes have scrolled to their end, down moves on to Later`() {
        show(longUpdate)
        compose.onNodeWithText("• change number 40").assertIsNotDisplayed()

        compose.onNodeWithText("Update").performKeyInput { repeat(40) { pressKey(Key.DirectionDown) } }

        compose.onNodeWithText("• change number 40").assertIsDisplayed()
        compose.onNodeWithText("Later").assertIsFocused()
    }

    @Test
    fun `back is Later`() {
        show()

        compose.pressBackOnPanel()

        assertThat(later).isEqualTo(1)
    }

    @Test
    fun `downloading shows how far through it is`() {
        compose.setTvContent { UpdateDownloadingPanel(fraction = 0.4f, onBack = {}) }

        compose.onNodeWithText("40%").assertIsInSidePanel()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.4f, 0f..1f))).assertExists()
        captureScreen("update_downloading")
    }

    @Test
    fun `back closes the downloading panel`() {
        var closed = 0
        compose.setTvContent { UpdateDownloadingPanel(fraction = 0.4f, onBack = { closed++ }) }

        compose.pressBackOnPanel()

        assertThat(closed).isEqualTo(1)
    }

    @Test
    fun `a failed download says why, with the highlight on Try again`() {
        var tries = 0
        compose.setTvContent {
            UpdateFailedPanel(
                ViewerError("Couldn't download the update. Check this TV is online, then try again.", "timeout"),
                onTryAgain = { tries++ },
                onClose = {},
            )
        }

        compose.onNodeWithText("Couldn't download the update", substring = true).assertIsInSidePanel()
        compose.onNodeWithText("Try again").assertIsFocused().performClick()
        assertThat(tries).isEqualTo(1)
        captureScreen("update_failed")
    }

    @Test
    fun `back closes a failed download`() {
        var closed = 0
        compose.setTvContent {
            UpdateFailedPanel(ViewerError("Couldn't download the update."), onTryAgain = {}, onClose = { closed++ })
        }

        compose.pressBackOnPanel()

        assertThat(closed).isEqualTo(1)
    }
}
