/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.opentv.FakePanel
import app.opentv.PANEL_SOURCE
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.db.wontOpen
import app.opentv.data.remote.XtreamApi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settee's own handled errors (#576): the kinds only Settee's code can cause reach Sentry, scrubbed,
 * while Crash Reports are on, and a Provider's failures never do. Caught by a fake outbox, as
 * [CrashReportsTest] catches crashes. Placeholders only: `u`/`p`, `example.com`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HandledErrorReportsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val settings = AppSettings.separate(app, "handled-error-reports-test")
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

    /** An Xtream client whose parse failures are reported, as the app's is. */
    private fun xtream(panel: FakePanel) =
        XtreamApi(panel.http, reportParseFailure = { reports.report(CrashReports.Handled.XTREAM_PARSE, it) })

    /** Signs in to [panel], which fails: every reply here is one Settee refuses. */
    private fun signIn(panel: FakePanel) {
        assertThrows(Exception::class.java) { runBlocking { xtream(panel).authenticate(PANEL_SOURCE) } }
    }

    /** A database left by a newer Settee, after a downgrade, which Room refuses to open. */
    private fun openNewerDatabase(): RuntimeException? {
        val path = app.getDatabasePath(OpenTvDatabase.FILE_NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use {
            it.execSQL("CREATE TABLE sources (id INTEGER PRIMARY KEY, name TEXT)")
            it.execSQL("INSERT INTO sources VALUES (1, 'Provider name')")
            it.version = NEWER
        }
        val database = OpenTvDatabase.build(app)
        return try {
            database.wontOpen { reports.report(CrashReports.Handled.DATABASE_MIGRATION, it) }
        } finally {
            database.close()
        }
    }

    @After
    fun tearDown() {
        reports.setOn(false)
        // Takes out the crash handler and freeze watch start() put in, as the process ending would.
        reports.close()
        app.deleteDatabase(OpenTvDatabase.FILE_NAME)
    }

    @Test
    fun `an Xtream reply Settee can't parse is reported while crash reports are on`() {
        reports.setOn(true)
        // Whole, but not JSON Settee can read: a comma missing.
        val panel = FakePanel("" to """{"user_info": {"auth": 1 "password": "p"}}""")

        signIn(panel)

        val event = sentEvents().single()
        assertThat(event).contains("XTREAM_PARSE")
        assertThat(event).contains("XtreamApi")
    }

    @Test
    fun `the reply itself never leaves the device, since a panel's reply can hold the login`() {
        reports.setOn(true)
        val panel = FakePanel("" to """{"user_info": {"username": "u", "password": "secretword" "auth": 1}}""")

        signIn(panel)

        val event = sentEvents().single()
        assertThat(event).doesNotContain("secretword")
        assertThat(event).doesNotContain("user_info")
        assertThat(event).doesNotContain("example.com")
    }

    @Test
    fun `a movie list Settee can't read is reported`() {
        reports.setOn(true)
        val panel = FakePanel(
            "get_vod_streams" to """[{"stream_id": 1, "name": "A film"} {"stream_id": 2, "name": "B"}]""",
        )

        assertThrows(XtreamApi.XtreamException::class.java) {
            runBlocking { xtream(panel).movies(PANEL_SOURCE).use { list -> list.forEachBatch { } } }
        }

        val event = sentEvents().single()
        assertThat(event).contains("XTREAM_PARSE")
        assertThat(event).doesNotContain("A film")
    }

    @Test
    fun `a movie list that breaks off partway is the provider's, and isn't reported`() {
        reports.setOn(true)
        val panel = FakePanel("get_vod_streams" to """[{"stream_id": 1, "name": "A film"}, {"stream_id": 2, "name": """)

        assertThrows(XtreamApi.XtreamException::class.java) {
            runBlocking { xtream(panel).movies(PANEL_SOURCE).use { list -> list.forEachBatch { } } }
        }

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `a sign-in reply that breaks off partway is the provider's, and isn't reported`() {
        reports.setOn(true)

        signIn(FakePanel("" to """{"user_info": {"auth": 1, "password": "p"""))
        signIn(FakePanel("" to """{"user_info": {"auth": 1, "message": "a } in a string"""))

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `with crash reports off, a parse failure is dropped`() {
        signIn(FakePanel("" to """{"user_info": {"auth": 1 "password": "p"}}"""))

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `a database Room won't open is reported, with Room's reason but nothing from its rows`() {
        reports.setOn(true)

        assertThat(openNewerDatabase()).isNotNull()

        val event = sentEvents().single()
        assertThat(event).contains("DATABASE_MIGRATION")
        assertThat(event).contains("A migration from $NEWER")
        assertThat(event).doesNotContain("Provider name")
    }

    @Test
    fun `with crash reports off, a database Room won't open is dropped`() {
        assertThat(openNewerDatabase()).isNotNull()

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `a provider's HTTP errors and web pages never leave the device`() {
        reports.setOn(true)

        for (reply in listOf("!403", "!404", "!500", "!503", "<html><body>Sign in to the Wi-Fi</body></html>")) {
            signIn(FakePanel("" to reply))
        }

        assertThat(sentEvents()).isEmpty()
    }

    @Test
    fun `only Settee's own failures can be reported, so playback and provider failures have no kind`() {
        assertThat(CrashReports.Handled.entries).containsExactly(
            CrashReports.Handled.XTREAM_PARSE,
            CrashReports.Handled.DATABASE_MIGRATION,
            CrashReports.Handled.OPENSUBTITLES,
            CrashReports.Handled.TMDB,
            CrashReports.Handled.INTRODB,
            CrashReports.Handled.UPDATE_CHECK,
        )
    }

    private companion object {
        /** Past any schema Settee has: what a downgrade leaves. */
        const val NEWER = 999
    }
}
