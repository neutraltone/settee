/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Movie
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.SubtitleTitle
import app.opentv.ui.player.SubtitlePicks
import app.opentv.ui.vod.DOWNLOADED_SUBTITLE_TAG
import app.opentv.ui.vod.LocalVodSubtitles
import app.opentv.ui.vod.OPENSUBTITLES_ENTRY_TAG
import app.opentv.ui.vod.OPENSUBTITLES_RESULTS_TAG
import app.opentv.ui.vod.TIMING_TAG
import app.opentv.ui.vod.VodPlayerContent
import app.opentv.ui.vod.VodPlayerViewModel
import app.opentv.ui.vod.VodSubtitles
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** OpenSubtitles in the film player's subtitle list, against [FakeOpenSubtitles]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class VodOpenSubtitlesTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private val players = TestPlayers(app)
    private val controller = players.controller()
    private val site = FakeOpenSubtitles().apply {
        replies["GET /subtitles"] = """
            {"data": [
              {"id": "1", "attributes": {"language": "fr", "release": "Heat.1995.FR", "download_count": 9000,
                "files": [{"file_id": 22}]}},
              {"id": "2", "attributes": {"language": "en", "release": "Heat.1995.1080p", "download_count": 50,
                "hearing_impaired": true, "files": [{"file_id": 11}]}}
            ]}
        """.trimIndent()
        replies["POST /download"] = """{"link": "https://dl.opensubtitles.example/f.srt", "remaining": 4}"""
        replies["GET /f.srt"] = "1\n00:00:00,000 --> 01:00:00,000\nHello\n"
    }
    private val graph = ServiceLocator.Graph(app, http = site.http).apply { openSubtitlesKey = "k" }
    private val viewModel = VodPlayerViewModel(app, graph)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val subtitles = VodSubtitles(graph.openSubtitles!!, graph.profileSettings, scope, device = { "en" })
    private val noPicks = SubtitlePicks(onOff = {}, onPicked = {})
    private val heat = SubtitleTitle.film(source = "Provider", streamId = "1")

    @Before
    fun seed() = runBlocking {
        graph.database.sources().insert(
            Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"),
        )
        graph.database.movies().upsertAll(
            listOf(
                Movie(
                    id = 1, sourceId = 1, streamId = "1", name = "Heat", categoryId = null, posterUrl = null,
                    rating = null, year = 1995, plot = null, durationSeconds = null, containerExtension = "mkv",
                    streamUrl = "https://example.com/1",
                ),
            ),
        )
        viewModel.attach(MediaKey.Movie(1), FakePlayback())
    }

    @After
    fun release() {
        scope.cancel()
        controller.release()
        players.close()
        graph.database.close()
    }

    private fun showFilm(withOpenSubtitles: VodSubtitles? = subtitles) {
        compose.setTvContent {
            CompositionLocalProvider(LocalVodSubtitles provides withOpenSubtitles) {
                VodPlayerContent(controller, viewModel, false, noPicks, onBack = {})
            }
        }
        players.play(controller)
        controller.player.seekTo(TEN_MINUTES)
        players.untilReady(controller.player)
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    /** From the bar: Down to Subtitles, and open it. */
    private fun openSubtitleList() {
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
    }

    private fun until(condition: () -> Boolean) {
        compose.waitUntil(WAIT_MILLIS) {
            compose.mainClock.advanceTimeBy(FRAME_MILLIS)
            condition()
        }
        settle()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun searchAndPickEnglish() {
        openSubtitleList()
        compose.onNodeWithTag(OPENSUBTITLES_ENTRY_TAG).performClick()
        until { compose.onAllNodes(hasText("English")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("English").performClick()
        until { exists(DOWNLOADED_SUBTITLE_TAG) }
    }

    @Test
    fun `the film player's subtitle list starts with OpenSubtitles`() {
        showFilm()
        until { subtitles.subject.value != null }

        openSubtitleList()

        val entry = compose.onNodeWithTag(OPENSUBTITLES_ENTRY_TAG).fetchSemanticsNode().boundsInRoot
        val off = compose.onNodeWithText("Off").fetchSemanticsNode().boundsInRoot
        assertThat(entry.top).isLessThan(off.top)
    }

    @Test
    fun `without OpenSubtitles the list is only the stream's`() {
        showFilm(withOpenSubtitles = null)

        openSubtitleList()

        compose.onNodeWithText("Off").assertIsDisplayed()
        assertThat(exists(OPENSUBTITLES_ENTRY_TAG)).isFalse()
    }

    @Test
    fun `OpenSubtitles shows what it found with the Highlight on the first, and Back returns to the list`() {
        showFilm()
        until { subtitles.subject.value != null }
        openSubtitleList()

        compose.onNodeWithTag(OPENSUBTITLES_ENTRY_TAG).performClick()
        until { compose.onAllNodes(hasText("English")).fetchSemanticsNodes().isNotEmpty() }

        // The device's language comes before the more downloaded French.
        compose.onNodeWithText("English").assertIsFocused()
        compose.onNodeWithText("Heat.1995.1080p · Hearing impaired").assertIsDisplayed()
        compose.onNodeWithText("Subtitles provided by OpenSubtitles.com").assertIsDisplayed()
        captureScreen("vod_player_opensubtitles_results")

        Espresso.pressBack()
        settle()
        assertThat(exists(OPENSUBTITLES_RESULTS_TAG)).isFalse()
        compose.onNodeWithTag(OPENSUBTITLES_ENTRY_TAG).assertIsDisplayed()
    }

    @Test
    fun `a picked result shows over the film where it was, and is offered the next time with no new download`() {
        showFilm()
        until { subtitles.subject.value != null }

        searchAndPickEnglish()

        assertThat(controller.player.currentPosition).isAtLeast(TEN_MINUTES)
        assertThat(runBlocking { graph.openSubtitles!!.store.forTitle(heat) }.map { it.language }).containsExactly("en")
        val downloads = site.requests.count { it.url.encodedPath.endsWith("/download") }
        assertThat(downloads).isEqualTo(1)

        // The next time the film plays.
        val again = VodSubtitles(graph.openSubtitles!!, graph.profileSettings, scope, device = { "en" })
        again.load(MediaKey.Movie(1))
        until { again.downloaded.value != null }
        assertThat(again.downloaded.value!!.map { it.language }).containsExactly("en")
        assertThat(site.requests.count { it.url.encodedPath.endsWith("/download") }).isEqualTo(downloads)
    }

    @Test
    fun `Timing moves a Downloaded Subtitle in tenths of a second, and keeps it for the next time`() {
        showFilm()
        until { subtitles.subject.value != null }
        searchAndPickEnglish()

        openSubtitleList()
        compose.onNodeWithTag(TIMING_TAG).requestFocus()
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        press(Key.DirectionLeft)

        compose.onNode(hasText("+0.2 s", substring = true)).assertIsDisplayed()
        // Still showing while it's adjusted.
        assertThat(exists(DOWNLOADED_SUBTITLE_TAG)).isTrue()
        captureScreen("vod_player_subtitle_timing")
        until { runBlocking { graph.openSubtitles!!.store.forTitle(heat) }.single().offsetMillis == 200L }
    }

    private companion object {
        const val TEN_MINUTES = 600_000L
        const val SETTLE_MILLIS = 600L
        const val WAIT_MILLIS = 10_000L
        const val FRAME_MILLIS = 50L
    }
}
