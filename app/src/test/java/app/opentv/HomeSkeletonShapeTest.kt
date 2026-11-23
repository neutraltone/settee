/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.HomeArrangement
import app.opentv.data.repo.HomeRowKind
import app.opentv.ui.HomeState
import app.opentv.ui.HomeViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which of Home's rows hold their place as skeletons before their cards are built (#581): worked out
 * from quick checks of the Profile's own small tables, so a skeleton row never disappears and moves
 * the rows below it. Every read of the catalogue is held here, the way a big one is slow on a Stick.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeSkeletonShapeTest {

    @get:Rule
    val testGraph = TestGraph(name = "home-skeleton-shape-test")

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val db = graph.database

    @Before
    fun setUp(): Unit = runBlocking {
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
        db.movies().upsertAll(listOf(superbad))
        db.series().upsertAll(listOf(severance))
    }

    @Test
    fun `a Profile with something played and a Favourite show gets places for them and Recently Added`() {
        runBlocking {
            graph.viewerLibrary.recordProgress(
                "movie:1",
                positionMillis = FILM_MILLIS / 2,
                durationMillis = FILM_MILLIS,
            )
            graph.viewerLibrary.favourites.set(severance, true)
        }
        testGraph.hold()

        val first = firstState()

        assertThat(first.loading).containsExactly(
            HomeRowKind.CONTINUE_WATCHING,
            HomeRowKind.FAVOURITE_SHOWS,
            HomeRowKind.RECENT_SHOWS,
            HomeRowKind.RECENT_MOVIES,
        )
    }

    @Test
    fun `a Profile that has played nothing gets no place for Continue Watching`() {
        testGraph.hold()

        val first = firstState()

        assertThat(first.loading).containsExactly(HomeRowKind.RECENT_SHOWS, HomeRowKind.RECENT_MOVIES)
    }

    @Test
    fun `a Profile whose only film is finished gets no place for Continue Watching`() {
        runBlocking {
            graph.viewerLibrary.recordProgress("movie:1", positionMillis = FILM_MILLIS, durationMillis = FILM_MILLIS)
        }
        testGraph.hold()

        val first = firstState()

        assertThat(first.loading).containsExactly(HomeRowKind.RECENT_SHOWS, HomeRowKind.RECENT_MOVIES)
    }

    @Test
    fun `a finished episode still gets a place, for its Up Next`() {
        runBlocking {
            graph.viewerLibrary.recordProgress("ep:5", positionMillis = FILM_MILLIS, durationMillis = FILM_MILLIS)
        }
        testGraph.hold()

        val first = firstState()

        assertThat(first.loading).contains(HomeRowKind.CONTINUE_WATCHING)
    }

    @Test
    fun `a Profile whose last watch was live gets a place for the Live Preview`() {
        runBlocking {
            db.channels().replaceCatalogue(1, listOf(bbcOne), syncStamp = 1)
            graph.viewerLibrary.live.record(db.channels().everyRow().single())
        }
        testGraph.hold()

        val first = firstState()

        assertThat(first.liveLoading).isTrue()
    }

    @Test
    fun `a Profile that has watched nothing live gets no place for the Live Preview`() {
        testGraph.hold()

        val first = firstState()

        assertThat(first.liveLoading).isFalse()
    }

    @Test
    fun `a row the Profile hid never gets a place`() {
        runBlocking {
            graph.viewerLibrary.recordProgress(
                "movie:1",
                positionMillis = FILM_MILLIS / 2,
                durationMillis = FILM_MILLIS,
            )
            graph.profileSettings.arrangeHome(HomeArrangement.USUAL.withShown(HomeRowKind.CONTINUE_WATCHING, false))
        }
        testGraph.hold()

        val first = firstState()

        assertThat(first.loading).containsExactly(HomeRowKind.RECENT_SHOWS, HomeRowKind.RECENT_MOVIES)
    }

    @Test
    fun `each place goes as its row's cards come, Continue Watching's with the film in it`() {
        runBlocking {
            graph.viewerLibrary.recordProgress(
                "movie:1",
                positionMillis = FILM_MILLIS / 2,
                durationMillis = FILM_MILLIS,
            )
        }
        testGraph.hold()
        val home = testGraph.viewModel { HomeViewModel(app, graph) }
        testGraph.firstWhenIdle(home.state.filterNotNull())

        testGraph.release()
        val settled = testGraph.firstWhenIdle(home.state.filterNotNull()) { it.loading.isEmpty() }

        assertThat(settled.continueWatching.map { it.name }).containsExactly("Superbad")
    }

    /** Home's first state, once the quick checks are back with the catalogue's reads held. */
    private fun firstState(): HomeState {
        val home = testGraph.viewModel { HomeViewModel(app, graph) }
        return testGraph.firstWhenIdle(home.state.filterNotNull())
    }

    private val superbad = Movie(
        id = 1,
        sourceId = 1,
        streamId = "1",
        name = "Superbad",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 2007,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/1",
    )

    private val bbcOne = Channel(
        sourceId = 1,
        streamId = "1",
        name = "BBC One",
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = 1,
        streamUrl = "http://example.com/live/u/p/1.ts",
    )

    private val severance = Series(
        id = 2,
        sourceId = 1,
        seriesId = "2",
        name = "Severance",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
    )

    private companion object {
        const val FILM_MILLIS = 6_000_000L
    }
}
