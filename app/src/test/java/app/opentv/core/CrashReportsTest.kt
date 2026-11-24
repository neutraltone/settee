/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import androidx.test.core.app.ApplicationProvider
import app.opentv.TestGraph
import com.google.common.truth.Truth.assertThat
import io.sentry.Sentry
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager

/**
 * What [CrashReports] would send to Sentry, caught by a fake outbox in place of Sentry's own transport:
 * the envelopes as they'd leave the device, so a scrubber that isn't wired in fails here too.
 * Placeholders only: `u`/`p`, `example.com`, `00:1A:79:00:00:01`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrashReportsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Only to let the main thread run until it's idle, as it is once Settee has launched. */
    @get:Rule
    val mainThread = TestGraph(swapIn = false)
    private val settings = AppSettings.separate(app, "crash-reports-test")
    private val sent = mutableListOf<String>()
    private val outbox = CrashReports.Outbox { envelope -> synchronized(sent) { sent += envelope } }
    private var before: Thread.UncaughtExceptionHandler? = null
    private lateinit var reports: CrashReports

    private val freezesHeld = java.util.concurrent.CountDownLatch(1)

    // The test's main looper only runs when a test lets it, so to the watch it's always frozen: Android
    // never agrees it's a freeze unless a test says so.
    private var freezes = CrashReports.Freezes(afterMillis = 50, notResponding = { false })

    private fun crashReports(dsn: String = DSN, release: String = "1.4.0") =
        CrashReports(
            settings,
            CrashReports.Destination(dsn, outbox),
            release,
            environment = "test",
            freezes,
            background = Runnable::run,
        ).also {
            reports = it
            it.start(app)
        }

    /** Settee opening again after [reports]' process ended, as it does after a crash. */
    private fun nextLaunch(dsn: String = DSN, release: String = "1.4.0"): CrashReports {
        reports.close()
        return crashReports(dsn, release)
    }

    /** The app crashing: whatever handler is installed hears of it, as the JVM would tell it. */
    private fun crash(error: Throwable = IllegalStateException("Settee broke")) {
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        if (handler !== before) handler?.uncaughtException(Thread.currentThread(), error)
    }

    private fun sentEvents() = synchronized(sent) { sent.toList() }

    /**
     * Waits for what Sentry set off as it started, reading Android's exit history among it, to be done:
     * it runs on Sentry's one background thread, so anything handed to that thread after runs after.
     */
    private fun sentryStartingWorkDone() {
        Sentry.getCurrentScopes().options.executorService.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** What Android says is free, as Sentry reads it for a report's free memory. */
    private fun freeMemory(bytes: Long) {
        shadowOf(app.getSystemService(ActivityManager::class.java))
            .setMemoryInfo(ActivityManager.MemoryInfo().apply { availMem = bytes })
    }

    private fun freezeWatching() = Thread.getAllStackTraces().keys.any { it.name == "Settee freeze watch" && it.isAlive }

    /** Android ending Settee for not responding at [atMillis], keeping the main thread's stack (Android 11+). */
    private fun androidEndedSetteeForFreeze(atMillis: Long) {
        shadowOf(app.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(
            ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(app.packageName)
                .setPid(FROZEN_PID)
                .setReason(ApplicationExitInfo.REASON_ANR)
                .setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
                .setTimestamp(atMillis)
                .setTraceInputStream(THREAD_DUMP.byteInputStream())
                .build(),
        )
    }

    @Before
    fun setUp() {
        before = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        if (::reports.isInitialized) {
            reports.setOn(false)
            reports.close()
        }
        settings.setCrashReportsOn(false)
    }

    @Test
    fun `off by default, and a crash sends nothing`() {
        crashReports()

        assertThat(reports.on.value).isFalse()
        crash()

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `turned on, a crash is sent`() {
        crashReports().setOn(true)

        crash(IllegalStateException("Settee broke"))

        assertThat(sentEvents()).hasSize(1)
        assertThat(sentEvents().single()).contains("Settee broke")
        assertThat(sentEvents().single()).contains("IllegalStateException")
    }

    @Test
    fun `on when the app starts, a crash is sent without turning it on again`() {
        settings.setCrashReportsOn(true)
        crashReports()

        crash()

        assertThat(sentEvents()).hasSize(1)
    }

    @Test
    fun `turned on and then off again, a crash sends nothing`() {
        crashReports().setOn(true)
        reports.setOn(false)

        crash()

        assertThat(sentEvents()).isEmpty()
        assertThat(settings.crashReportsOn.value).isFalse()
    }

    @Test
    fun `a build without a DSN has no crash reports, and sends nothing even when turned on`() {
        crashReports(dsn = "")

        assertThat(reports.available).isFalse()
        reports.setOn(true)
        crash()

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `provider addresses, logins and MACs in a crash's message leave the device scrubbed`() {
        crashReports().setOn(true)

        crash(
            IllegalStateException(
                "Xtream http://example.com:8080/live/u/p/1234.ts " +
                    "M3U http://u:p@example.com/get.php?username=u&password=p&type=m3u_plus#frag " +
                    "Stalker http://example.com/stalker_portal/server/load.php?type=stb&mac=00:1A:79:00:00:01 " +
                    "box 00:1A:79:00:00:01",
            ),
        )

        val event = sentEvents().single()
        assertThat(event).doesNotContain("example.com")
        assertThat(event).doesNotContain("8080")
        assertThat(event).doesNotContain("username")
        assertThat(event).doesNotContain("password")
        assertThat(event).doesNotContain("u:p@")
        assertThat(event).doesNotContain("frag")
        assertThat(event).doesNotContain("00:1A:79")
        assertThat(event).doesNotContain("/u/p/")
        assertThat(event).contains("Xtream http:///live/*/*/1234.ts")
        assertThat(event).contains("M3U http:///get.php")
        assertThat(event).contains("Stalker http:///stalker_portal/server/load.php")
        assertThat(event).contains("box **:**:**:**:**:**")
    }

    @Test
    fun `a provider address in a cause or a thread name is scrubbed too`() {
        crashReports().setOn(true)
        val cause = java.io.IOException("unexpected end of stream on http://example.com/live/u/p/1.ts")

        val crashing = Thread("OkHttp http://example.com/player_api.php?username=u&password=p")
        crashing.start()
        crashing.join()
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(crashing, RuntimeException("Wrapped", cause))

        val event = sentEvents().single()
        assertThat(event).doesNotContain("example.com")
        assertThat(event).doesNotContain("username")
        assertThat(event).contains("unexpected end of stream on http:///live/*/*/1.ts")
    }

    @Test
    fun `a report carries the install id and nothing else that identifies the viewer`() {
        crashReports().setOn(true)
        reports.screenOpened("seriesDetail/{seriesId}?from=42")

        crash()

        val event = sentEvents().single()
        val installId = settings.crashReportsInstallId
        assertThat(installId).isNotNull()
        assertThat(event).contains("\"user\":{\"id\":\"$installId\"}")
        assertThat(event).doesNotContain("ip_address")
        assertThat(event).doesNotContain("{{auto}}")
        assertThat(event).doesNotContain(settings.syncDeviceId)
        assertThat(event).doesNotContain("\"breadcrumbs\"")
        assertThat(event).doesNotContain("\"extra\"")
        assertThat(event).doesNotContain("\"server_name\"")
        assertThat(event).doesNotContain("\"request\"")
        assertThat(event).doesNotContain("\"modules\"")
        assertThat(event).contains("\"tags\":{\"screen\":\"seriesDetail\"}")
        assertThat(event).doesNotContain("from=42")
        assertThat(event).contains("\"release\":\"1.4.0\"")
    }

    @Test
    fun `only the allow-listed device, system and app details are sent`() {
        crashReports().setOn(true)

        crash()

        val event = sentEvents().single()
        val contexts = JSONObject(event.lines().single { it.contains("\"contexts\"") }).getJSONObject("contexts")
        // PRIVACY.md's list: the device model, free memory, the Android or Fire OS version, Settee's version.
        val allowed = mapOf(
            "device" to setOf("model", "free_memory"),
            "os" to setOf("name", "version", "build"),
            "app" to setOf("app_version", "app_build"),
        )
        assertThat(contexts.keys().asSequence().toSet()).containsExactlyElementsIn(allowed.keys)
        allowed.forEach { (context, fields) ->
            assertThat(contexts.getJSONObject(context).keys().asSequence().toSet()).isIn(subsetsOf(fields))
        }
        assertThat(contexts.getJSONObject("device").getString("model")).isNotEmpty()
        assertThat(contexts.getJSONObject("app").getString("app_version")).isNotEmpty()
        // Only the event: no session, client report, screenshot or view hierarchy rides along.
        assertThat(event.lines().count { it.contains("\"type\":") && it.contains("\"length\"") })
            .isEqualTo(1)
        assertThat(event).contains("\"type\":\"event\"")
    }

    @Test
    fun `off, a crash is held instead of sent, and the next launch asks about it`() {
        crashReports()

        crash(IllegalStateException("Settee broke"))
        assertThat(sentEvents()).isEmpty()
        assertThat(reports.askAboutHeld.value).isFalse()

        assertThat(nextLaunch().askAboutHeld.value).isTrue()
        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `a launch after no crash doesn't ask`() {
        crashReports()

        assertThat(nextLaunch().askAboutHeld.value).isFalse()
    }

    @Test
    fun `Send sends the held crash, from the release it happened in, and leaves reports off`() {
        crashReports(release = "1.4.0")
        crash(IllegalStateException("Settee broke"))

        nextLaunch(release = "1.5.0").answer(CrashReports.Answer.SEND)

        val event = sentEvents().single()
        assertThat(event).contains("Settee broke")
        assertThat(event).contains("IllegalStateException")
        assertThat(event).contains("\"release\":\"1.4.0\"")
        assertThat(event).contains("\"handled\":false")
        assertThat(reports.askAboutHeld.value).isFalse()
        assertThat(reports.on.value).isFalse()
        assertThat(nextLaunch().askAboutHeld.value).isFalse()
        assertThat(sentEvents()).hasSize(1)
    }

    @Test
    fun `after Send, reports are still off, so the next crash is held and asked about again`() {
        crashReports()
        crash()
        nextLaunch().answer(CrashReports.Answer.SEND)

        crash(IllegalStateException("Broke again"))

        assertThat(sentEvents()).hasSize(1)
        assertThat(nextLaunch().askAboutHeld.value).isTrue()
    }

    @Test
    fun `a held crash leaves the device scrubbed when sent`() {
        crashReports()
        crash(IllegalStateException("Xtream http://example.com:8080/live/u/p/1234.ts box 00:1A:79:00:00:01"))

        nextLaunch().answer(CrashReports.Answer.SEND)

        val event = sentEvents().single()
        assertThat(event).doesNotContain("example.com")
        assertThat(event).doesNotContain("00:1A:79")
        assertThat(event).doesNotContain("/u/p/")
        assertThat(event).contains("Xtream http:///live/*/*/1234.ts box **:**:**:**:**:**")
    }

    @Test
    fun `Don't send throws the held crash away and sends nothing`() {
        crashReports()
        crash()

        nextLaunch().answer(CrashReports.Answer.DONT_SEND)

        assertThat(reports.askAboutHeld.value).isFalse()
        assertThat(nextLaunch().askAboutHeld.value).isFalse()
        reports.answer(CrashReports.Answer.SEND)
        assertThat(sentEvents()).isEmpty()
        assertThat(reports.on.value).isFalse()
    }

    @Test
    fun `Always send sends the held crash and turns reports on, so the next crash is sent without asking`() {
        crashReports()
        crash(IllegalStateException("Settee broke"))

        nextLaunch().answer(CrashReports.Answer.ALWAYS_SEND)

        assertThat(sentEvents()).hasSize(1)
        assertThat(sentEvents().single()).contains("Settee broke")
        assertThat(reports.on.value).isTrue()
        assertThat(settings.crashReportsInstallId).isNotNull()

        crash(IllegalStateException("Broke again"))
        assertThat(sentEvents()).hasSize(2)
        assertThat(nextLaunch().askAboutHeld.value).isFalse()
    }

    @Test
    fun `after two Don't sends the question never comes back`() {
        crashReports()
        crash()
        nextLaunch().answer(CrashReports.Answer.DONT_SEND)
        crash()
        nextLaunch().answer(CrashReports.Answer.DONT_SEND)

        crash()

        assertThat(nextLaunch().askAboutHeld.value).isFalse()
        assertThat(sentEvents()).isEmpty()
        // Settings can still turn them on.
        reports.setOn(true)
        crash()
        assertThat(sentEvents()).hasSize(1)
    }

    @Test
    fun `one Don't send still asks after the next crash`() {
        crashReports()
        crash()
        nextLaunch().answer(CrashReports.Answer.DONT_SEND)

        crash()

        assertThat(nextLaunch().askAboutHeld.value).isTrue()
    }

    @Test
    fun `on, a crash is sent and the next launch doesn't ask`() {
        crashReports().setOn(true)

        crash()

        assertThat(sentEvents()).hasSize(1)
        assertThat(nextLaunch().askAboutHeld.value).isFalse()
    }

    @Test
    fun `turning reports off throws away a held crash`() {
        crashReports()
        crash()
        nextLaunch()

        reports.setOn(false)

        assertThat(nextLaunch().askAboutHeld.value).isFalse()
        reports.answer(CrashReports.Answer.SEND)
        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `turning reports on in Settings sends the crash held while they were off, as Always send would`() {
        crashReports()
        crash(IllegalStateException("Settee broke"))

        nextLaunch().setOn(true)

        assertThat(sentEvents()).hasSize(1)
        assertThat(sentEvents().single()).contains("Settee broke")
        assertThat(reports.askAboutHeld.value).isFalse()
        assertThat(nextLaunch().askAboutHeld.value).isFalse()
        assertThat(sentEvents()).hasSize(1)
    }

    @Test
    fun `a held crash is sent with the screen and free memory from when it happened, not from when it's sent`() {
        crashReports()
        reports.screenOpened("guide")
        freeMemory(111_000_000)
        crash()

        nextLaunch().screenOpened("main")
        freeMemory(222_000_000)
        reports.answer(CrashReports.Answer.SEND)

        val event = sentEvents().single()
        assertThat(event).contains("\"tags\":{\"screen\":\"guide\"}")
        assertThat(event).contains("\"free_memory\":111000000")
        assertThat(event).doesNotContain("222000000")
    }

    @Test
    fun `a crash held before any screen opened is sent without one`() {
        crashReports()
        crash()

        nextLaunch().screenOpened("main")
        reports.answer(CrashReports.Answer.SEND)

        assertThat(sentEvents().single()).doesNotContain("\"screen\"")
    }

    @Test
    fun `a build without a DSN holds nothing and never asks`() {
        crashReports(dsn = "")

        crash()

        assertThat(nextLaunch(dsn = "").askAboutHeld.value).isFalse()
        assertThat(sentEvents()).isEmpty()
    }

    @Test
    @Config(sdk = [25])
    fun `off, before Android 11, a freeze is watched for once Settee has launched, and Send sends it with the main thread's stack`() {
        val notFreezing = freezes
        // Anything the main thread sits on for 50ms here, and Android agrees it's a freeze.
        freezes = CrashReports.Freezes(afterMillis = 50, notResponding = { true }, held = freezesHeld::countDown)
        crashReports()
        // Nothing watches until the main thread has done the launch's work queued ahead of the watch.
        assertThat(freezeWatching()).isFalse()

        mainThread.awaitIdle()

        assertThat(freezeWatching()).isTrue()
        // The test thread is the main thread, and its looper only runs when the test lets it: it's frozen.
        assertThat(freezesHeld.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        freezes = notFreezing
        nextLaunch().answer(CrashReports.Answer.SEND)

        val event = sentEvents().single()
        assertThat(event).contains("ApplicationNotResponding")
        assertThat(event).contains("\"type\":\"ANR\"")
        assertThat(event).contains("CrashReportsTest")
    }

    @Test
    fun `off, from Android 11 nothing watches the main thread, and a freeze Android ended Settee for is held from its history`() {
        crashReports()
        mainThread.awaitIdle()
        assertThat(freezeWatching()).isFalse()

        androidEndedSetteeForFreeze(atMillis = System.currentTimeMillis() + 1)

        assertThat(nextLaunch().askAboutHeld.value).isTrue()
        reports.answer(CrashReports.Answer.SEND)
        val event = sentEvents().single()
        assertThat(event).contains("ApplicationNotResponding")
        assertThat(event).contains("\"type\":\"ANR\"")
        assertThat(event).contains("HomeScreen")
        assertThat(event).contains("\"handled\":false")
    }

    @Test
    fun `a freeze from before Settee first looked isn't held, since nobody knows what the viewer said then`() {
        androidEndedSetteeForFreeze(atMillis = System.currentTimeMillis() - 60_000)

        assertThat(crashReports().askAboutHeld.value).isFalse()
        assertThat(nextLaunch().askAboutHeld.value).isFalse()
    }

    @Test
    fun `a freeze from while reports were off isn't sent by Sentry once they're turned on`() {
        androidEndedSetteeForFreeze(atMillis = System.currentTimeMillis() - 60_000)
        crashReports()

        reports.setOn(true)
        sentryStartingWorkDone()

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `a freeze from while reports were on is sent by Sentry at the next launch`() {
        settings.setCrashReportsOn(true, atMillis = System.currentTimeMillis() - 120_000)
        androidEndedSetteeForFreeze(atMillis = System.currentTimeMillis() - 60_000)

        crashReports()
        sentryStartingWorkDone()

        assertThat(sentEvents()).hasSize(1)
        assertThat(sentEvents().single()).contains("HomeScreen")
    }

    @Test
    fun `turning reports off throws away a report still waiting to go`() {
        val waiting = mutableListOf<Runnable>()
        reports = CrashReports(
            settings,
            CrashReports.Destination(DSN, outbox, queue = { synchronized(waiting) { waiting += it } }),
            "1.4.0",
            environment = "test",
            freezes,
            background = Runnable::run,
        ).also { it.start(app) }
        reports.setOn(true)
        reports.report(CrashReports.Handled.XTREAM_PARSE, IllegalStateException("Unexpected JSON token"))
        assertThat(synchronized(waiting) { waiting.size }).isEqualTo(1)

        reports.setOn(false)
        synchronized(waiting) { waiting.toList() }.forEach(Runnable::run)

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `a report waiting to go while reports stay on goes when its turn comes`() {
        val waiting = mutableListOf<Runnable>()
        reports = CrashReports(
            settings,
            CrashReports.Destination(DSN, outbox, queue = { synchronized(waiting) { waiting += it } }),
            "1.4.0",
            environment = "test",
            freezes,
            background = Runnable::run,
        ).also { it.start(app) }
        reports.setOn(true)
        reports.report(CrashReports.Handled.XTREAM_PARSE, IllegalStateException("Unexpected JSON token"))

        synchronized(waiting) { waiting.toList() }.forEach(Runnable::run)

        assertThat(sentEvents()).hasSize(1)
    }

    @Test
    fun `a handled error while reports are off is neither sent nor held to ask about`() {
        crashReports()

        reports.report(CrashReports.Handled.XTREAM_PARSE, IllegalStateException("Unexpected JSON token"))

        assertThat(sentEvents()).isEmpty()
        assertThat(nextLaunch().askAboutHeld.value).isFalse()
    }

    @Test
    fun `a handled error while a held crash is being sent with reports off isn't sent with it`() {
        crashReports()
        crash(IllegalStateException("Settee broke"))
        val next = nextLaunch()
        next.answer(CrashReports.Answer.SEND)

        next.report(CrashReports.Handled.XTREAM_PARSE, IllegalStateException("Unexpected JSON token"))

        assertThat(sentEvents()).hasSize(1)
        assertThat(sentEvents().single()).doesNotContain("XTREAM_PARSE")
        assertThat(nextLaunch().askAboutHeld.value).isFalse()
    }

    private fun subsetsOf(fields: Set<String>): List<Set<String>> =
        fields.fold(listOf(emptySet())) { subsets, field -> subsets + subsets.map { it + field } }

    private companion object {
        const val DSN = "https://k@sentry.example.org/1"

        const val FROZEN_PID = 4321

        /** The main thread's part of an ANR's thread dump, as Android keeps it. */
        val THREAD_DUMP = """
            |----- pid 4321 at 2026-11-23 10:00:00.000000000+0000 -----
            |Cmd line: uk.neutraltone.settee
            |
            |"main" prio=5 tid=1 Sleeping
            |  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x71f0e8b8 self=0xb400007b2b2a4380
            |  | sysTid=4321 nice=-10 cgrp=top-app sched=0/0 handle=0x7c6d0f84f8
            |  | state=S schedstat=( 0 0 0 ) utm=0 stm=0 core=0 HZ=100
            |  | stack=0x7fd8a6e000-0x7fd8a70000 stackSize=8188KB
            |  | held mutexes=
            |  at java.lang.Thread.sleep(Native method)
            |  - sleeping on <0x0a1b2c3d> (a java.lang.Object)
            |  at app.opentv.ui.HomeScreen.loadRows(HomeScreen.kt:42)
            |  at android.os.Handler.dispatchMessage(Handler.java:106)
            |  at android.os.Looper.loop(Looper.java:288)
            |
            |----- end 4321 -----
            |
        """.trimMargin()
    }
}
