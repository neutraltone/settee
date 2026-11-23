/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What [CrashReports] would send to Sentry, caught by a fake outbox in place of Sentry's own transport:
 * the envelopes as they'd leave the device, so a scrubber that isn't wired in fails here too.
 * Placeholders only: `u`/`p`, `example.com`, `00:1A:79:00:00:01`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrashReportsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = AppSettings.separate(app, "crash-reports-test")
    private val sent = mutableListOf<String>()
    private val outbox = CrashReports.Outbox { envelope -> synchronized(sent) { sent += envelope } }
    private var before: Thread.UncaughtExceptionHandler? = null
    private lateinit var reports: CrashReports

    private fun crashReports(dsn: String = DSN) =
        CrashReports(settings, dsn, release = "1.4.0", environment = "test", outbox = outbox).also {
            reports = it
            it.start(app)
        }

    /** The app crashing: whatever handler is installed hears of it, as the JVM would tell it. */
    private fun crash(error: Throwable = IllegalStateException("Settee broke")) {
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        if (handler !== before) handler?.uncaughtException(Thread.currentThread(), error)
    }

    private fun sentEvents() = synchronized(sent) { sent.toList() }

    @Before
    fun setUp() {
        before = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        if (::reports.isInitialized) reports.setOn(false)
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

    private fun subsetsOf(fields: Set<String>): List<Set<String>> =
        fields.fold(listOf(emptySet())) { subsets, field -> subsets + subsets.map { it + field } }

    private companion object {
        const val DSN = "https://k@sentry.example.org/1"
    }
}
