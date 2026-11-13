/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.content.Context
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import app.opentv.core.ServiceLocator
import app.opentv.data.remote.OpenSubtitlesClient
import app.opentv.data.repo.DownloadedSubtitles
import app.opentv.data.repo.OpenSubtitles
import app.opentv.data.repo.OpenSubtitlesAccount
import app.opentv.ui.settings.OPENSUBTITLES_PANE_TAG
import app.opentv.ui.settings.OPENSUBTITLES_SIGN_IN_HERE_TAG
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsScreen
import app.opentv.ui.settings.SettingsSection
import com.google.common.truth.Truth.assertThat
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

    // Built here rather than by the graph: the Android Keystore the graph's sealer uses isn't in Robolectric.
    private val openSubtitles = OpenSubtitles(
        OpenSubtitlesAccount(graph.settings, OpenSubtitlesClient(site.http, "k", "0.5.0"), FakeSealer()),
        DownloadedSubtitles(app.filesDir.resolve("subtitles")),
        graph.catalogRepository,
        graph.sourceRepository,
    )

    @After
    fun close() = graph.database.close()

    private fun show(with: OpenSubtitles? = openSubtitles) = compose.setTvContent {
        view = LocalView.current
        SettingsScreen(memory = SettingsMemory(SettingsSection.OpenSubtitles), onBack = {}, openSubtitles = with)
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

    private fun waitFor(text: String, substring: Boolean = false) = compose.waitUntil(5_000) {
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

    @Test
    fun `the automatic download switch turns off and stays off`() {
        show()

        compose.onNodeWithText("Fetch missing subtitles by themselves").performClick()
        compose.waitForIdle()

        assertThat(openSubtitles.account.autoDownload.value).isFalse()
    }

    @Test
    fun `a build without Settee's key has no OpenSubtitles section`() {
        show(with = null)

        compose.onNodeWithText("Subtitles for films and episodes that have none").assertDoesNotExist()
        compose.onNodeWithTag(OPENSUBTITLES_PANE_TAG).assertDoesNotExist()
    }

    private companion object {
        const val SETTINGS = "opensubtitles-settings-test"
    }
}
