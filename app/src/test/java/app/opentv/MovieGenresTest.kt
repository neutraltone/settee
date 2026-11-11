/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Movie
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.MovieGenres
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The step after a sync that asks the provider for films' genres, which its film list leaves out, so
 * the genre rows have films to show.
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

    private fun movie(id: String, added: Long, sourceId: Long = 1, genre: String? = null) = Movie(
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
    fun `a film that already has a genre costs no call`() = runTest {
        test.db.movies().upsertAll(listOf(movie("known", added = 2, genre = "Drama"), movie("unknown", added = 1)))
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
    fun `only the 200 newest films without a genre from the synced provider are asked about`() = runTest {
        test.db.movies().upsertAll(
            (1..202).map { movie("$it", added = it.toLong()) } +
                movie("known", added = 999, genre = "Drama") +
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
    fun `the release date lookup's answer gives the film its genre too, without asking again`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive"}]"""
        panel.replies["get_vod_info"] = """{"info": {"genre": "Crime, Drama", "releasedate": "2011-09-02"}}"""

        test.syncVod()
        test.catalog.awaitBackground()

        assertThat(panel.asked.count { it == "get_vod_info:3" }).isEqualTo(1)
        assertThat(test.db.movies().all().single().genre).isEqualTo("Crime, Drama")
    }

    @Test
    fun `a film given its genre afterwards still gets its artwork from the provider when opened`() = runTest {
        // Dated too, so opening it has no date left to ask the provider for.
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive"}]"""
        panel.replies["get_vod_info"] = """{"info": {"genre": "Crime, Drama", "releasedate": "2011-09-02"}}"""
        test.syncVod()
        test.catalog.awaitBackground()
        panel.replies["get_vod_info"] = """{"info": {"backdrop_path": ["https://example.com/b.jpg"]}}"""

        val opened = test.catalog.movieDetail(test.db.movies().all().single().id)

        assertThat(opened?.backdropUrl).isEqualTo("https://example.com/b.jpg")
    }
}
