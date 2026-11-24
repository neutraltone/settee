/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.opentv.core.CrashReports
import app.opentv.core.ReportedSource
import app.opentv.core.ViewerError
import app.opentv.data.model.SourceKind
import app.opentv.player.PlayerController
import app.opentv.ui.MainShell
import app.opentv.ui.ProviderFailure
import app.opentv.ui.Tab
import app.opentv.ui.VodViewModel
import app.opentv.ui.channels.LiveScreen
import app.opentv.ui.sendReportFor
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
 * Send report on an error that could be Settee's (#633, ADR 0010): one scrubbed Error Report, with
 * crash reports off and left off, and Sent after. Caught by a fake outbox in place of Sentry, as
 * CrashReportsTest catches crashes. Placeholders only: `u`/`p`, `example.com`, `00:1A:79:00:00:01`.
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
        ),
    )

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val viewModel by lazy { testGraph.viewModel { VodViewModel(app, graph) } }

    private val sendReport get() = app.getString(R.string.report_send)
    private val sentLabel get() = app.getString(R.string.report_sent)

    @Before
    fun launch() = runBlocking<Unit> {
        graph.crashReports.start(app)
        graph.database.sources().insert(PANEL_SOURCE)
    }

    @After
    fun close() {
        graph.crashReports.close()
        graph.settings.forgetDatabaseRows()
    }

    private fun sentEnvelopes() = synchronized(sent) { sent.toList() }

    /** Waits for the report to go: it's sent off the main thread, as the after-crash Send is. */
    private fun awaitSent(count: Int) {
        compose.waitUntil(SEND_MILLIS) { sentEnvelopes().size >= count }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun awaitText(text: String) = compose.onAllNodes(hasText(text, substring = true)).onFirst().assertExists()

    private fun assertNone(text: String) {
        compose.waitForIdle()
        val found = compose.onAllNodesWithText(text).fetchSemanticsNodes()
        check(found.isEmpty()) { "Didn't expect \"$text\" on screen" }
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

    @Test
    fun `Send report on Movies that failed sends one report with crash reports off, leaves them off, then says Sent`() {
        panel.replies["get_vod_streams"] = "!503"
        graph.crashReports.screenOpened("main")
        setMovies()
        awaitText(app.getString(R.string.common_try_again))
        awaitText(sendReport)
        captureScreen("movies_load_failed_send_report")

        compose.onNode(hasText(sendReport) and hasClickAction()).performClick()
        awaitSent(1)

        assertThat(graph.settings.crashReportsOn.value).isFalse()
        awaitText(sentLabel)
        assertNone(sendReport)
        captureScreen("movies_load_failed_report_sent")
        // Sent can't send it again.
        compose.onNode(hasText(sentLabel) and hasClickAction()).performClick()
        compose.waitForIdle()
        Thread.sleep(AFTER_MILLIS)
        assertThat(sentEnvelopes()).hasSize(1)

        val report = sentEnvelopes().single()
        assertThat(report).contains("VIEWER_REPORT")
        assertThat(report).contains(""""error-detail":"HTTP 503"""")
        assertThat(report).contains(""""source-kind":"xtream"""")
        assertThat(report).contains(""""screen":"main"""")
        assertThat(report).doesNotContain("example.com")
        assertThat(report).doesNotContain("/u/p")
        assertThat(report).doesNotContain("Drive")
    }

    @Test
    fun `Try again keeps the Highlight on coming into Movies, and Send report is Down from it`() {
        panel.replies["get_vod_streams"] = "!503"
        setMovies()
        awaitText(sendReport)
        compose.onNode(hasContentDescription("Movies") or hasText("Movies")).requestFocus()
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(RAIL_HANDOVER_MILLIS)
        compose.waitForIdle()

        compose.onNode(hasText(app.getString(R.string.common_try_again))).assertIsFocused()
        press(Key.DirectionDown)
        compose.onNode(hasText(sendReport) and hasClickAction()).assertIsFocused()
        press(Key.DirectionCenter)

        awaitSent(1)
        compose.onNode(hasText(sentLabel) and hasClickAction()).assertIsFocused()
    }

    @Test
    fun `a film that won't play keeps the Highlight on Try again, and its report never names the film`() {
        val error = PlayerController.State.Error(
            "Heat",
            ViewerError("The stream stopped.", "IoBadHttpStatus · HTTP 403 · H.264 1920×1080"),
            canRetry = true,
        )
        // Over the player once it has failed, as it comes, rather than the screen's first frame.
        var failed by mutableStateOf(false)
        compose.setTvContent {
            if (failed) {
                PlayerErrorPanel(
                    error,
                    onRetry = {},
                    onBack = {},
                    sendReport = sendReportFor(error.reason, ReportedSource.STREMIO),
                )
            }
        }
        compose.waitForIdle()
        failed = true
        compose.waitForIdle()

        compose.onNode(hasText(app.getString(R.string.common_try_again))).assertIsFocused()
        captureScreen("vod_player_retry_send_report")
        press(Key.DirectionDown)
        compose.onNode(hasText(sendReport) and hasClickAction()).assertIsFocused()
        press(Key.DirectionCenter)
        awaitSent(1)

        val report = sentEnvelopes().single()
        assertThat(report).contains(""""error-kind":"IoBadHttpStatus"""")
        assertThat(report).contains(""""source-kind":"stremio"""")
        assertThat(report).doesNotContain("Heat")
        assertThat(report).doesNotContain("The stream stopped")
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

        compose.onNode(hasText(sendReport) and hasClickAction()).performClick()
        awaitSent(1)

        val report = sentEnvelopes().single()
        assertThat(report).contains(""""source-kind":"m3u"""")
        assertThat(report).contains("HTTP 500")
        assertThat(report).doesNotContain("example.com")
        assertThat(report).doesNotContain("username=u")
        assertThat(report).doesNotContain("password=p")
        assertThat(report).doesNotContain("Acme TV")
    }

    @Test
    fun `a Stalker portal's failure arrives without its MAC or name`() {
        setLive(
            ProviderFailure(
                1,
                "Acme TV",
                ViewerError("Couldn't load channels.", "BadReply · mac=00:1A:79:00:00:01 · 00:1A:79:00:00:01"),
                SourceKind.STALKER,
            ),
        )

        compose.onNode(hasText(sendReport) and hasClickAction()).performClick()
        awaitSent(1)

        val report = sentEnvelopes().single()
        assertThat(report).contains(""""source-kind":"stalker"""")
        assertThat(report).contains(""""error-kind":"BadReply"""")
        assertThat(report).doesNotContain("00:1A:79")
        assertThat(report).doesNotContain("Acme TV")
    }

    @Test
    fun `a provider that can't be reached or turned down the login doesn't offer Send report`() {
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
        awaitText(app.getString(R.string.common_try_again))
        assertNone(sendReport)

        failure = ProviderFailure(1, "Acme TV", ViewerError("Wrong login.", "LoginRejected"), SourceKind.XTREAM)
        awaitText("Wrong login.")
        assertNone(sendReport)
    }

    private companion object {
        const val SEND_MILLIS = 10_000L
        const val AFTER_MILLIS = 300L
        const val RAIL_HANDOVER_MILLIS = 500L
    }
}
