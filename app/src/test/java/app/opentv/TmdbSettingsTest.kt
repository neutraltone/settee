/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import app.opentv.core.ServiceLocator
import app.opentv.data.remote.TmdbCheck
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

/**
 * Settings › TMDB: the TMDB Key added from a phone or typed on the TV, saved only once TMDB accepts
 * it, and replaced or removed (#239). TMDB's answers come from [tmdbAnswer], never TMDB itself.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TmdbSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private val graph get() = ServiceLocator.get(ApplicationProvider.getApplicationContext<Application>())

    /** What TMDB says to every key checked, and each key it was asked about. */
    private var tmdbAnswer: TmdbCheck = TmdbCheck.Accepted
    private val tmdbAsked = mutableListOf<String>()

    private fun show(memory: SettingsMemory) = compose.setTvContent {
        view = LocalView.current
        SettingsScreen(
            memory = memory,
            onBack = {},
            checkTmdbKey = { _, key ->
                tmdbAsked += key
                tmdbAnswer
            },
        )
    }

    /** The keyboard over an entry screen, which is a window of its own over Settings. */
    private fun entryKeyboard(): DeviceKeyboard {
        compose.waitForIdle()
        var root: View? = null
        Espresso.onView(ViewMatchers.isRoot()).inRoot(RootMatchers.isDialog()).perform(
            object : ViewAction {
                override fun getConstraints() = ViewMatchers.isRoot()
                override fun getDescription() = "find the entry screen's window"
                override fun perform(controller: UiController, view: View) {
                    root = view
                }
            },
        )
        return DeviceKeyboard(compose) { checkNotNull(root) }
    }

    private fun press(vararg keys: Int) = keys.forEach(device::remote)

    private fun highlighted() = compose.onNode(isFocused())

    private fun waitFor(text: String, substring: Boolean = false) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * A press inside a side panel, on its row showing [text], once that row has the Highlight. The
     * panel is a window of its own, which [device] can't reach: it presses keys on the screen's window.
     */
    private fun pressInPanel(text: String, key: Key) {
        // A key goes to whatever has the Highlight, so wait for it to reach the row first: a panel
        // moves it there a frame after it opens.
        val row = hasText(text) and hasAnyAncestor(isDialog())
        compose.waitUntil(5_000) { compose.onAllNodes(row and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(row).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /**
     * Waits for what a press saved. A condition that only reads the database never lets the main
     * thread run, and the save resumes there between its steps, so on its own it would never finish:
     * each check here lets it catch up first.
     */
    private fun waitUntilSaved(saved: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (true) {
            compose.waitForIdle()
            if (saved()) return
            check(System.currentTimeMillis() < until) { "what was saved still isn't there after 5 seconds" }
            Thread.sleep(10)
        }
    }

    /** Settings › TMDB once its QR code is up, or its server couldn't start, which a test machine may not allow. */
    private fun showTmdb() {
        show(SettingsMemory(SettingsSection.Tmdb))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(SettingsTags.CODE_BLOCK).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText("Try again")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Types [key] on Type it instead's entry screen and saves it. */
    private fun typeTmdbKey(key: String) {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Type it instead").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        entryKeyboard().run {
            type(key)
            pressAction()
        }
    }

    @Test
    fun `TMDB with no key shows the QR code to scan, Type it instead and TMDB's attribution`() {
        graph.settings.removeTmdbApiKey()
        showTmdb()

        val scanning = compose.onAllNodesWithTag(SettingsTags.CODE_BLOCK).fetchSemanticsNodes().isNotEmpty()
        if (scanning) compose.onNodeWithText("Scan with your phone to add your TMDB Key").assertExists()
        compose.onNodeWithText(ATTRIBUTION).assertExists()
        compose.onNodeWithContentDescription("TMDB").assertExists()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        if (scanning) highlighted().assertTextContains("Type it instead")
        captureScreen("settings_tmdb_no_key")
    }

    @Test
    fun `a key typed on the TV is saved once TMDB accepts it, and shows only its last four characters`() {
        graph.settings.removeTmdbApiKey()
        tmdbAnswer = TmdbCheck.Accepted
        showTmdb()

        typeTmdbKey(" $TMDB_KEY ")

        waitUntilSaved { graph.settings.tmdbApiKey.value == TMDB_KEY }
        assertThat(tmdbAsked).containsExactly(TMDB_KEY)
        waitFor("Key saved")
        compose.onNodeWithText("Ends in wxyz").assertExists()
        compose.onNode(hasText(TMDB_KEY, substring = true)).assertDoesNotExist()
        compose.onNodeWithText(ATTRIBUTION).assertExists()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Replace key") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        highlighted().assertTextContains("Remove key")
        captureScreen("settings_tmdb_saved")
    }

    @Test
    fun `a key TMDB turns down is not saved, and the pane says why with its detail`() {
        graph.settings.removeTmdbApiKey()
        tmdbAnswer = TmdbCheck.Rejected
        showTmdb()

        typeTmdbKey(TMDB_KEY)

        waitFor("TMDB didn't accept that key. Check you copied the whole API Key, then try again.")
        compose.onNode(hasText("KeyRejected · HTTP 401", substring = true)).assertExists()
        assertThat(graph.settings.tmdbApiKey.value).isEmpty()
        highlighted().assertExists()
        captureScreen("settings_tmdb_wrong_key")
    }

    @Test
    fun `no answer from TMDB isn't taken for a wrong key`() {
        graph.settings.removeTmdbApiKey()
        tmdbAnswer = TmdbCheck.Unreachable(java.net.UnknownHostException())
        showTmdb()

        typeTmdbKey(TMDB_KEY)

        waitFor("Can't reach TMDB.", substring = true)
        compose.onNode(hasText("UnknownHost", substring = true)).assertExists()
        compose.onNode(hasText("didn't accept", substring = true)).assertDoesNotExist()
        assertThat(graph.settings.tmdbApiKey.value).isEmpty()
    }

    @Test
    fun `Remove key asks first, starting on Keep it, then TMDB is off and its QR code is back`() {
        graph.settings.setTmdbApiKey(TMDB_KEY)
        show(SettingsMemory(SettingsSection.Tmdb))
        waitFor("Key saved")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        highlighted().assertTextContains("Replace key")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        highlighted().assertTextContains("Remove key")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasText("Keep it") and isFocused()).assertExists()
        Espresso.pressBack()
        compose.onNode(hasText("Remove key") and isFocused()).assertExists()
        assertThat(graph.settings.tmdbApiKey.value).isEqualTo(TMDB_KEY)

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        pressInPanel("Keep it", Key.DirectionUp)
        pressInPanel("Yes, remove your TMDB Key", Key.DirectionCenter)

        waitUntilSaved { graph.settings.tmdbApiKey.value.isEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Type it instead")).fetchSemanticsNodes().isNotEmpty() }
        highlighted().assertExists()
    }

    @Test
    fun `Replace key brings the QR code back, and the saved key stays until a new one is accepted`() {
        graph.settings.setTmdbApiKey(TMDB_KEY)
        tmdbAnswer = TmdbCheck.Rejected
        show(SettingsMemory(SettingsSection.Tmdb))
        waitFor("Key saved")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Type it instead")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Type it instead").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        entryKeyboard().run {
            type("e".repeat(32))
            pressAction()
        }

        waitFor("TMDB didn't accept that key. Check you copied the whole API Key, then try again.")
        assertThat(graph.settings.tmdbApiKey.value).isEqualTo(TMDB_KEY)
    }

    private companion object {
        /** A placeholder TMDB Key, low-entropy so gitleaks sees no secret. */
        val TMDB_KEY = "a".repeat(28) + "wxyz"
        const val ATTRIBUTION = "This product uses the TMDB API but is not endorsed or certified by TMDB."
    }
}
