/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.espresso.Espresso
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsScreen
import app.opentv.ui.settings.SettingsSection
import app.opentv.ui.settings.SettingsTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings: sections on the left, the highlighted one's settings beside them (#168). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private var leftSettings = 0

    private fun show(memory: SettingsMemory = SettingsMemory()) = compose.setTvContent {
        view = LocalView.current
        SettingsScreen(memory = memory, onBack = { leftSettings++ })
    }

    private fun press(vararg keys: Int) = keys.forEach(device::remote)

    private fun highlighted() = compose.onNode(isFocused())

    private fun paneTitle() = compose.onNodeWithTag(SettingsTags.PANE_TITLE)

    @Test
    fun `Settings opens on Providers, with the ten sections in order and Providers' settings beside them`() {
        show()
        captureScreen("settings_initial")

        highlighted().assertTextContains("Providers")
        paneTitle().assertTextContains("Providers")
        val sections = compose.onAllNodesWithTag(SettingsTags.SECTION).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString("") }
        assertThat(sections).containsExactly(
            "Providers",
            "TV guide",
            "Channels",
            "Display and playback",
            "Recording",
            "Parental lock",
            "Your devices",
            "Add-ons",
            "Manage on phone or laptop",
            "About",
        ).inOrder()
    }

    @Test
    fun `highlighting a section shows its settings straight away, with no OK`() {
        show()

        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)

        highlighted().assertTextContains("TV guide")
        paneTitle().assertTextContains("TV guide")
        compose.onNodeWithText("Where your TV guide comes from").assertExists()
    }

    @Test
    fun `Right or OK moves into the pane, and Left comes back to the section`() {
        show(SettingsMemory(SettingsSection.Display))

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        highlighted().assertTextContains("Subtitles")
        captureScreen("settings_display")

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        highlighted().assertTextContains("Display and playback")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        highlighted().assertTextContains("Subtitles")
    }

    @Test
    fun `Back from the pane goes back to the sections, and Back from the sections leaves Settings`() {
        show(SettingsMemory(SettingsSection.Display))
        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN)

        Espresso.pressBack()
        highlighted().assertTextContains("Display and playback")
        assertThat(leftSettings).isEqualTo(0)

        Espresso.pressBack()
        assertThat(leftSettings).isEqualTo(1)
    }

    @Test
    fun `OK on a value opens a panel with the current choice ticked, and Back closes it`() {
        show(SettingsMemory(SettingsSection.Display))
        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)

        // The panel is a window of its own, which keeps its own Highlight: on the ticked choice.
        compose.onNode(hasText("Off") and isSelected() and isFocused()).assertExists()
        captureScreen("settings_value_panel")

        Espresso.pressBack()
        highlighted().assertTextContains("Sleep timer")
    }

    @Test
    fun `leaving Settings and coming back returns to the section it was on`() {
        val memory = SettingsMemory()
        var showing by mutableStateOf(true)
        compose.setTvContent {
            view = LocalView.current
            if (showing) SettingsScreen(memory = memory, onBack = { showing = false })
        }
        press(
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
        )
        Espresso.pressBack()
        compose.runOnIdle { assertThat(showing).isFalse() }

        showing = true

        highlighted().assertTextContains("Recording")
        paneTitle().assertTextContains("Recording")
    }
}
