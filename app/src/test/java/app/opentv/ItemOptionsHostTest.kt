/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.MediaKey
import app.opentv.ui.VodViewModel.ResumeItem
import app.opentv.ui.options.ItemOptionsHost
import app.opentv.ui.options.OptionsDestination
import app.opentv.ui.vod.ContinueWatchingRow
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The options panel over a Continue Watching card, with its real view model and database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ItemOptionsHostTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val graph = ServiceLocator.get(ApplicationProvider.getApplicationContext<Application>())
    private val events = mutableListOf<Any>()

    private val movie = Movie(
        id = 41,
        sourceId = 1,
        streamId = "41",
        name = "Heat",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "",
    )
    private val series = Series(
        id = 42,
        sourceId = 1,
        seriesId = "s",
        name = "The Show",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        backdropUrl = null,
    )
    private val episode = testEpisode(1, 1)

    private val movieCard = ResumeItem(
        MediaKey.Movie(movie.id),
        movie.name,
        posterUrl = null,
        progress = 0.5f,
        card = ContinueWatchingCard.Movie(movie.id, 0.5f),
    )
    private val upNextCard = ResumeItem(
        MediaKey.Episode(episode.id),
        series.name,
        posterUrl = null,
        progress = null,
        card = ContinueWatchingCard.Series(episode, progress = null),
    )

    // The graph is process-wide, so its database outlives each test.
    @Before
    fun catalogue(): Unit = runBlocking {
        if (graph.sourceRepository.byId(1) == null) {
            graph.database.sources().insert(
                Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"),
            )
        }
        graph.database.movies().upsertAll(listOf(movie))
        graph.database.series().upsertAll(listOf(series))
        graph.database.episodes().upsertAll(listOf(episode))
        graph.viewerLibrary.favourites.set(movie, false)
        graph.viewerLibrary.marks.markUnwatched("movie:${movie.id}")
        graph.viewerLibrary.marks.markUnwatched("ep:${episode.id}")
        graph.viewerLibrary.recordProgress("movie:${movie.id}", positionMillis = 600_000, durationMillis = 1_200_000)
    }

    private fun openOptionsOn(card: ResumeItem) {
        compose.setTvContent {
            ItemOptionsHost(navigate = { events += it }) {
                ContinueWatchingRow(
                    listOf(card),
                    onResume = { events += "resume" },
                    onLeave = {
                        { go ->
                            events += "before leaving"
                            go()
                        }
                    },
                )
            }
        }
        waitFor(card.title).requestFocus().performKeyInput { pressKey(Key.Menu) }
    }

    private fun waitFor(text: String): SemanticsNodeInteraction {
        compose.waitUntilAtLeastOneExists(hasText(text), TIMEOUT_MILLIS)
        return compose.onNodeWithText(text)
    }

    // Idles between checks rather than using waitUntil, which timed out here with the write unfinished.
    private fun becameFavourite(movie: Movie): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        fun stored() = runBlocking {
            graph.viewerLibrary.favourites.contains(StreamKind.MOVIE, movie.sourceId, movie.streamId)
        }
        while (!stored() && System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            Thread.sleep(POLL_MILLIS)
        }
        return stored()
    }

    @Test
    fun `Play from start comes first on a card In Progress, and opens the player from the start`() {
        openOptionsOn(movieCard)

        waitFor("Play from start").assertIsFocused()
        waitFor("Show info").assertExists()
        captureScreen("options_panel_continue_watching")
        waitFor("Play from start").performClick()

        assertThat(events)
            .containsExactly("before leaving", OptionsDestination.PlayFromStart(MediaKey.Movie(movie.id)))
            .inOrder()
    }

    @Test
    fun `Show info on a movie's card opens the movie's page`() {
        openOptionsOn(movieCard)

        waitFor("Show info").performClick()

        assertThat(events).containsExactly("before leaving", OptionsDestination.MoviePage(movie.id)).inOrder()
    }

    @Test
    fun `Show info on a series' card opens the series' page, not the episode's player`() {
        openOptionsOn(upNextCard)

        waitFor("Show info").performClick()

        assertThat(events).containsExactly("before leaving", OptionsDestination.SeriesPage(series.id)).inOrder()
    }

    @Test
    fun `an Up Next never played has nothing to play from the start, so Show info comes first`() {
        openOptionsOn(upNextCard)

        waitFor("Show info").assertIsFocused()
        compose.onNodeWithText("Play from start").assertDoesNotExist()
    }

    @Test
    fun `a card whose title has left the catalogue offers neither action`() {
        val gone = 999L
        runBlocking { graph.viewerLibrary.recordProgress("movie:$gone", 600_000, 1_200_000) }

        openOptionsOn(
            ResumeItem(
                MediaKey.Movie(gone),
                "Gone",
                posterUrl = null,
                progress = 0.5f,
                card = ContinueWatchingCard.Movie(gone, 0.5f),
            ),
        )

        waitFor("Mark watched").assertExists()
        compose.onNodeWithText("Show info").assertDoesNotExist()
        compose.onNodeWithText("Play from start").assertDoesNotExist()
    }

    @Test
    fun `the panel's other options stay on the screen`() {
        openOptionsOn(movieCard)

        waitFor("Add to Favourites").performClick()

        assertThat(becameFavourite(movie)).isTrue()
        assertThat(events).isEmpty()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
