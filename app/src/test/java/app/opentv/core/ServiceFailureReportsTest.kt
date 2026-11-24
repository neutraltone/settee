/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.FakeOpenSubtitles
import app.opentv.data.remote.OpenSubtitlesClient
import app.opentv.data.remote.SubtitleSearch
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settee's own services turning down Settee's request (#632, ADR 0010): each client reports through
 * the hook the app wires in ServiceLocator, and a fake outbox catches what would leave. Only the
 * service, the operation and the status arrive; the viewer's own trouble (no internet, a wrong
 * password) and a service's (5xx, 429) never do. Placeholders only: `u`/`p`, `example.com`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServiceFailureReportsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = AppSettings.separate(app, "service-failure-reports-test")
    private val sent = mutableListOf<String>()
    private val reports = CrashReports(
        settings,
        CrashReports.Destination(
            "https://k@sentry.example.org/1",
            CrashReports.Outbox { envelope -> synchronized(sent) { sent += envelope } },
        ),
        release = "1.4.0",
        environment = "test",
    ).also { it.start(app) }

    private fun sentEvents() = synchronized(sent) { sent.toList() }

    private val openSubtitles = FakeOpenSubtitles()

    /** OpenSubtitles as the app builds it, its failures reported through the same hook. */
    private val subtitles = OpenSubtitlesClient(
        openSubtitles.http,
        "k",
        "0.5.0",
        reportFailure = { reports.report(CrashReports.Handled.OPENSUBTITLES, it) },
    )

    private fun signIn(reply: String) {
        openSubtitles.replies["POST /login"] = reply
        assertThat(subtitles.signIn("u", "secretword").isFailure).isTrue()
    }

    @After
    fun tearDown() {
        reports.setOn(false)
        reports.close()
    }

    @Test
    fun `a 403 at OpenSubtitles sign-in arrives naming the service, the call and the status`() {
        reports.setOn(true)

        signIn("""!403 {"message": "You cannot consume this service"}""")

        val event = sentEvents().single()
        assertThat(event).contains("OPENSUBTITLES sign-in http-403")
        assertThat(event).contains(""""service":"opensubtitles"""")
        assertThat(event).contains(""""operation":"sign-in"""")
        assertThat(event).contains(""""status":"http-403"""")
    }

    @Test
    fun `nothing from the request or the reply leaves with it`() {
        reports.setOn(true)

        signIn("""!403 {"message": "You cannot consume this service"}""")

        val event = sentEvents().single()
        assertThat(event).doesNotContain("secretword")
        assertThat(event).doesNotContain("cannot consume")
        assertThat(event).doesNotContain("api.opensubtitles.com")
        assertThat(event).doesNotContain("Settee v0.5.0")
    }

    @Test
    fun `a wrong password, no answer, trouble at OpenSubtitles and its rate limit never arrive`() {
        reports.setOn(true)

        for (reply in listOf("!401 {}", "!", "!500 {}", "!503 {}", "!429 {}")) signIn(reply)

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `a search OpenSubtitles answers with a page Settee can't read arrives as unreadable`() {
        reports.setOn(true)
        openSubtitles.replies["GET /subtitles"] = "<html><body>Just a moment...</body></html>"

        assertThat(subtitles.search(SubtitleSearch.Film("A film", 2001, null)).isFailure)
            .isTrue()

        val event = sentEvents().single()
        assertThat(event).contains("OPENSUBTITLES search unreadable-reply")
        assertThat(event).doesNotContain("A film")
        assertThat(event).doesNotContain("Just a moment")
    }

    @Test
    fun `the daily download limit is OpenSubtitles' quota, and never arrives`() {
        reports.setOn(true)
        openSubtitles.replies["POST /download"] = """!406 {"message": "limit"}"""

        assertThat(subtitles.download(1, token = null).isFailure).isTrue()

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `with crash reports off, a 403 at sign-in is dropped`() {
        signIn("!403 {}")

        assertThat(sentEvents()).isEmpty()
    }
}
