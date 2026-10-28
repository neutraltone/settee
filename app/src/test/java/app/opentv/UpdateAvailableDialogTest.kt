/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.update.NotesHeading
import app.opentv.update.NotesSection
import app.opentv.update.UpdateAvailableDialog
import app.opentv.update.UpdateChecker
import app.opentv.update.VersionNotes
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class UpdateAvailableDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val update = UpdateChecker.Update(
        versionName = "0.5.0",
        title = "Settee 0.5.0",
        notes = VersionNotes(
            "0.5.0",
            listOf(NotesSection(NotesHeading.NewFeatures, (1..40).map { "change number $it" })),
        ),
        apkUrl = "https://example.com/settee.apk",
        apkSizeBytes = 1000,
        releaseUrl = "https://example.com/release",
    )

    @Test
    fun `down on the remote scrolls long notes while the highlight stays where it is`() {
        compose.setContent {
            UpdateAvailableDialog(update, installedVersion = "0.4.0", onInstall = {}, onLater = {})
        }
        compose.onNodeWithText("change number 40", substring = true).assertIsNotDisplayed()

        val highlighted = compose.onNode(isFocused()).fetchSemanticsNode().id

        compose.onNode(isFocused()).performKeyInput { repeat(40) { pressKey(Key.DirectionDown) } }

        compose.onNodeWithText("change number 40", substring = true).assertIsDisplayed()
        assertThat(compose.onNode(isFocused()).fetchSemanticsNode().id).isEqualTo(highlighted)
    }
}
