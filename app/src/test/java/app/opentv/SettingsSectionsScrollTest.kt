/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.espresso.Espresso
import app.opentv.core.AppSettings
import app.opentv.core.CrashReports
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsScreen
import app.opentv.ui.settings.SettingsSection
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

/**
 * Settings' sections scroll when there are more than a 1080p TV has room for (#386), each section's
 * pane opens at its top (#441), and longer words fit its titles and rows (#507). Beside
 * SettingsScreenTest, which is already as long as a class may be.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSectionsScrollTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose)

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private fun press(vararg keys: Int) = keys.forEach(device::remote)

    private fun highlighted() = compose.onNode(isFocused())

    @Test
    fun `each section comes into view as the Highlight goes down to About, into its settings, and back up`() {
        val app = testGraph.app
        // With OpenSubtitles and Crash Reports, as a build with Settee's keys has them: the most
        // sections Settings has (TMDB's went with #515). Nothing turns reports on, so nothing's sent.
        val openSubtitles = FakeOpenSubtitles().openSubtitles(testGraph.graph, app.filesDir)
        val crashReports = CrashReports(
            testGraph.graph.settings,
            CrashReports.Destination("https://k@sentry.example.org/1", CrashReports.Outbox {}),
            release = "1.4.0",
            environment = "test",
        )
        compose.setTvContent {
            view = LocalView.current
            SettingsScreen(memory = SettingsMemory(), onBack = {
            }, openSubtitles = openSubtitles, crashReports = crashReports)
        }
        val sections = compose.onAllNodesWithTag(SettingsTags.SECTION).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString("") }
        assertThat(sections).hasSize(13)

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

    @Test
    fun `each section opens at its top, its title showing, after the one beside it was scrolled down`() {
        // Saving to a network drive gives Recording enough rows to scroll on a 1080p TV.
        testGraph.graph.settings.setRecordingTarget(AppSettings.RecordingTarget.SMB)
        compose.setTvContent {
            view = LocalView.current
            SettingsScreen(memory = SettingsMemory(SettingsSection.Recording), onBack = {}, openSubtitles = null)
        }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(RECORDING_ROWS) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertThat(paneTitleShowing()).isLessThan(paneTitleHeight())

        press(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP)

        highlighted().assertTextContains("Display and playback")
        assertThat(paneTitleShowing()).isEqualTo(paneTitleHeight())
        captureScreen("settings_section_top")

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertThat(paneTitleShowing()).isEqualTo(paneTitleHeight())
    }

    @Test
    fun `at a larger font size every section's title is shown in full, on two lines where it needs them`() {
        compose.setTvContent {
            AtFontScale(1.3f) { SettingsScreen(memory = SettingsMemory(), onBack = {}, openSubtitles = null) }
        }

        // French would be longer still ("Gérer depuis un téléphone ou un ordinateur"), but the
        // section titles aren't translated yet; a larger font size runs English past one line too.
        val text = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
        val titles = compose.onAllNodes(
            hasAnyAncestor(hasTestTag(SettingsTags.SECTION)) and text,
            useUnmergedTree = true,
        )
        val count = titles.fetchSemanticsNodes().size
        assertThat(count).isEqualTo(compose.onAllNodesWithTag(SettingsTags.SECTION).fetchSemanticsNodes().size)
        repeat(count) { titles[it].assertShownWhole() }
        captureScreen("settings_sections_font_1_3")
    }

    @Test
    fun `a long network drive folder is cut short rather than the label beside it`() {
        val folder = "settee/live/from the sitting room/the big telly in the corner"
        testGraph.graph.settings.setRecordingTarget(AppSettings.RecordingTarget.SMB)
        testGraph.graph.settings.setSmbConfig("192.168.1.20", "recordings", folder, user = "", password = "")
        compose.setTvContent {
            SettingsScreen(memory = SettingsMemory(SettingsSection.Recording), onBack = {}, openSubtitles = null)
        }

        compose.onNodeWithText("Folder inside it", useUnmergedTree = true).assertShownWhole().assertOnOneLine()
        captureScreen("settings_recording_nas_path")
    }

    private fun paneTitle() = compose.onNodeWithTag(SettingsTags.PANE_TITLE).fetchSemanticsNode()

    private fun paneTitleHeight() = paneTitle().size.height

    /** How much of the pane's title is inside the screen, in pixels. */
    private fun paneTitleShowing(): Int {
        val title = paneTitle().boundsInWindow
        val window = compose.onRoot().fetchSemanticsNode().boundsInWindow
        return (minOf(title.bottom, window.bottom) - maxOf(title.top, window.top)).coerceAtLeast(0f).roundToInt()
    }

    private companion object {
        /** More Downs than Recording has rows, so its last ends highlighted. */
        const val RECORDING_ROWS = 20
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
