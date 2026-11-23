/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.opentv.core.CrashReports
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsScreen
import app.opentv.ui.settings.SettingsSection
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Privacy: Crash Reports, off until the viewer turns them on (ADR 0009). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivacySettingsTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose, name = "privacy-settings-test")

    private val graph = testGraph.graph
    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }
    private val sent = mutableListOf<String>()

    /** Crash Reports as a build with [dsn] has them; nothing they'd send leaves the test. */
    private fun crashReports(dsn: String): CrashReports {
        graph.sentryDsn = dsn
        graph.crashOutbox = CrashReports.Outbox { sent += it }
        return graph.crashReports
    }

    private fun show(crashReports: CrashReports, section: SettingsSection = SettingsSection.Privacy) =
        compose.setTvContent {
            view = LocalView.current
            SettingsScreen(
                memory = SettingsMemory(section),
                onBack = {},
                openSubtitles = null,
                crashReports = crashReports,
            )
        }

    private fun highlighted() = compose.onNode(isFocused())

    @Test
    fun `Privacy has crash reports off, says what a report holds, and the remote turns them on and off`() {
        val crashReports = crashReports(DSN)
        show(crashReports)

        highlighted().assertTextContains("Privacy")
        compose.onNodeWithText("Send crash reports").assertIsOff()
        compose.onNodeWithText("which version, which device and screen", substring = true).assertExists()
        compose.onNodeWithText("Never your provider", substring = true).assertExists()

        device.remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        highlighted().assertTextContains("Send crash reports")
        captureScreen("settings_privacy")

        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Send crash reports").assertIsOn()
        assertThat(crashReports.on.value).isTrue()
        assertThat(graph.settings.crashReportsInstallId).isNotNull()
        captureScreen("settings_privacy_on")

        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText("Send crash reports").assertIsOff()
        assertThat(crashReports.on.value).isFalse()
        assertThat(sent).isEmpty()
    }

    @Test
    fun `a build without Settee's Sentry DSN has no Privacy section`() {
        show(crashReports(dsn = ""), section = SettingsSection.Providers)

        highlighted().assertTextContains("Providers")
        compose.onAllNodes(hasText("Privacy")).assertCountEquals(0)
    }

    private companion object {
        const val DSN = "https://k@sentry.example.org/1"
    }
}
