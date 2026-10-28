/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.opentv.ui.settings.ProfileNameEntry
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
class ProfileNameEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private var saved: String? = null

    private fun show(initial: String) {
        compose.setTvContent {
            ProfileNameEntry(initial = initial, heading = "Rename profile", onCancel = {}, onSave = { saved = it })
        }
        compose.waitForIdle()
    }

    private val nameBox get() = compose.onNode(hasSetTextAction())

    @Test
    fun `renaming starts with the profile's name in a highlighted box`() {
        show(initial = "Sam")

        nameBox.assertTextContains("Sam")
        nameBox.assertIsFocused()
        captureScreen("profile_name_rename")
    }

    @Test
    fun `Save hands over the name the device keyboard typed`() {
        show(initial = "Sam")

        nameBox.performTextReplacement("Alex")
        compose.onNodeWithText("Save").performClick()

        assertThat(saved).isEqualTo("Alex")
    }

    @Test
    fun `the keyboard's Done key saves the name`() {
        show(initial = "")

        nameBox.performTextInput("Alex")
        nameBox.performImeAction()

        assertThat(saved).isEqualTo("Alex")
    }

    @Test
    fun `the keyboard's Done key saves nothing while the name is blank`() {
        show(initial = "")

        nameBox.performTextInput("  ")
        nameBox.performImeAction()

        assertThat(saved).isNull()
    }

    @Test
    fun `a dictated name longer than 24 letters is cut to fit`() {
        show(initial = "")

        nameBox.performTextInput("The Very Long Family Room Television")
        compose.onNodeWithText("Save").performClick()

        assertThat(saved).isEqualTo("The Very Long Family Roo")
    }
}
