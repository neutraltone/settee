/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.CrashReports
import app.opentv.ui.CrashQuestion
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The question the launch after a crash asks, while Crash Reports are off (ADR 0009): Send,
 * Don't send or Always send, answered with the remote. What a sent report holds is CrashReportsTest's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrashQuestionTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose, name = "crash-question-test")

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = testGraph.graph.settings
    private val sent = mutableListOf<String>()
    private val launches = mutableListOf<CrashReports>()

    private fun launch(dsn: String = DSN) = CrashReports(
        settings,
        dsn,
        release = "1.4.0",
        environment = "test",
        outbox = { synchronized(sent) { sent += it } },
        // The test's main looper only runs when the test lets it: no freeze is a freeze here.
        freezes = CrashReports.Freezes(notResponding = { false }),
        background = Runnable::run,
    ).also {
        launches.lastOrNull()?.close()
        launches += it
        it.start(app)
    }

    /** Settee crashing while reports are off, and opening again. */
    private fun launchAfterCrash(dsn: String = DSN): CrashReports {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        launch(dsn)
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        if (handler !==
            before
        ) {
            handler?.uncaughtException(Thread.currentThread(), IllegalStateException("Settee broke"))
        }
        return launch(dsn)
    }

    private fun show(crashReports: CrashReports) = compose.setTvContent { CrashQuestion(crashReports) }

    private fun sentCount() = synchronized(sent) { sent.size }

    @After
    fun tearDown() {
        launches.lastOrNull()?.let {
            it.setOn(false)
            it.close()
        }
    }

    @Test
    fun `after a crash, the next launch asks in a Side Panel with the Highlight on Send`() {
        show(launchAfterCrash())

        compose.onNodeWithText("Send a crash report?").assertIsInSidePanel()
        compose.onNodeWithText("Never your provider", substring = true).assertExists()
        compose.onNodeWithText("Send").assertIsFocused()
        compose.onNodeWithText("Don't send").assertExists()
        compose.onNodeWithText("Always send").assertExists()
        captureScreen("crash_question")
    }

    @Test
    fun `Send sends the report and the question goes`() {
        val crashReports = launchAfterCrash()
        show(crashReports)

        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(sentCount()).isEqualTo(1)
        assertThat(crashReports.on.value).isFalse()
        compose.onNodeWithText("Send a crash report?").assertDoesNotExist()
    }

    @Test
    fun `Don't send, a press down, sends nothing and the question goes`() {
        show(launchAfterCrash())

        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Don't send").assertIsFocused()
        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(sentCount()).isEqualTo(0)
        assertThat(settings.crashReportsRefusals).isEqualTo(1)
        compose.onNodeWithText("Send a crash report?").assertDoesNotExist()
    }

    @Test
    fun `Always send sends the report and turns reports on`() {
        val crashReports = launchAfterCrash()
        show(crashReports)

        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Always send").assertIsFocused()
        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(sentCount()).isEqualTo(1)
        assertThat(crashReports.on.value).isTrue()
        assertThat(settings.crashReportsOn.value).isTrue()
        compose.onNodeWithText("Send a crash report?").assertDoesNotExist()
    }

    @Test
    fun `Back answers nothing, so nothing is sent and the next launch asks again`() {
        show(launchAfterCrash())

        compose.pressBackOnPanel()

        compose.onNodeWithText("Send a crash report?").assertDoesNotExist()
        assertThat(sentCount()).isEqualTo(0)
        assertThat(launch().askAboutHeld.value).isTrue()
    }

    @Test
    fun `a build without a DSN never asks`() {
        show(launchAfterCrash(dsn = ""))

        compose.onNodeWithText("Send a crash report?").assertDoesNotExist()
    }

    @Test
    fun `with reports on, a crash is sent and nothing is asked`() {
        settings.setCrashReportsOn(true)
        show(launchAfterCrash())

        compose.onNodeWithText("Send a crash report?").assertDoesNotExist()
        assertThat(sentCount()).isEqualTo(1)
    }

    private companion object {
        const val DSN = "https://k@sentry.example.org/1"
    }
}
