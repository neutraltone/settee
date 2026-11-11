/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Movie
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.data.repo.MovieGenres
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The background job that asks the provider (and TMDB, with the viewer's key) for films' genres,
 * which the provider's film list leaves out, so the genre rows have films to show.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MovieGenresTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val panel = test.panel
    private val step = MovieGenres(test.db.movies(), XtreamApi(panel.http))

    @After
    fun close() = test.close()

    private fun movie(id: String, added: Long, sourceId: Long = 1, genre: String? = null, lookedUp: Boolean = false) =
        Movie(
            sourceId = sourceId,
            streamId = id,
            name = "Movie $id",
            categoryId = null,
            posterUrl = null,
            rating = null,
            year = null,
            plot = null,
            durationSeconds = null,
            containerExtension = "mp4",
            streamUrl = "http://example.com/$sourceId/$id.mp4",
            addedMillis = added,
            genre = genre,
            detailsLookedUp = lookedUp,
        )

    private suspend fun genre(id: String): String? = test.db.movies().forSource(1).single { it.streamId == id }.genre

    @Test
    fun `a film the provider has a genre for gets it`() = runTest {
        test.db.movies().upsertAll(listOf(movie("7", added = 1)))
        panel.replies["get_vod_info"] = """{"info": {"genre": "Action, Thriller"}}"""

        step.refresh(PANEL_SOURCE)

        assertThat(genre("7")).isEqualTo("Action, Thriller")
    }

    @Test
    fun `a film already looked up costs no call`() = runTest {
        test.db.movies().upsertAll(
            listOf(movie("known", added = 2, genre = "Drama", lookedUp = true), movie("unknown", added = 1)),
        )
        panel.replies["get_vod_info"] = """{"info": {"genre": "Comedy"}}"""

        step.refresh(PANEL_SOURCE)

        assertThat(panel.asked).containsExactly("get_vod_info:unknown")
        assertThat(genre("known")).isEqualTo("Drama")
    }

    @Test
    fun `a film the provider has no genre for isn't asked about again`() = runTest {
        test.db.movies().upsertAll(listOf(movie("7", added = 1)))
        panel.replies["get_vod_info"] = """{"info": {"year": "2011"}}"""

        step.refresh(PANEL_SOURCE)
        step.refresh(PANEL_SOURCE)

        assertThat(panel.asked).containsExactly("get_vod_info:7")
    }

    @Test
    fun `a film the provider couldn't answer for is asked about again next sync`() = runTest {
        test.db.movies().upsertAll(listOf(movie("7", added = 1)))
        panel.replies["get_vod_info"] = "!"

        step.refresh(PANEL_SOURCE)
        step.refresh(PANEL_SOURCE)

        assertThat(panel.asked).containsExactly("get_vod_info:7", "get_vod_info:7")
        assertThat(genre("7")).isNull()
    }

    @Test
    fun `a film whose genre came with the list is asked for its details but keeps that genre`() = runTest {
        test.db.movies().upsertAll(listOf(movie("7", added = 1, genre = "Drama")))
        panel.replies["get_vod_info"] = """{"info": {"genre": "Comedy", "director": "Agnès Varda"}}"""

        step.refresh(PANEL_SOURCE)

        assertThat(panel.asked).containsExactly("get_vod_info:7")
        assertThat(genre("7")).isEqualTo("Drama")
    }

    @Test
    fun `only the 200 newest films not looked up from the synced provider are asked about`() = runTest {
        test.db.movies().upsertAll(
            (1..202).map { movie("$it", added = it.toLong()) } +
                movie("known", added = 999, genre = "Drama", lookedUp = true) +
                movie("other", added = 999, sourceId = 2),
        )
        panel.replies["get_vod_info"] = """{"info": {"genre": "Comedy"}}"""

        step.refresh(PANEL_SOURCE)

        assertThat(panel.asked.map { it.substringAfter(':') }).containsExactlyElementsIn((3..202).map { "$it" })
    }

    @Test
    fun `the next sync moves on to older films`() = runTest {
        test.db.movies().upsertAll((1..201).map { movie("$it", added = it.toLong()) })
        panel.replies["get_vod_info"] = """{"info": {"genre": "Comedy"}}"""

        step.refresh(PANEL_SOURCE)
        panel.asked.clear()
        step.refresh(PANEL_SOURCE)

        assertThat(panel.asked).containsExactly("get_vod_info:1")
    }

    @Test
    fun `a sync fills films' genres in afterwards`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive"}]"""
        panel.replies["get_vod_info"] = """{"info": {"genre": "Crime, Drama"}}"""

        test.syncVod()
        test.catalog.awaitBackground()

        assertThat(test.db.movies().all().single().genre).isEqualTo("Crime, Drama")
    }

    @Test
    fun `a sync asks the provider about each film's genre once`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive"}]"""
        panel.replies["get_vod_info"] = """{"info": {"genre": "Crime, Drama", "releasedate": "2011-09-02"}}"""

        test.syncVod()
        test.catalog.awaitBackground()

        assertThat(panel.asked.count { it == "get_vod_info:3" }).isEqualTo(1)
        assertThat(test.db.movies().all().single().genre).isEqualTo("Crime, Drama")
    }

    @Test
    fun `a film given its genre afterwards still gets its artwork from the provider when opened`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive"}]"""
        panel.replies["get_vod_info"] = """{"info": {"genre": "Crime, Drama", "releasedate": "2011-09-02"}}"""
        test.syncVod()
        test.catalog.awaitBackground()
        panel.replies["get_vod_info"] = """{"info": {"backdrop_path": ["https://example.com/b.jpg"]}}"""

        val opened = test.catalog.movieDetail(test.db.movies().all().single().id)

        assertThat(opened?.backdropUrl).isEqualTo("https://example.com/b.jpg")
    }

    @Test
    fun `a film the provider has no genre for gets TMDB's, with the viewer's key`() = runTest {
        test.db.movies().upsertAll(listOf(movie("7", added = 1)))
        panel.replies["get_vod_info"] = """{"info": {"year": "2011"}}"""
        val withTmdb = MovieGenres(test.db.movies(), XtreamApi(panel.http), tmdbGenre = { "Crime, Drama" })

        withTmdb.refresh(PANEL_SOURCE)

        assertThat(genre("7")).isEqualTo("Crime, Drama")
    }

    @Test
    fun `a film TMDB couldn't be reached about is left to ask about next time`() = runTest {
        test.db.movies().upsertAll(listOf(movie("7", added = 1)))
        panel.replies["get_vod_info"] = """{"info": {"year": "2011"}}"""
        val unreachable = MovieGenres(test.db.movies(), XtreamApi(panel.http), tmdbGenre = { error("offline") })

        unreachable.refresh(PANEL_SOURCE)

        assertThat(genre("7")).isNull()
    }

    @Test
    fun `filling in keeps going a batch at a time until every film has been asked about`() = runTest {
        test.db.movies().upsertAll((1..5).map { movie("$it", added = it.toLong()) })
        panel.replies["get_vod_info"] = """{"info": {"genre": "Comedy"}}"""
        val inTwos = MovieGenres(test.db.movies(), XtreamApi(panel.http), count = 2, pauseMillis = 0)

        inTwos.fillAll(PANEL_SOURCE)

        assertThat(test.db.movies().forSource(1).map { it.genre }).containsExactly(
            "Comedy",
            "Comedy",
            "Comedy",
            "Comedy",
            "Comedy",
        )
    }

    @Test
    fun `filling in asks about a film whose lookup failed only once`() = runTest {
        test.db.movies().upsertAll((1..3).map { movie("$it", added = it.toLong()) })
        panel.replies["get_vod_info"] = """{"info": {"year": "2011"}}"""
        val unreachable = MovieGenres(
            test.db.movies(),
            XtreamApi(panel.http),
            count = 2,
            pauseMillis = 0,
            tmdbGenre = { error("offline") },
        )

        unreachable.fillAll(PANEL_SOURCE)

        assertThat(panel.asked).containsExactly("get_vod_info:1", "get_vod_info:2", "get_vod_info:3")
    }

    @Test
    fun `opening Movies fills genres in even when the film list is fresh`() = runTest {
        test.db.sources().insert(PANEL_SOURCE)
        test.db.movies().upsertAll(listOf(movie("7", added = 1)))
        test.settings.setVodFetchedAtMillis(PANEL_SOURCE.id, 0L)
        panel.replies["get_vod_info"] = """{"info": {"genre": "Western"}}"""

        test.refresh().refresh(CatalogueRefresh.Trigger.OpenedMoviesOrShows)
        test.catalog.awaitBackground()

        assertThat(panel.asked).doesNotContain("get_vod_streams")
        assertThat(genre("7")).isEqualTo("Western")
    }

    @Test
    fun `a film saved blank gets TMDB's genre on the pass over blanks, without asking the provider again`() = runTest {
        test.db.movies().upsertAll(listOf(movie("7", added = 1, genre = ""), movie("8", added = 2, genre = "Drama")))
        val withTmdb = MovieGenres(test.db.movies(), XtreamApi(panel.http), tmdbGenre = { "Western" })

        assertThat(withTmdb.retryBlanks(PANEL_SOURCE)).isTrue()

        assertThat(genre("7")).isEqualTo("Western")
        assertThat(genre("8")).isEqualTo("Drama")
        assertThat(panel.asked).isEmpty()
    }

    @Test
    fun `the pass over blanks finishes even when TMDB knows none of them`() = runTest {
        test.db.movies().upsertAll((1..7).map { movie("$it", added = it.toLong(), genre = "") })
        var asked = 0
        val noMatches = MovieGenres(test.db.movies(), XtreamApi(panel.http), count = 2, pauseMillis = 0) {
            asked++
            null
        }

        assertThat(noMatches.retryBlanks(PANEL_SOURCE)).isTrue()

        assertThat(asked).isEqualTo(7)
        assertThat(test.db.movies().forSource(1).map { it.genre }.toSet()).containsExactly("")
    }

    @Test
    fun `the pass over blanks gives up, unfinished, when TMDB can't be reached`() = runTest {
        test.db.movies().upsertAll((1..7).map { movie("$it", added = it.toLong(), genre = "") })
        val offline = MovieGenres(test.db.movies(), XtreamApi(panel.http), tmdbGenre = { error("offline") })

        assertThat(offline.retryBlanks(PANEL_SOURCE)).isFalse()
    }
}
