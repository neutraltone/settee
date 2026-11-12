/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.KeyboardType
import androidx.test.espresso.Espresso
import app.opentv.ui.design.EntryScreen
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Typing text for a setting: one box, with the device's keyboard, and a Save below it (#168). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EntryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private var saved: String? = null
    private var cancelled = 0

    private fun show(
        initial: String = "",
        keyboardType: KeyboardType = KeyboardType.Text,
        maxLength: Int? = null,
        refusal: (String) -> String? = { null },
    ) {
        compose.setTvContent {
            view = LocalView.current
            EntryScreen(
                title = "TV guide address",
                initial = initial,
                saveLabel = "Save",
                onSave = { saved = it },
                onCancel = { cancelled++ },
                hint = "You can also set this from your phone, in Manage on phone or laptop",
                keyboardType = keyboardType,
                maxLength = maxLength,
                refusal = refusal,
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `it opens with the current value in a highlighted box`() {
        show(initial = "http://example.com/guide.xml")

        assertThat(device.textShownBy()).isEqualTo("http://example.com/guide.xml")
        assertThat(device.highlighted()).isTrue()
        captureScreen("entry_screen")
    }

    @Test
    fun `the keyboard's Done key saves what was typed`() {
        show()

        device.type("Alex")
        device.pressAction()

        assertThat(saved).isEqualTo("Alex")
    }

    // A single-line EditText takes Down to move its cursor to the end first, which would leave a
    // viewer pressing Down twice to reach Save.
    @Test
    fun `Down from the box reaches Save straight away, which saves`() {
        show(initial = "Sam")

        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Save").assertIsFocused()
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(saved).isEqualTo("Sam")
    }

    @Test
    fun `nothing blank is saved`() {
        show()

        device.type("  ")
        device.pressAction()

        assertThat(saved).isNull()
    }

    @Test
    fun `a refused value can't be saved, and the screen says why`() {
        show(refusal = { if (it.trim() == "Tom") "Another profile is already called Tom." else null })

        device.type("Tom")
        compose.onNodeWithText("Another profile is already called Tom.").assertIsDisplayed()
        captureScreen("entry_screen_refused")
        device.pressAction()
        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(saved).isNull()
    }

    // A name can arrive longer than the box takes, from sync or an older version.
    @Test
    fun `text longer than the limit is cut to fit, however it arrives`() {
        show(initial = "The Very Long Family Room Television", maxLength = 24)
        device.pressAction()
        assertThat(saved).isEqualTo("The Very Long Family Roo")

        device.replaceAll("Another Very Long Name For A Room")
        device.pressAction()
        assertThat(saved).isEqualTo("Another Very Long Name F")
    }

    @Test
    fun `a web address is typed on a keyboard for web addresses`() {
        show(keyboardType = KeyboardType.Uri)

        val (info, _) = device.connection()

        assertThat(info.inputType).isEqualTo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
    }

    @Test
    fun `Back leaves without saving`() {
        show(initial = "Sam")

        device.replaceAll("Alex")
        Espresso.pressBack()

        assertThat(cancelled).isEqualTo(1)
        assertThat(saved).isNull()
    }
}
