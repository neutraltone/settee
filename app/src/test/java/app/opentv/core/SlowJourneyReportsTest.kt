/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.app.ActivityManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.JourneyTimer.Journey
import com.google.common.truth.Truth.assertThat
import io.sentry.Sentry
import io.sentry.hints.AbnormalExit
import io.sentry.util.HintUtils
import io.sentry.exception.ExceptionMechanismException
import io.sentry.protocol.Mechanism
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Slow Journeys (#634, ADR 0010): a journey whose own time, less its network wait, passes its threshold
 * reaches Sentry once, with its times, the screen, free memory and the Source's kind, and nothing else
 * ever does. Timed on a fake clock, caught by a fake outbox as [CrashReportsTest] catches crashes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SlowJourneyReportsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = AppSettings.separate(app, "slow-journey-reports-test")
    private val sent = mutableListOf<String>()
    private var clock = 1_000_000L
    private val reports = CrashReports(
        settings,
        CrashReports.Destination(
            "https://k@sentry.example.org/1",
            CrashReports.Outbox { envelope -> synchronized(sent) { sent += envelope } },
        ),
        release = "1.4.0",
        environment = "test",
        CrashReports.Freezes(elapsed = { clock }),
    ).also { it.start(app) }
    private val timer = JourneyTimer(now = { clock }, slow = reportingTo(reports))

    private fun sentEvents() = synchronized(sent) { sent.toList() }

    private fun freeMemory(bytes: Long) {
        shadowOf(app.getSystemService(ActivityManager::class.java))
            .setMemoryInfo(ActivityManager.MemoryInfo().apply { availMem = bytes })
    }

    /** [journey] taking [millis] of Settee's own time, of which [network] waiting on a call. */
    private fun run(journey: Journey, millis: Long, network: Long = 0, source: ReportedSource? = null) {
        timer.start(journey)
        if (network > 0) {
            val call = timer.network.create(CALL)
            call.callStart(CALL)
            clock += network
            call.callEnd(CALL)
        }
        clock += millis
        timer.end(journey, source)
    }

    @After
    fun tearDown() {
        reports.setOn(false)
        reports.close()
    }

    @Test
    fun `a journey whose own part passes its threshold is reported with its times, screen, memory and source`() {
        reports.setOn(true)
        reports.screenOpened("main")
        freeMemory(123_000_000)

        // 6 s threshold: 7.34 s of Settee's own, plus 2.06 s waiting on the network.
        run(Journey.COLD_START, millis = 7_340, network = 2_060, source = ReportedSource.XTREAM)

        val event = sentEvents().single()
        assertThat(event).contains("SLOW_JOURNEY: cold-start")
        assertThat(event).contains("\"journey\":\"cold-start\"")
        assertThat(event).contains("\"own-ms\":\"7300\"")
        assertThat(event).contains("\"network-ms\":\"2100\"")
        assertThat(event).contains("\"source-kind\":\"xtream\"")
        assertThat(event).contains("\"screen\":\"main\"")
        assertThat(event).contains("\"free_memory\":123000000")
    }

    @Test
    fun `a journey within its threshold sends nothing`() {
        reports.setOn(true)

        run(Journey.OPEN_MOVIES, millis = 4_900)

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `a journey slow only because it waited on the network sends nothing`() {
        reports.setOn(true)

        run(Journey.CATALOGUE_REFRESH, millis = 2_000, network = 300_000)

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `the HTTP client marks its calls as network wait`() {
        reports.setOn(true)
        // A Provider taking 9 s to answer, on a client marked as the app's is.
        val http = OkHttpClient.Builder()
            .eventListenerFactory(timer.network)
            .addInterceptor { chain ->
                clock += 9_000
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{}".toResponseBody()).build()
            }
            .build()

        timer.start(Journey.PLAYBACK_START)
        http.newCall(Request.Builder().url("https://example.com/live/u/p/1.ts").build()).execute().use {
            it.body.string()
        }
        clock += 1_000
        timer.end(Journey.PLAYBACK_START)

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `a stream playing throughout doesn't hide a slow journey`() {
        reports.setOn(true)
        // The Live Preview's stream, open before Movies is picked and still open after.
        val stream = timer.streaming.create(CALL).apply { callStart(CALL) }

        run(Journey.OPEN_MOVIES, millis = 9_000)
        stream.callEnd(CALL)

        assertThat(sentEvents().single()).contains("\"journey\":\"open-movies\"")
    }

    @Test
    fun `a call that started before the journey isn't its wait`() {
        reports.setOn(true)
        // A guide download already going when Shows is picked, and still going once its row is drawn.
        val download = timer.network.create(CALL).apply { callStart(CALL) }

        run(Journey.OPEN_SHOWS, millis = 9_000)
        download.callEnd(CALL)

        assertThat(sentEvents().single()).contains("\"own-ms\":\"9000\"")
    }

    @Test
    fun `the stream a start of playback waits for is its network wait`() {
        reports.setOn(true)

        timer.start(Journey.PLAYBACK_START)
        val stream = timer.streaming.create(CALL).apply { callStart(CALL) }
        clock += 9_000
        stream.callEnd(CALL)
        clock += 1_000
        timer.end(Journey.PLAYBACK_START)

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `a second slow run of the same journey in one process sends nothing more`() {
        reports.setOn(true)

        run(Journey.OPEN_SHOWS, millis = 9_000)
        run(Journey.OPEN_SHOWS, millis = 12_000)
        run(Journey.PLAYBACK_START, millis = 5_000)

        assertThat(sentEvents()).hasSize(2)
        assertThat(sentEvents().count { it.contains("\"journey\":\"open-shows\"") }).isEqualTo(1)
    }

    @Test
    fun `nothing is sent while crash reports are off`() {
        run(Journey.COLD_START, millis = 30_000)

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `a journey left before its end sends nothing`() {
        reports.setOn(true)

        timer.start(Journey.OPEN_MOVIES)
        clock += 20_000
        timer.cancel(Journey.OPEN_MOVIES)
        timer.end(Journey.OPEN_MOVIES)

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `a slow journey that froze is reported once, as the freeze`() {
        reports.setOn(true)

        timer.start(Journey.COLD_START)
        clock += 5_000
        // What Sentry's freeze watch sends, while the app is still stuck: its own exception type is private.
        val main = Thread.currentThread()
        Sentry.captureException(
            ExceptionMechanismException(Mechanism().apply { type = "ANR" }, IllegalStateException("Frozen"), main),
        )
        clock += 5_000
        timer.end(Journey.COLD_START)

        val event = sentEvents().single()
        assertThat(event).contains("\"type\":\"ANR\"")
        assertThat(event).doesNotContain("SLOW_JOURNEY")
    }

    @Test
    fun `a freeze from the last launch, reported on this one, doesn't swallow this launch's slow start`() {
        reports.setOn(true)

        timer.start(Journey.COLD_START)
        clock += 5_000
        // Android 11 and later: Sentry finds the freeze that ended the last launch in the exit history.
        val main = Thread.currentThread()
        Sentry.captureException(
            ExceptionMechanismException(Mechanism().apply { type = "ANR" }, IllegalStateException("Frozen"), main),
            HintUtils.createWithTypeCheckHint(EndedLastLaunch(System.currentTimeMillis())),
        )
        clock += 5_000
        timer.end(Journey.COLD_START)

        val events = sentEvents()
        assertThat(events).hasSize(2)
        assertThat(events.last()).contains("SLOW_JOURNEY: cold-start")
    }

    /** What Sentry's exit-history reader marks a freeze it found with: its own hint is private. */
    private class EndedLastLaunch(private val at: Long) : AbnormalExit {
        override fun mechanism() = "anr_foreground"

        override fun ignoreCurrentThread() = false

        override fun timestamp() = at
    }

    @Test
    fun `a build without a DSN sends none`() {
        val blank = CrashReports(settings, CrashReports.Destination(""), "1.4.0", "test")
        blank.setOn(true)

        JourneyTimer(now = { clock }, slow = reportingTo(blank)).run {
            start(Journey.COLD_START)
            clock += 30_000
            end(Journey.COLD_START)
        }

        assertThat(sentEvents()).isEmpty()
    }

    /** As the app hands [to] a slow journey. */
    private fun reportingTo(to: CrashReports): (JourneyTimer.SlowJourney) -> Unit =
        { to.report(it) }

    private companion object {
        val CALL: okhttp3.Call = OkHttpClient().newCall(Request.Builder().url("https://example.com/").build())
    }
}
