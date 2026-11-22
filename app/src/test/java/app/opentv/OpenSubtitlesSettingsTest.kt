/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.content.Context
import android.view.View
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import app.opentv.data.repo.OpenSubtitles
import app.opentv.ui.settings.OPENSUBTITLES_PANE_TAG
import app.opentv.ui.settings.OPENSUBTITLES_SIGN_IN_HERE_TAG
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsScreen
import app.opentv.ui.settings.SettingsSection
import app.opentv.ui.settings.SettingsTags
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › OpenSubtitles, against [FakeOpenSubtitles]. Placeholder account only. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpenSubtitlesSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val site = FakeOpenSubtitles().apply {
        replies["POST /login"] = """{"user": {"allowed_downloads": 20, "remaining_downloads": 18}, "token": "t"}"""
        replies["GET /infos/user"] = """{"data": {"allowed_downloads": 20, "remaining_downloads": 18}}"""
    }
    private val graph = ServiceLocator.Graph(app, http = site.http, settingsFile = SETTINGS)

    private val openSubtitles = site.openSubtitles(graph, app.filesDir)

    @After
    fun close() = graph.database.close()

    private fun show(with: OpenSubtitles? = openSubtitles, section: SettingsSection = SettingsSection.OpenSubtitles) =
        compose.setTvContent {
            view = LocalView.current
            SettingsScreen(memory = SettingsMemory(section), onBack = {}, openSubtitles = with)
        }

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

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun row(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun waitFor(text: String, substring: Boolean = false) = compose.waitUntil(UI_WAIT_MILLIS) {
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun signInOnTheTv(password: String) {
        compose.onNodeWithTag(OPENSUBTITLES_SIGN_IN_HERE_TAG).requestFocus()
        compose.onNodeWithTag(OPENSUBTITLES_SIGN_IN_HERE_TAG).performClick()
        entryKeyboard().run {
            type("u")
            pressAction()
        }
        entryKeyboard().run {
            type(password)
            pressAction()
        }
    }

    @Test
    fun `not signed in, it says what works without an account and offers both ways to sign in`() {
        show()

        compose.onNodeWithTag(OPENSUBTITLES_PANE_TAG).assertExists()
        compose.onNodeWithText("Not signed in. 5 subtitles a day work without an account.").assertExists()
        compose.onNodeWithText("Sign in on this TV").assertExists()
        compose.onNodeWithText("Fetch missing subtitles by themselves").assertExists()
        compose.onNodeWithText("Subtitles provided by OpenSubtitles.com").assertExists()
        captureScreen("settings_opensubtitles")
    }

    @Test
    fun `signing in on the TV shows who's signed in and the downloads left, and Sign out forgets it all`() {
        show()

        signInOnTheTv(password = "p")

        waitFor("Signed in as u")
        waitFor("18 downloads left today")
        captureScreen("settings_opensubtitles_signed_in")

        compose.onNodeWithText("Sign out").performClick()
        waitFor("Not signed in", substring = true)
        val stored = app.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).all.keys
        assertThat(stored.filter { it.startsWith("opensubtitles_") && it != "opensubtitles_auto" }).isEmpty()
    }

    @Test
    fun `a wrong username or password says so, and nothing is kept`() {
        site.replies["POST /login"] = """!401 {"message": "Invalid username/password"}"""
        show()

        signInOnTheTv(password = "wrong")

        waitFor("OpenSubtitles didn't accept that username or password. Check them and try again.")
        assertThat(openSubtitles.account.signedInAs.value).isNull()
    }

    /** Signed in, then OpenSubtitles turns down the token and the saved password alike. */
    private fun signedOutByOpenSubtitles() {
        runBlocking { assertThat(openSubtitles.account.signIn("u", "p").isSuccess).isTrue() }
        site.replies["GET /infos/user"] = """!401 {"message": "invalid token"}"""
        site.replies["POST /login"] = """!401 {"message": "Invalid username/password"}"""
    }

    @Test
    fun `when OpenSubtitles has signed the TV out, it says so and offers Sign in again the remote can reach`() {
        signedOutByOpenSubtitles()
        show()

        waitFor(SIGNED_OUT, substring = true)
        compose.onNodeWithText("Signed in as u").assertExists()
        compose.onNodeWithText("Sign in again").requestFocus().assertIsFocused()
        captureScreen("settings_opensubtitles_signed_out_by_server")
    }

    @Test
    fun `Sign in again asks for the password under the saved username, then shows the downloads left`() {
        signedOutByOpenSubtitles()
        show()
        waitFor("Sign in again")
        site.replies["POST /login"] = """{"user": {"allowed_downloads": 20, "remaining_downloads": 9}, "token": "t2"}"""

        compose.onNodeWithText("Sign in again").requestFocus()
        compose.onNodeWithText("Sign in again").performClick()
        entryKeyboard().run {
            assertThat(textShownBy()).isEqualTo("u")
            pressAction()
        }
        entryKeyboard().run {
            type("new password")
            pressAction()
        }

        waitFor("9 downloads left today")
        compose.onNodeWithText(SIGNED_OUT, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Sign in again").assertDoesNotExist()
        // The row it was on has gone, so the highlight moves to the one that took its place.
        row("Sign out").assertIsFocused()
        assertThat(site.bodies.last()).contains("\"username\":\"u\"")
    }

    @Test
    fun `when OpenSubtitles can't be reached, it says why under the account and offers no Sign in again`() {
        runBlocking { assertThat(openSubtitles.account.signIn("u", "p").isSuccess).isTrue() }
        site.replies["GET /infos/user"] = "!"
        show()

        waitFor("Can't reach OpenSubtitles.", substring = true)
        compose.onNodeWithText("IO").assertExists()
        // The error stands in for the downloads left, rather than under an empty line where they'd be.
        compose.onAllNodes(hasText("") and hasAnyAncestor(hasTestTag(SettingsTags.CODE_BLOCK)), useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onNodeWithText("Sign in again").assertDoesNotExist()
        compose.onNodeWithText("Sign out").assertExists()
        captureScreen("settings_opensubtitles_unreachable")
    }

    @Test
    fun `signing out and in again starts afresh, without the last account's error or Sign in again`() {
        signedOutByOpenSubtitles()
        show()
        waitFor("Sign in again")

        compose.onNodeWithText("Sign out").performClick()
        waitFor("Not signed in", substring = true)
        site.replies["POST /login"] =
            """{"user": {"allowed_downloads": 20, "remaining_downloads": 18}, "token": "t2"}"""
        site.replies["GET /infos/user"] = """{"data": {"allowed_downloads": 20, "remaining_downloads": 18}}"""
        val held = java.util.concurrent.CountDownLatch(1)
        site.accountHeld = held
        try {
            signInOnTheTv(password = "p")
            waitFor("Signed in as u")

            compose.onNodeWithText(SIGNED_OUT, substring = true).assertDoesNotExist()
            compose.onNodeWithText("Sign in again").assertDoesNotExist()
        } finally {
            held.countDown()
        }
    }

    @Test
    fun `the remote goes from the section to Sign in again, then down through Sign out to automatic downloads`() {
        signedOutByOpenSubtitles()
        show()
        waitFor("Sign in again")
        // The error arriving leaves the highlight on the section it started on.
        compose.onNode(hasTestTag(SettingsTags.SECTION) and hasText("OpenSubtitles")).assertIsFocused()

        press(Key.DirectionRight)
        row("Sign in again").assertIsFocused()
        press(Key.DirectionDown)
        row("Sign out").assertIsFocused()
        press(Key.DirectionDown)
        row("Fetch missing subtitles by themselves").assertIsFocused()
        press(Key.DirectionUp, times = 2)
        row("Sign in again").assertIsFocused()
    }

    @Test
    fun `the automatic download switch turns off and stays off`() {
        show()

        compose.onNodeWithText("Fetch missing subtitles by themselves").performClick()
        compose.waitForIdle()

        assertThat(openSubtitles.account.autoDownload.value).isFalse()
    }

    @Test
    fun `a build without Settee's key has no OpenSubtitles section`() {
        // On Providers: without a key nothing leads to the OpenSubtitles section.
        show(with = null, section = SettingsSection.Providers)

        compose.onNode(hasTestTag(SettingsTags.SECTION) and hasText("OpenSubtitles")).assertDoesNotExist()
        compose.onNode(hasTestTag(SettingsTags.SECTION) and hasText("Add-ons")).assertExists()
        compose.onNodeWithTag(OPENSUBTITLES_PANE_TAG).assertDoesNotExist()
    }

    private companion object {
        const val SETTINGS = "opensubtitles-settings-test"

        // The app's one graph words errors for a TV or a phone by whichever test built it first, so these
        // match what both say; OpenSubtitlesSignInTest pins the TV's sentence.
        const val SIGNED_OUT = "out. Sign in again in Settings."
    }
}
