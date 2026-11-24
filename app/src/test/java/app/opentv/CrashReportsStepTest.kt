/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.espresso.Espresso
import app.opentv.core.CrashReports
import app.opentv.ui.onboarding.CrashReportsStepScreen
import app.opentv.ui.onboarding.STEP_ACTIONS
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Setup's last step, offering Crash Reports (ADR 0009): pressing straight through leaves them off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrashReportsStepTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose, name = "crash-reports-step-test")

    private val graph = testGraph.graph
    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }
    private val sent = mutableListOf<String>()

    private val crashReports: CrashReports by lazy {
        CrashReports(graph.settings, CrashReports.Destination(DSN, CrashReports.Outbox { sent += it }), "1.4.0", "test")
    }

    private var done = false
    private var back = false

    @After
    fun turnOff() {
        crashReports.setOn(false)
    }

    private fun show() = compose.setTvContent {
        view = LocalView.current
        CrashReportsStepScreen(crashReports, onDone = { done = true }, onBack = { back = true })
    }

    private fun highlighted() = compose.onNode(isFocused())

    private fun button(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(STEP_ACTIONS)))

    @Test
    fun `the step says what a report holds, with Not now highlighted, and OK there leaves reports off`() {
        show()

        compose.onNodeWithText("which version, which device and screen", substring = true).assertExists()
        compose.onNodeWithText("Never your provider", substring = true).assertExists()
        button("Send crash reports").assertExists()
        compose.onAllNodes(hasText("Not now") and isFocused()).onFirst().assertExists()
        captureScreen("onboarding_crash_reports_step")

        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(done).isTrue()
        assertThat(crashReports.on.value).isFalse()
        assertThat(graph.settings.crashReportsInstallId).isNull()
    }

    @Test
    fun `Right then OK sends crash reports, turning on Settings' Privacy switch, and moves on`() {
        show()
        compose.onAllNodes(hasText("Not now") and isFocused()).onFirst().assertExists()

        device.remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        highlighted().assertTextContains("Send crash reports")
        captureScreen("onboarding_crash_reports_step_send")
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(done).isTrue()
        assertThat(crashReports.on.value).isTrue()
        assertThat(graph.settings.crashReportsOn.value).isTrue()
        assertThat(sent).isEmpty()
    }

    @Test
    fun `Back goes back a step, choosing nothing`() {
        show()
        compose.onAllNodes(hasText("Not now") and isFocused()).onFirst().assertExists()

        Espresso.pressBack()

        assertThat(back).isTrue()
        assertThat(done).isFalse()
        assertThat(crashReports.on.value).isFalse()
    }

    private companion object {
        const val DSN = "https://k@sentry.example.org/1"
    }
}
