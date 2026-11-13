/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.View
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import app.opentv.ui.onboarding.OpenSubtitlesStepScreen
import app.opentv.ui.settings.OPENSUBTITLES_SIGN_IN_HERE_TAG
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Setup's OpenSubtitles step, against [FakeOpenSubtitles]. Placeholder account only. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpenSubtitlesStepTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val site = FakeOpenSubtitles().apply {
        replies["POST /login"] = """{"user": {"allowed_downloads": 20, "remaining_downloads": 20}, "token": "t"}"""
    }
    private val graph = ServiceLocator.Graph(app, http = site.http, settingsFile = "opensubtitles-step-test")

    // Built here rather than by the graph: the Android Keystore the graph's sealer uses isn't in Robolectric.
    private val openSubtitles = OpenSubtitles(
        OpenSubtitlesAccount(graph.settings, OpenSubtitlesClient(site.http, "k", "0.5.0"), FakeSealer()),
        DownloadedSubtitles(app.filesDir.resolve("subtitles")),
        graph.catalogRepository,
        graph.sourceRepository,
    )

    @After
    fun close() = graph.database.close()

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

    @Test
    fun `signing in says so and waits on Carry on, so there's time to read it`() {
        var done = false
        compose.setTvContent { OpenSubtitlesStepScreen(graph, openSubtitles, onDone = { done = true }) }

        compose.onNodeWithTag(OPENSUBTITLES_SIGN_IN_HERE_TAG).performClick()
        entryKeyboard().run {
            type("u")
            pressAction()
        }
        entryKeyboard().run {
            type("p")
            pressAction()
        }

        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Carry on") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Signed in as u").assertExists()
        assertThat(done).isFalse()
        captureScreen("onboarding_opensubtitles_signed_in")

        compose.onNodeWithText("Carry on").performClick()

        assertThat(done).isTrue()
    }
}
