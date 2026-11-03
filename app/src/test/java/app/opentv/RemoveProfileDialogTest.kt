/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.ui.ProfilesViewModel
import app.opentv.ui.settings.ProfilesScreen
import app.opentv.ui.settings.RemoveProfileDialog
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoveProfileDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private var cancelled = false
    private var removed = false

    private fun show() {
        compose.setTvContent {
            RemoveProfileDialog(name = "Tom", onCancel = { cancelled = true }, onRemove = { removed = true })
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `Remove on the Profiles screen asks first and removes nothing yet`() = runTest {
        val app: Application = ApplicationProvider.getApplicationContext()
        val profiles = ServiceLocator.get(app).profiles
        profiles.create("Tom")
        compose.setTvContent { ProfilesScreen(onBack = {}, viewModel = ProfilesViewModel(app)) }
        compose.waitUntil { compose.onAllNodesWithText("Tom").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Cancel").assertIsFocused()
        assertThat(profiles.all().map { it.name }).contains("Tom")
    }

    @Test
    fun `it says what removing costs, with Cancel highlighted`() {
        show()
        compose.onNodeWithText(
            "Remove Tom from this TV? Their progress and Favourites here will be deleted. Other devices keep Tom.",
        ).assertExists()
        compose.onNodeWithText("Cancel").assertIsFocused()
        captureScreen("profiles_remove_confirm")
    }

    @Test
    fun `a stray press of Select cancels and removes nothing`() {
        show()
        press(Key.DirectionCenter)

        assertThat(cancelled).isTrue()
        assertThat(removed).isFalse()
    }

    @Test
    fun `Remove is one press of the D-pad away`() {
        show()
        press(Key.DirectionRight)
        compose.onNodeWithText("Remove").assertIsFocused()

        press(Key.DirectionCenter)

        assertThat(removed).isTrue()
        assertThat(cancelled).isFalse()
    }
}
