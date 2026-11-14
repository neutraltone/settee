/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import app.opentv.core.ServiceLocator
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsScreen
import app.opentv.ui.settings.SettingsTags
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings' sections scroll when there are more than a 1080p TV has room for (#386). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSectionsScrollTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private fun press(key: Int) = device.remote(key)

    private fun highlighted() = compose.onNode(isFocused())

    @Test
    fun `each section comes into view as the Highlight goes down to About, into its settings, and back up`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // With OpenSubtitles, as a build with Settee's key has it: more sections than the left
        // side has room for.
        val openSubtitles = FakeOpenSubtitles().openSubtitles(ServiceLocator.get(app), app.filesDir)
        compose.setTvContent {
            view = LocalView.current
            SettingsScreen(memory = SettingsMemory(), onBack = {}, openSubtitles = openSubtitles)
        }
        val sections = compose.onAllNodesWithTag(SettingsTags.SECTION).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString("") }
        assertThat(sections).hasSize(12)

        sections.drop(1).forEach { section ->
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            highlighted().assertTextContains(section)
            compose.assertSectionWhollyOnScreen(section)
        }
        captureScreen("settings_sections_scrolled_to_about")

        // Into About's settings and back, by Left and by Back, with About still in view.
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        highlighted().assertTextContains("Check for updates")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        highlighted().assertTextContains("About")
        compose.assertSectionWhollyOnScreen("About")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        Espresso.pressBack()
        highlighted().assertTextContains("About")
        compose.assertSectionWhollyOnScreen("About")

        sections.reversed().drop(1).forEach { section ->
            press(KeyEvent.KEYCODE_DPAD_UP)
            highlighted().assertTextContains(section)
            compose.assertSectionWhollyOnScreen(section)
        }
    }
}

/**
 * Settings' section row showing [text] is on screen, all of it. Three ways it can fail to be: a
 * column with no room squeezes its last rows shorter than Providers', down to nothing (#386); a
 * scrolling parent cuts its bounds in the window to what shows; and a row past the window's edge
 * reaches outside it.
 */
fun ComposeContentTestRule.assertSectionWhollyOnScreen(text: String) {
    fun section(text: String) = onNode(hasTestTag(SettingsTags.SECTION) and hasText(text)).fetchSemanticsNode()
    val row = section(text)
    val full = section("Providers").size.height
    val shows = row.boundsInWindow
    val window = onRoot().fetchSemanticsNode().boundsInWindow
    assertWithMessage("$text's row's height, against Providers'").that(row.size.height).isEqualTo(full)
    assertWithMessage("how much of $text's row shows").that(shows.height.roundToInt()).isEqualTo(full)
    assertWithMessage("$text's row, $shows, inside the window, $window")
        .that(shows.top >= window.top && shows.bottom <= window.bottom)
        .isTrue()
}
