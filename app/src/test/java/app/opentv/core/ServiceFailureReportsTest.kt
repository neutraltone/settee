/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.FakeJsonHttp
import app.opentv.FakeOpenSubtitles
import app.opentv.data.remote.IntroDbClient
import app.opentv.data.remote.OpenSubtitlesClient
import app.opentv.data.remote.SubtitleSearch
import app.opentv.data.remote.TheIntroDbClient
import app.opentv.data.remote.TmdbClient
import app.opentv.update.UpdateChecker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
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
        reportFailure = { reports.report(it) },
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

    @Test
    fun `a 401 to a search, which sends only Settee's key, arrives as the key refused`() {
        reports.setOn(true)
        openSubtitles.replies["GET /subtitles"] = "!401 {}"

        assertThat(subtitles.search(SubtitleSearch.Film("A film", 2001, null)).isFailure).isTrue()

        assertThat(sentEvents().single()).contains("OPENSUBTITLES search http-401")
    }

    @Test
    fun `a 401 to a request with the viewer's token is their session ending, and never arrives`() {
        reports.setOn(true)
        openSubtitles.replies["POST /download"] = "!401 {}"
        openSubtitles.replies["GET /infos/user"] = "!401 {}"

        assertThat(subtitles.download(1, token = "t").isFailure).isTrue()
        assertThat(subtitles.account("t").isFailure).isTrue()

        assertThat(sentEvents()).isEmpty()
    }

    /** TMDB as the app builds it, answering every request with [reply] (see [FakeJsonHttp]). */
    private fun tmdb(reply: String) = TmdbClient(
        FakeJsonHttp { reply }.http,
        "0123456789abcdef0123456789abcdef",
        reportFailure = { reports.report(it) },
    )

    @Test
    fun `a 403 from TMDB's search arrives naming the service, the call and the status, and not the title`() {
        reports.setOn(true)

        assertThat(tmdb("!403").movieMetaOrFailure("A film", 2001, tmdbId = null).isFailure).isTrue()

        val event = sentEvents().single()
        assertThat(event).contains("TMDB search http-403")
        assertThat(event).contains(""""service":"tmdb"""")
        assertThat(event).doesNotContain("A film")
        assertThat(event).doesNotContain("0123456789abcdef")
        assertThat(event).doesNotContain("api.themoviedb.org")
    }

    @Test
    fun `TMDB refusing Settee's key with a 401 arrives`() {
        reports.setOn(true)

        assertThat(tmdb("!401").movieMetaOrFailure("A film", null, tmdbId = "603").isFailure).isTrue()

        assertThat(sentEvents().single()).contains("TMDB details http-401")
    }

    @Test
    fun `a 404 to a TMDB search, which answers no match with an empty list, arrives`() {
        reports.setOn(true)

        assertThat(tmdb("404").movieMetaOrFailure("A film", null, tmdbId = null).isSuccess).isTrue()

        assertThat(sentEvents().first()).contains("TMDB search http-404")
    }

    @Test
    fun `details TMDB answers with a page Settee can't read arrive as unreadable`() {
        reports.setOn(true)

        assertThat(tmdb("<html>Blocked</html>").movieMetaOrFailure("A film", null, tmdbId = "603").isFailure).isTrue()

        assertThat(sentEvents().single()).contains("TMDB details unreadable-reply")
    }

    @Test
    fun `TMDB's 404, its way of saying there's no such title, never arrives`() {
        reports.setOn(true)

        assertThat(tmdb("404").movieMetaOrFailure("A film", null, tmdbId = "603").getOrThrow()).isNull()

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `TMDB not answering, its trouble and its rate limit never arrive`() {
        reports.setOn(true)

        for (reply in listOf("!", "!500", "!503", "!429")) {
            assertThat(tmdb(reply).movieMetaOrFailure("A film", null, tmdbId = "603").isFailure).isTrue()
        }

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `with crash reports off, a 403 from TMDB is dropped`() {
        assertThat(tmdb("!403").movieMetaOrFailure("A film", null, tmdbId = "603").isFailure).isTrue()

        assertThat(sentEvents()).isEmpty()
    }

    private fun introDb(reply: String) = IntroDbClient(FakeJsonHttp { reply }.http) { reports.report(it) }

    private fun theIntroDb(reply: String) = TheIntroDbClient(FakeJsonHttp { reply }.http) { reports.report(it) }

    @Test
    fun `a 403 from IntroDB or TheIntroDB arrives as a timings failure, without the show`() {
        reports.setOn(true)

        assertThat(introDb("!403").timings("tt0903747", 1, 2).isFailure).isTrue()
        assertThat(theIntroDb("!410").timings("1396", 1, 2).isFailure).isTrue()

        val events = sentEvents()
        assertThat(events).hasSize(2)
        assertThat(events[0]).contains("INTRODB timings http-403")
        assertThat(events[1]).contains("INTRODB timings http-410")
        events.forEach { event ->
            assertThat(event).contains(""""service":"introdb"""")
            assertThat(event).doesNotContain("tt0903747")
            assertThat(event).doesNotContain("introdb.app")
        }
    }

    @Test
    fun `a 401 from IntroDB, which Settee asks without any login, arrives`() {
        reports.setOn(true)

        assertThat(introDb("!401").timings("tt0903747", 1, 2).isFailure).isTrue()

        assertThat(sentEvents().single()).contains("INTRODB timings http-401")
    }

    @Test
    fun `a page in front of TheIntroDB arrives as unreadable`() {
        reports.setOn(true)

        assertThat(theIntroDb("<html>Just a moment</html>").timings("1396", 1, 2).isFailure).isTrue()

        assertThat(sentEvents().single()).contains("INTRODB timings unreadable-reply")
    }

    @Test
    fun `an episode IntroDB hasn't timed, no answer, its trouble and its rate limit never arrive`() {
        reports.setOn(true)

        for (reply in listOf("404", "!", "!500", "!429")) {
            introDb(reply).timings("tt0903747", 1, 2)
            theIntroDb(reply).timings("1396", 1, 2)
        }

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `with crash reports off, a 403 from IntroDB is dropped`() {
        assertThat(introDb("!403").timings("tt0903747", 1, 2).isFailure).isTrue()

        assertThat(sentEvents()).isEmpty()
    }

    private fun checkForUpdate(reply: String) = runBlocking {
        UpdateChecker(FakeJsonHttp { reply }.http, "0.1.0") { reports.report(it) }.check()
    }

    @Test
    fun `a 404 from GitHub's latest release arrives as an update check failure`() {
        reports.setOn(true)

        assertThat(checkForUpdate("!404")).isInstanceOf(UpdateChecker.Outcome.CouldNotCheck::class.java)

        val event = sentEvents().single()
        assertThat(event).contains("UPDATE_CHECK latest-release http-404")
        assertThat(event).contains(""""service":"update-check"""")
        assertThat(event).doesNotContain("api.github.com")
    }

    @Test
    fun `GitHub turning down the update check with a 401 arrives`() {
        reports.setOn(true)

        assertThat(checkForUpdate("!401")).isInstanceOf(UpdateChecker.Outcome.CouldNotCheck::class.java)

        assertThat(sentEvents().single()).contains("UPDATE_CHECK latest-release http-401")
    }

    @Test
    fun `a latest release Settee can't read arrives as unreadable`() {
        reports.setOn(true)

        assertThat(checkForUpdate("<html>Unicorn</html>")).isInstanceOf(UpdateChecker.Outcome.CouldNotCheck::class.java)

        assertThat(sentEvents().single()).contains("UPDATE_CHECK latest-release unreadable-reply")
    }

    @Test
    fun `GitHub not answering, its trouble and its rate limit never arrive`() {
        reports.setOn(true)

        for (reply in listOf("!", "!500", "!502", "!429")) checkForUpdate(reply)

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `with crash reports off, a 404 from GitHub is dropped`() {
        checkForUpdate("!404")

        assertThat(sentEvents()).isEmpty()
    }
}
