/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.TestGraph
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The daily cap (#635, ADR 0010): handled errors, Slow Journeys and Error Reports together reach
 * Sentry at most 20 times a day from one install, so one struggling stick can't use up the free plan.
 * Crashes and freezes are never capped. Caught by a fake outbox, as [CrashReportsTest] catches crashes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DailyCapReportsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Only to let the main thread run until it's idle, as it is once Settee has launched. */
    @get:Rule
    val mainThread = TestGraph(swapIn = false)
    private val settings = AppSettings.separate(app, "daily-cap-reports-test")
    private val sent = mutableListOf<String>()
    private var now = MONDAY_NOON
    private var before: Thread.UncaughtExceptionHandler? = null
    private lateinit var reports: CrashReports

    @Before
    fun setUp() {
        before = Thread.getDefaultUncaughtExceptionHandler()
        reports = CrashReports(
            settings,
            CrashReports.Destination(
                "https://k@sentry.example.org/1",
                CrashReports.Outbox { envelope -> synchronized(sent) { sent += envelope } },
                cap = DailyCap(now = { now }),
            ),
            release = "1.4.0",
            environment = "test",
            CrashReports.Freezes(notResponding = { false }),
            background = Runnable::run,
        ).also { it.start(app) }
    }

    @After
    fun tearDown() {
        reports.setOn(false)
        reports.close()
    }

    private fun sentEvents() = synchronized(sent) { sent.toList() }

    private fun parseFailure() =
        reports.report(CaughtError(CaughtError.Kind.XTREAM_PARSE, IllegalStateException("bad JSON")))

    private fun errorReport() = reports.report(ErrorReport("HTTP 503", ReportedSource.STREMIO, ErrorReport.SentBy.VIEWER))

    /** The app crashing: whatever handler is installed hears of it, as the JVM would tell it. */
    private fun crash(error: Throwable = IllegalStateException("Settee broke")) {
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        if (handler !== before) handler?.uncaughtException(Thread.currentThread(), error)
    }

    @Test
    fun `the 21st report that isn't a crash in a day doesn't arrive, whatever its kind`() {
        reports.setOn(true)

        repeat(10) { parseFailure() }
        repeat(10) { errorReport() }
        assertThat(sentEvents()).hasSize(20)

        parseFailure()
        errorReport()

        assertThat(sentEvents()).hasSize(20)
    }

    @Test
    fun `one the next day arrives`() {
        reports.setOn(true)
        repeat(21) { parseFailure() }
        assertThat(sentEvents()).hasSize(20)

        now += DAY

        parseFailure()
        assertThat(sentEvents()).hasSize(21)
    }

    @Test
    fun `crashes over the cap still arrive`() {
        reports.setOn(true)
        repeat(20) { parseFailure() }

        crash()

        assertThat(sentEvents()).hasSize(21)
        assertThat(sentEvents().last()).contains("Settee broke")
    }

    @Test
    fun `an Error Report over the cap, with crash reports off, sends nothing and leaves them off`() {
        repeat(20) { errorReport() }
        assertThat(sentEvents()).hasSize(20)

        errorReport()

        assertThat(sentEvents()).hasSize(20)
        assertThat(reports.on.value).isFalse()
    }

    @Test
    fun `reports dropped while crash reports are off don't count towards the cap`() {
        repeat(25) { parseFailure() }
        assertThat(sentEvents()).isEmpty()

        reports.setOn(true)
        repeat(20) { parseFailure() }

        assertThat(sentEvents()).hasSize(20)
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L

        /** Monday 23 November 2026, 12:00 UTC. */
        const val MONDAY_NOON = 1_795_435_200_000L
    }
}
