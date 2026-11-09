/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private fun show(initial: String, taken: Set<String> = emptySet()) {
        compose.setTvContent {
            view = LocalView.current
            ProfileNameEntry(
                initial = initial,
                heading = "Rename profile",
                onCancel = {},
                onSave = { saved = it },
                isTaken = { it.trim() in taken },
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `renaming starts with the profile's name in a highlighted box`() {
        show(initial = "Sam")

        assertThat(device.textShownBy()).isEqualTo("Sam")
        assertThat(device.highlighted()).isTrue()
        captureScreen("profile_name_rename")
    }

    // A single-line EditText takes Up to move its cursor to the start first, which would leave a
    // viewer pressing Up twice to reach Save.
    @Test
    fun `Up from the name box reaches Save straight away`() {
        show(initial = "Sam")

        device.remote(KeyEvent.KEYCODE_DPAD_UP)

        compose.onNodeWithText("Save").assertIsFocused()
    }

    @Test
    fun `Save hands over the name the device keyboard typed`() {
        show(initial = "Sam")

        device.replaceAll("Alex")
        compose.onNodeWithText("Save").performClick()

        assertThat(saved).isEqualTo("Alex")
    }

    @Test
    fun `the keyboard's Done key saves the name`() {
        show(initial = "")

        device.type("Alex")
        device.pressAction()

        assertThat(saved).isEqualTo("Alex")
    }

    @Test
    fun `the keyboard's Done key saves nothing while the name is blank`() {
        show(initial = "")

        device.type("  ")
        device.pressAction()

        assertThat(saved).isNull()
    }

    @Test
    fun `a name another profile has can't be saved, and the entry says why`() {
        show(initial = "", taken = setOf("Tom"))

        device.type("Tom")
        compose.onNodeWithText("Another profile is already called Tom.").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        captureScreen("profile_name_taken")

        device.pressAction()
        assertThat(saved).isNull()
    }

    // A name can arrive longer than the box takes, from sync or an older version.
    @Test
    fun `renaming a profile whose name is too long saves it cut to fit`() {
        show(initial = "The Very Long Family Room Television")

        compose.onNodeWithText("Save").performClick()

        assertThat(saved).isEqualTo("The Very Long Family Roo")
    }

    @Test
    fun `a dictated name longer than 24 letters is cut to fit`() {
        show(initial = "")

        device.type("The Very Long Family Room Television")
        compose.onNodeWithText("Save").performClick()

        assertThat(saved).isEqualTo("The Very Long Family Roo")
    }
}
