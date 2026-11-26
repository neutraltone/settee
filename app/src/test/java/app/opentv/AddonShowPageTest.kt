/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.opentv.data.model.StremioAddon
import app.opentv.data.repo.AddonTitle
import app.opentv.data.repo.MediaKey
import app.opentv.ui.VodViewModel
import app.opentv.ui.vod.SeriesDetailScreen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The page of a show kept from an add-on (#672), with its real view model: its seasons and episodes come
 * from TMDB (faked), its episodes play through the add-on picker, and TMDB failing gives the failed
 * state with Try again, as a Provider's panel failing does (#459). IMDb ids and addresses are made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class AddonShowPageTest {

    private val compose = createComposeRule()

    private var tmdbDown = false

    /** TMDB knows the show but lists no seasons of it yet. */
    private var noSeasons = false

    private val network = FakeJsonHttp { request: Request ->
        when {
            request.url.encodedPath.startsWith("/stream/series/tt0000201:1:1") ->
                """{"streams": [{"url": "https://example.com/pilot.mkv", "title": "Pilot 1080p"}]}"""
            tmdbDown -> "!"
            noSeasons && request.url.encodedPath == "/3/tv/201" -> """{"name": "Lost Show", "seasons": []}"""
            else -> tmdbReply(request)
        }
    }

    @get:Rule
    val testGraph = TestGraph(compose, http = network.http)

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app = testGraph.app
    private val graph = testGraph.graph.apply { tmdbKey = "key" }
    private val played = mutableListOf<MediaKey>()

    @After
    fun close() {
        graph.settings.stremioAddons.value.forEach { graph.settings.removeStremioAddon(it.manifestUrl) }
    }

    private fun openPage() {
        graph.settings.addStremioAddon(
            StremioAddon(
                "https://example.com/manifest.json",
                "Example",
                listOf("stream"),
                listOf("movie", "series"),
                listOf("movie", "series"),
            ),
        )
        val show = runBlocking { graph.addonTitles.keepShow(LOST_SHOW) }
        val viewModel = testGraph.viewModel { VodViewModel(app, graph) }
        compose.setTvContent {
            SeriesDetailScreen(
                seriesId = show.id,
                onPlayEpisode = { played += it },
                onOpenPerson = {},
                onBack = {},
                viewModel = viewModel,
            )
        }
        compose.settleShowPage()
    }

    private fun press(key: Key) = compose.pressOnShowPage(key)

    @Test
    fun `an add-on show lists its seasons and episodes from TMDB, and starts on Play`() {
        openPage()

        compose.onAllNodes(hasText("Pilot", substring = true)).onFirst().assertExists()
        compose.showPagePill("Season 1").assertIsDisplayed()
        compose.showPagePill("Season 2").assertIsDisplayed()
        compose.episodeRow(1, 2).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.vod_play_episode, 1, 1)).assertIsFocused()
        captureScreen("series_detail_addon_show")
    }

    @Test
    fun `Play on an add-on show opens the add-on picker, and the stream picked plays the episode`() {
        openPage()

        press(Key.DirectionCenter)

        compose.onNodeWithText("Choose a stream").assertIsDisplayed()
        compose.onNode(hasText("Pilot 1080p", substring = true) and isFocused()).assertExists()
        captureScreen("series_detail_addon_show_picker")
        // On the picker's row, not whatever is focused: the page's button keeps focus in its own window.
        compose.onNode(hasText("Pilot 1080p", substring = true) and isFocused())
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.settleShowPage()

        val pilot = runBlocking {
            val addons = checkNotNull(graph.database.sources().addons())
            checkNotNull(graph.database.episodes().byProviderId(addons.id, "tt0000201:1:1"))
        }
        val picked = MediaKey.AddonStream(MediaKey.Episode(pilot.id), "https://example.com/pilot.mkv")
        assertThat(played).containsExactly(picked)
    }

    @Test
    fun `OK on an add-on episode opens the picker rather than the player`() {
        openPage()

        compose.episodeRow(1, 1).requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.settleShowPage()

        compose.onNodeWithText("Choose a stream").assertIsDisplayed()
        assertThat(played).isEmpty()
    }

    @Test
    fun `TMDB not answering gives the failed state, and Try again by remote brings the episodes`() {
        tmdbDown = true
        openPage()

        val failed = app.getString(R.string.vod_episodes_failed)
        compose.onAllNodes(hasText(failed)).onFirst().assertExists()
        // Never in a Provider's words: the show has no Provider.
        compose.onNodeWithText(app.getString(R.string.error_timed_out)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.vod_no_episodes)).assertDoesNotExist()
        captureScreen("series_detail_addon_show_failed")

        tmdbDown = false
        val tryAgain = app.getString(R.string.common_try_again)
        compose.onNode(hasText(tryAgain) and hasClickAction()).requestFocus()
        press(Key.DirectionCenter)

        compose.onAllNodes(hasText("Pilot", substring = true)).onFirst().assertExists()
        compose.onNodeWithText(failed).assertDoesNotExist()
    }

    @Test
    fun `an add-on show TMDB lists no episodes of says so without blaming a provider`() {
        noSeasons = true
        openPage()

        compose.onAllNodes(hasText(app.getString(R.string.vod_no_episodes_addons))).onFirst().assertExists()
        compose.onNodeWithText(app.getString(R.string.vod_no_episodes)).assertDoesNotExist()
        captureScreen("series_detail_addon_show_no_episodes")
    }

    private companion object {
        val LOST_SHOW = AddonTitle(
            tmdbId = "201",
            imdbId = "tt0000201",
            name = "Lost Show",
            year = 2010,
            posterUrl = null,
            backdropUrl = null,
            overview = "A show nobody carries.",
            isMovie = false,
        )
    }
}
