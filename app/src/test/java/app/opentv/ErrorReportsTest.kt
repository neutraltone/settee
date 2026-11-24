/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.opentv.core.CrashReports
import app.opentv.core.DailyCap
import app.opentv.core.ReportedSource
import app.opentv.core.ViewerError
import app.opentv.data.model.SourceKind
import app.opentv.player.PlayerController
import app.opentv.ui.MainShell
import app.opentv.ui.ProviderFailure
import app.opentv.ui.Tab
import app.opentv.ui.VodViewModel
import app.opentv.ui.channels.LiveScreen
import app.opentv.ui.design.ERROR_DETAIL_TAG
import app.opentv.ui.visibleTabs
import app.opentv.ui.vod.MoviesScreen
import app.opentv.ui.vod.PlayerErrorPanel
import app.opentv.ui.vod.ProvidersCarry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Error Reports (#633, ADR 0010) on an error that could be Settee's: sent as it's shown, once, while
 * crash reports are on; with them off, from the error's Report, through a Side Panel asking whether to
 * send it. Caught by a fake outbox in place of Sentry, as CrashReportsTest catches crashes.
 * Placeholders only: `u`/`p`, `example.com`, `00:1A:79:00:00:01`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ErrorReportsTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    private val panel = FakePanel()

    private val sent = mutableListOf<String>()

    @get:Rule
    val testGraph = TestGraph(
        compose,
        // Settings of its own: crash reports start off, and nothing this turns on reaches other tests.
        name = "error-reports-test",
        http = panel.http,
        crashReportsTo = CrashReports.Destination(
            "https://k@sentry.example.org/1",
            CrashReports.Outbox { envelope -> synchronized(sent) { sent += envelope } },
            cap = DailyCap(now = { MONDAY_NOON }),
        ),
    )

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val viewModel by lazy { testGraph.viewModel { VodViewModel(app, graph) } }

    private val report get() = app.getString(R.string.report_button)
    private val sentToSettee get() = app.getString(R.string.report_sent_to_settee)
    private val question get() = app.getString(R.string.report_question_title)
    private val tryAgain get() = app.getString(R.string.common_try_again)

    @Before
    fun launch() = runBlocking<Unit> {
        graph.crashReports.start(app)
        graph.database.sources().insert(PANEL_SOURCE)
    }

    @After
    fun close() {
        graph.crashReports.setOn(false)
        graph.crashReports.close()
        graph.settings.forgetDatabaseRows()
    }

    private fun sentEnvelopes() = synchronized(sent) { sent.toList() }

    /** Lets the report go: one the viewer sends goes off the main thread, as the after-crash Send does. */
    private fun awaitSent(count: Int) {
        compose.waitForIdle()
        assertThat(sentEnvelopes()).hasSize(count)
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun reportButton() = compose.onNode(hasText(report) and hasClickAction())

    private fun awaitText(text: String) = compose.onAllNodes(hasText(text, substring = true)).onFirst().assertExists()

    private fun assertNone(text: String) {
        compose.waitForIdle()
        val found = compose.onAllNodesWithText(text).fetchSemanticsNodes()
        check(found.isEmpty()) { "Didn't expect \"$text\" on screen" }
    }

    private fun assertDetail(detail: String) = compose.onNodeWithTag(ERROR_DETAIL_TAG).assertTextEquals(detail)

    /** Report, then Send in the panel it opens: the Highlight starts there. */
    private fun sendFromPanel() {
        reportButton().performClick()
        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun setMovies() = compose.setTvContent {
        MainShell(
            tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
            current = Tab.MOVIES,
            onSelect = {},
            onOpenSearch = {},
            onOpenSettings = {},
            onOpenProfiles = {},
            activeProfileName = "Sam",
            onExit = {},
            modifier = Modifier.fillMaxSize(),
        ) { _, _, _ ->
            MoviesScreen(
                onOpenMovie = {},
                onResume = {},
                providers = ProvidersCarry.MOVIES_AND_SHOWS,
                isSyncing = false,
                onEditProvider = {},
                viewModel = viewModel,
            )
        }
    }

    /** Coming into Movies from the rail, as the viewer does: Try again takes the Highlight. */
    private fun enterMovies() {
        compose.onNode(hasContentDescription("Movies") or hasText("Movies")).requestFocus()
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(RAIL_HANDOVER_MILLIS)
        compose.waitForIdle()
    }

    private fun setLive(failure: ProviderFailure) = compose.setTvContent {
        LiveScreen(
            isTelevision = true,
            hasSources = true,
            isSyncing = false,
            loadFailure = failure,
            onPlayChannel = {},
            onAddSource = {},
            onRefresh = {},
        )
    }

    // Movies' Report is drawn here rather than in VodLoadFailedTest, which runs as a build without a
    // DSN does, to check it's never offered there.
    @Test
    fun `with crash reports off, Report ends Movies' buttons, and its panel's Send sends one, leaving them off`() {
        panel.replies["get_vod_streams"] = "!503"
        graph.crashReports.screenOpened("main")
        setMovies()
        awaitText(report)
        enterMovies()
        compose.onNode(hasText(tryAgain)).assertIsFocused()
        assertDetail("HTTP 503")
        captureScreen("movies_load_failed_report")

        press(Key.DirectionRight)
        press(Key.DirectionRight)
        reportButton().assertIsFocused()
        press(Key.DirectionCenter)
        compose.onNodeWithText(question).assertIsInSidePanel()
        compose.onNodeWithText(app.getString(R.string.report_question_send)).assertIsFocused()
        captureScreen("movies_load_failed_report_panel")
        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitSent(1)

        assertThat(graph.settings.crashReportsOn.value).isFalse()
        assertNone(question)
        assertNone(report)
        compose.onNode(hasText(tryAgain)).assertIsFocused()
        assertDetail("HTTP 503 · $sentToSettee")
        captureScreen("movies_load_failed_report_sent")

        val sentReport = sentEnvelopes().single()
        assertThat(sentReport).contains("ERROR_REPORT")
        assertThat(sentReport).contains(""""error-detail":"HTTP 503"""")
        assertThat(sentReport).contains(""""sent-by":"viewer"""")
        assertThat(sentReport).contains(""""source-kind":"xtream"""")
        assertThat(sentReport).contains(""""screen":"main"""")
        assertThat(sentReport).doesNotContain("example.com")
        assertThat(sentReport).doesNotContain("/u/p")
        assertThat(sentReport).doesNotContain("Drive")
    }

    @Test
    fun `with crash reports on, Movies that failed is reported without a press, once, and says Sent to Settee`() {
        graph.crashReports.setOn(true)
        panel.replies["get_vod_streams"] = "!503"
        graph.crashReports.screenOpened("main")
        setMovies()
        awaitText(tryAgain)

        awaitSent(1)
        assertNone(report)
        assertDetail("HTTP 503 · $sentToSettee")
        captureScreen("movies_load_failed_sent_by_itself")
        val sentReport = sentEnvelopes().single()
        assertThat(sentReport).contains(""""sent-by":"settee"""")
        assertThat(sentReport).contains(""""error-detail":"HTTP 503"""")
        assertThat(sentReport).doesNotContain("example.com")
    }

    @Test
    fun `with crash reports on, a failure drawn again or come back to isn't sent again`() {
        graph.crashReports.setOn(true)
        var shown by mutableStateOf(true)
        var failure by mutableStateOf(
            ProviderFailure(1, "Acme TV", ViewerError("Couldn't load channels.", "HTTP 500"), SourceKind.M3U),
        )
        compose.setTvContent {
            if (shown) {
                LiveScreen(
                    isTelevision = true,
                    hasSources = true,
                    isSyncing = false,
                    loadFailure = failure,
                    onPlayChannel = {},
                    onAddSource = {},
                    onRefresh = {},
                )
            }
        }
        awaitSent(1)

        // The same failure saved again, as a refresh failing the same way does, then the screen left and come back to.
        failure = failure.copy(error = ViewerError("Couldn't load channels.", "HTTP 500"))
        compose.waitForIdle()
        shown = false
        compose.waitForIdle()
        shown = true
        awaitText(tryAgain)

        awaitSent(1)
        assertDetail("HTTP 500 · $sentToSettee")
    }

    @Test
    fun `Always send turns crash reports on and sends the film's report, which never names it`() {
        val error = PlayerController.State.Error(
            "Heat",
            ViewerError("The stream stopped.", "IoBadHttpStatus · HTTP 403 · H.264 1920×1080"),
            canRetry = true,
        )
        // Over the player once it has failed, as it comes, rather than the screen's first frame.
        var failed by mutableStateOf(false)
        compose.setTvContent {
            if (failed) PlayerErrorPanel(error, onRetry = {}, onBack = {}, reportedSource = ReportedSource.STREMIO)
        }
        compose.waitForIdle()
        failed = true
        compose.waitForIdle()

        compose.onNode(hasText(tryAgain)).assertIsFocused()
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        reportButton().assertIsFocused()
        press(Key.DirectionCenter)
        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText(app.getString(R.string.report_question_always_send)).assertIsFocused()
        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitSent(1)

        assertThat(graph.settings.crashReportsOn.value).isTrue()
        compose.onNode(hasText(tryAgain)).assertIsFocused()
        assertNone(report)
        val sentReport = sentEnvelopes().single()
        assertThat(sentReport).contains(""""error-kind":"IoBadHttpStatus"""")
        assertThat(sentReport).contains(""""source-kind":"stremio"""")
        assertThat(sentReport).contains(""""sent-by":"viewer"""")
        assertThat(sentReport).doesNotContain("Heat")
        assertThat(sentReport).doesNotContain("The stream stopped")
    }

    @Test
    fun `Back or Don't send closes the panel with nothing sent, and Report stays`() {
        setLive(ProviderFailure(1, "Acme TV", ViewerError("Couldn't load channels.", "HTTP 500"), SourceKind.M3U))

        reportButton().performClick()
        compose.pressBackOnPanel()
        assertNone(question)
        reportButton().performClick()
        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText(app.getString(R.string.report_question_dont_send)).assertIsFocused()
        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_CENTER)

        assertNone(question)
        awaitSent(0)
        reportButton().assertExists()
        assertDetail("HTTP 500")
        assertThat(graph.settings.crashReportsOn.value).isFalse()
    }

    @Test
    fun `an M3U playlist's failure arrives without its address, login or name`() {
        setLive(
            ProviderFailure(
                1,
                "Acme TV",
                ViewerError("Couldn't load channels.", "HTTP 500 · http://example.com/get.php?username=u&password=p"),
                SourceKind.M3U,
            ),
        )

        sendFromPanel()
        awaitSent(1)

        val sentReport = sentEnvelopes().single()
        assertThat(sentReport).contains(""""source-kind":"m3u"""")
        assertThat(sentReport).contains("HTTP 500")
        assertThat(sentReport).doesNotContain("example.com")
        assertThat(sentReport).doesNotContain("username=u")
        assertThat(sentReport).doesNotContain("password=p")
        assertThat(sentReport).doesNotContain("Acme TV")
    }

    @Test
    fun `a Stalker portal's failure arrives without its MAC or name, sent by Settee as by the viewer`() {
        graph.crashReports.setOn(true)
        setLive(
            ProviderFailure(
                1,
                "Acme TV",
                ViewerError("Couldn't load channels.", "BadReply · mac=00:1A:79:00:00:01 · 00:1A:79:00:00:01"),
                SourceKind.STALKER,
            ),
        )
        awaitSent(1)

        val sentReport = sentEnvelopes().single()
        assertThat(sentReport).contains(""""source-kind":"stalker"""")
        assertThat(sentReport).contains(""""error-kind":"BadReply"""")
        assertThat(sentReport).doesNotContain("00:1A:79")
        assertThat(sentReport).doesNotContain("Acme TV")
    }

    @Test
    fun `a provider that can't be reached or turned down the login is never reported, on or off`() {
        var failure by mutableStateOf(
            ProviderFailure(1, "Acme TV", ViewerError("Can't reach your provider.", "UnknownHost"), SourceKind.XTREAM),
        )
        compose.setTvContent {
            LiveScreen(
                isTelevision = true,
                hasSources = true,
                isSyncing = false,
                loadFailure = failure,
                onPlayChannel = {},
                onAddSource = {},
                onRefresh = {},
            )
        }
        awaitText(tryAgain)
        assertNone(report)

        failure = ProviderFailure(1, "Acme TV", ViewerError("Wrong login.", "LoginRejected"), SourceKind.XTREAM)
        awaitText("Wrong login.")
        assertNone(report)

        graph.crashReports.setOn(true)
        awaitSent(0)
        assertDetail("LoginRejected")
    }

    @Test
    fun `over the day's cap, the error still says Sent to Settee, though nothing more goes`() {
        // Twenty reports that weren't crashes already sent today.
        graph.settings.setCrashReportsCappedOn(MONDAY, DailyCap.MOST_A_DAY)
        setLive(ProviderFailure(1, "Acme TV", ViewerError("Couldn't load channels.", "HTTP 500"), SourceKind.M3U))

        sendFromPanel()

        assertDetail("HTTP 500 · $sentToSettee")
        assertNone(report)
        awaitSent(0)
    }

    private companion object {
        const val RAIL_HANDOVER_MILLIS = 500L

        /** Monday 23 November 2026, 12:00 UTC, and that day's UTC day number. */
        const val MONDAY_NOON = 1_795_435_200_000L
        const val MONDAY = 20_780L
    }
}
