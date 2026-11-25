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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.espresso.Espresso
import app.opentv.core.SleepTimer
import app.opentv.data.model.StremioAddon
import app.opentv.data.repo.AddonTitle
import app.opentv.data.repo.MediaKey
import app.opentv.ui.player.SubtitlePicks
import app.opentv.ui.vod.VodPlayerContent
import app.opentv.ui.vod.VodPlayerViewModel
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
 * The player opened on a film kept from an add-on, as a Continue Watching card opens it: it has no stream
 * of its own, so the viewer sees its add-on streams to pick from, not a player with nothing in it.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class VodPlayerAddonPickTest {

    private val compose = createComposeRule()

    private val network = FakeJsonHttp { request: Request ->
        if (request.url.host == "films.example.com") {
            """{"streams": [{"url": "$STREAM", "title": "Lost Film 1080p"}]}"""
        } else {
            "404"
        }
    }

    @get:Rule
    val testGraph = TestGraph(compose, name = "vod-player-addon-pick-test", http = network.http)

    private val app = testGraph.app
    private val players = TestPlayers(app)
    private val controller = players.controller()
    private val graph = testGraph.graph
    private val viewModel = testGraph.track(VodPlayerViewModel(app, graph))
    private val playback = FakePlayback()
    private val noPicks = SubtitlePicks(onOff = {}, onPicked = {})
    private var backs = 0

    @After
    fun release() {
        SleepTimer.clear()
        controller.release()
        players.close()
        graph.settings.stremioAddons.value.forEach { graph.settings.removeStremioAddon(it.manifestUrl) }
    }

    /** The player opened on the kept film, part way through, as Continue Watching opens it. */
    private fun openFromContinueWatching(): Long {
        graph.settings.addStremioAddon(
            StremioAddon("https://films.example.com/manifest.json", "Films", listOf("stream"), listOf("movie")),
        )
        val film = runBlocking {
            graph.addonTitles.keepFilm(FILM).also {
                graph.viewerLibrary.recordProgress("movie:${it.id}", positionMillis = 600_000, EPISODE_MILLIS)
                viewModel.attach(MediaKey.Movie(it.id), playback)
            }
        }
        compose.setTvContent {
            VodPlayerContent(
                controller,
                viewModel,
                false,
                noPicks,
                onBack = { backs++ },
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("Lost Film 1080p").assertIsFocused()
        return film.id
    }

    @Test
    fun `a film kept from an add-on shows its add-on streams, and plays the one picked from where it stopped`() {
        val filmId = openFromContinueWatching()

        compose.onNodeWithText("Choose a stream").assertIsDisplayed()
        captureScreen("vod_player_addon_pick")
        // OK on the stream the picker opened with the Highlight on.
        compose.onNodeWithText("Lost Film 1080p").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertThat(playback.played).containsExactly(MediaKey.AddonStream(MediaKey.Movie(filmId), STREAM) to 600_000L)
        compose.onNodeWithText("Choose a stream").assertDoesNotExist()
        assertThat(backs).isEqualTo(0)
    }

    @Test
    fun `Back on its streams leaves the player, as nothing else here can play`() {
        openFromContinueWatching()

        Espresso.pressBack()
        compose.waitForIdle()

        assertThat(backs).isEqualTo(1)
        assertThat(playback.played).isEmpty()
    }

    private companion object {
        const val STREAM = "https://example.com/lost-film.mkv"

        val FILM = AddonTitle(
            tmdbId = "101",
            imdbId = "tt0000101",
            name = "Lost Film",
            year = 1999,
            posterUrl = null,
            backdropUrl = null,
            overview = null,
        )
    }
}
