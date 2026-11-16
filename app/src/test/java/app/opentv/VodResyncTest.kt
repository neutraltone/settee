/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A movies-and-shows refresh picks up what the provider changed without losing what was filled in since. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodResyncTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val db = test.db
    private val panel = test.panel

    @After
    fun close() {
        test.settings.setTmdbApiKey("")
        test.close()
    }

    @Test
    fun `a show the provider updated moves up Recently Added, keeping its place in the library`() = runTest {
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Show", "last_modified": "100"}]"""
        test.syncVod()
        val before = db.series().byProviderId(1, "7")!!

        panel.replies["get_series"] = """[{"series_id": 7, "name": "Show", "last_modified": "200"}]"""
        test.syncVod()

        val after = db.series().byProviderId(1, "7")!!
        assertThat(after.addedMillis).isEqualTo(200_000L)
        assertThat(after.id).isEqualTo(before.id)
    }

    @Test
    fun `a complete film list deletes the films it no longer has, keeping the rest as they were`() = runTest {
        panel.replies["get_vod_streams"] = films(1, 2, 3)
        test.syncVod()
        val kept = test.catalog.allMovies().associate { it.streamId to it.id }

        panel.replies["get_vod_streams"] = films(1, 2)
        test.syncVod()

        assertThat(test.catalog.allMovies().associate { it.streamId to it.id })
            .containsExactly("1", kept["1"], "2", kept["2"])
    }

    @Test
    fun `a film list that fails, is cut off or comes back empty deletes nothing`() = runTest {
        for (reply in listOf("!", films(1).dropLast(10), "[]")) {
            panel.replies["get_vod_streams"] = films(1, 2, 3)
            test.syncVod()

            panel.replies["get_vod_streams"] = reply
            test.syncVod()

            assertWithMessage(reply).that(test.catalog.allMovies().map { it.streamId }).containsExactly("1", "2", "3")
        }
    }

    @Test
    fun `a complete show list deletes the shows it no longer has, keeping the rest as they were`() = runTest {
        panel.replies["get_series"] = shows(1, 2, 3)
        test.syncVod()
        val kept = test.catalog.allSeries().associate { it.seriesId to it.id }

        panel.replies["get_series"] = shows(1, 2)
        test.syncVod()

        assertThat(test.catalog.allSeries().associate { it.seriesId to it.id })
            .containsExactly("1", kept["1"], "2", kept["2"])
    }

    @Test
    fun `a show list that fails, is cut off or comes back empty deletes nothing`() = runTest {
        for (reply in listOf("!", shows(1).dropLast(10), "[]")) {
            panel.replies["get_series"] = shows(1, 2, 3)
            test.syncVod()

            panel.replies["get_series"] = reply
            test.syncVod()

            assertWithMessage(reply).that(test.catalog.allSeries().map { it.seriesId }).containsExactly("1", "2", "3")
        }
    }

    @Test
    fun `a film page open while a refresh removes the film shows it as it was, without bringing it back`() = runTest {
        panel.replies["get_vod_streams"] = films(1, 2)
        test.syncVod()
        val film = test.catalog.allMovies().single { it.streamId == "1" }
        val hold = panel.hold("get_vod_info:1")

        val opening = async(Dispatchers.Default) { test.catalog.movieDetail(film.id) }
        during(hold) {
            panel.replies["get_vod_streams"] = films(2)
            test.syncVod()
            hold.release()
        }
        val shown = opening.await()

        assertThat(shown).isEqualTo(film)
        assertThat(test.catalog.allMovies().map { it.streamId }).containsExactly("2")
    }

    @Test
    fun `a film page open while a refresh changes the film keeps what the refresh changed`() = runTest {
        // The release date marks the film looked up after the first sync, so the background pass
        // doesn't ask about it again while the page's request is held.
        panel.replies["get_vod_info"] = """{"info": {"releasedate": "2011-09-02"}}"""
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "category_id": "1", "added": "100"}]"""
        test.syncVod()
        val film = test.catalog.allMovies().single()
        panel.replies["get_vod_info"] = """{"info": {"cast": "Ryan Gosling", "plot": "A driver."}}"""
        val hold = panel.hold("get_vod_info:3")

        val opening = async(Dispatchers.Default) { test.catalog.movieDetail(film.id) }
        during(hold) {
            panel.replies["get_vod_streams"] = """
                [{"stream_id": 3, "name": "Drive Redux", "stream_icon": "https://example.com/new.jpg",
                  "category_id": "2", "added": "300"}]
            """
            test.syncVod()
            hold.release()
        }
        val shown = opening.await()

        val movie = db.movies().byId(film.id)!!
        assertThat(movie.name).isEqualTo("Drive Redux")
        assertThat(movie.posterUrl).isEqualTo("https://example.com/new.jpg")
        assertThat(movie.categoryId).isEqualTo("2")
        assertThat(movie.addedMillis).isEqualTo(300_000L)
        assertThat(movie.cast).isEqualTo("Ryan Gosling")
        assertThat(movie.plot).isEqualTo("A driver.")
        assertThat(shown).isEqualTo(movie)
    }

    @Test
    fun `a show page open while a refresh changes the show keeps what the refresh changed`() = runTest {
        panel.replies["get_series"] =
            """[{"series_id": 7, "name": "Show", "category_id": "1", "last_modified": "100"}]"""
        test.syncVod()
        val show = test.catalog.allSeries().single()
        panel.replies["get_series_info:7"] = """{"info": {"genre": "Drama", "cast": "Someone"}, "episodes": {}}"""
        val hold = panel.hold("get_series_info:7")

        val opening = async(Dispatchers.Default) { test.catalog.openSeries(show.id).toList() }
        during(hold) {
            panel.replies["get_series"] =
                """[{"series_id": 7, "name": "Show Redux", "category_id": "2", "last_modified": "300"}]"""
            test.syncVod()
            hold.release()
        }
        val shown = opening.await()

        val stored = db.series().byId(show.id)!!
        assertThat(stored.name).isEqualTo("Show Redux")
        assertThat(stored.categoryId).isEqualTo("2")
        assertThat(stored.addedMillis).isEqualTo(300_000L)
        assertThat(stored.genre).isEqualTo("Drama")
        assertThat(stored.cast).isEqualTo("Someone")
        assertThat(shown).containsExactly(stored)
    }

    @Test
    fun `a show page open while a refresh changes the show keeps it when TMDB answers after`() = runTest {
        panel.replies["get_series"] =
            """[{"series_id": 7, "name": "Show", "category_id": "1", "last_modified": "100"}]"""
        test.syncVod()
        val show = test.catalog.allSeries().single()
        panel.replies["get_series_info:7"] = """{"info": {"genre": "Drama", "cast": "Someone"}, "episodes": {}}"""
        panel.replies["/3/search/tv"] = """{"results": [{"id": 22}]}"""
        panel.replies["/3/tv/22"] = """{"id": 22, "overview": "A show."}"""
        test.settings.setTmdbApiKey("k")
        val hold = panel.hold("/3/tv/22")

        val opening = async(Dispatchers.Default) { test.catalog.openSeries(show.id).toList() }
        during(hold) {
            panel.replies["get_series"] =
                """[{"series_id": 7, "name": "Show Redux", "category_id": "2", "last_modified": "300"}]"""
            // Not syncVod, which waits for the background TMDB pass, held here too.
            test.catalog.fetchVod(PANEL_SOURCE, nowUtcMillis = 0)
            hold.release()
        }
        val shown = opening.await().last()
        test.catalog.awaitBackground()

        val stored = db.series().byId(show.id)!!
        assertThat(stored.name).isEqualTo("Show Redux")
        assertThat(stored.categoryId).isEqualTo("2")
        assertThat(stored.addedMillis).isEqualTo(300_000L)
        assertThat(stored.genre).isEqualTo("Drama")
        assertThat(stored.plot).isEqualTo("A show.")
        // The background pass may save it again after it's shown, so not the whole row.
        assertThat(listOf(shown.name, shown.plot)).containsExactly("Show Redux", "A show.").inOrder()
    }

    @Test
    fun `a show page open while a refresh removes the show shows it as it was, without bringing it back`() = runTest {
        panel.replies["get_series"] = shows(1, 2)
        test.syncVod()
        val show = test.catalog.allSeries().single { it.seriesId == "1" }
        panel.replies["get_series_info:1"] = """{"info": {"genre": "Drama"}, "episodes": {}}"""
        val hold = panel.hold("get_series_info:1")

        val opening = async(Dispatchers.Default) { test.catalog.openSeries(show.id).toList() }
        during(hold) {
            panel.replies["get_series"] = shows(2)
            test.syncVod()
            hold.release()
        }
        val shown = opening.await()

        // The page doesn't wait for ever on a show that's gone; playing it says so then.
        assertThat(shown).containsExactly(show)
        assertThat(test.catalog.allSeries().map { it.seriesId }).containsExactly("2")
    }

    @Test
    fun `a show page open while a refresh gives the show its cover still fills in the panel's details`() = runTest {
        panel.replies["get_series"] =
            """[{"series_id": 7, "name": "Show", "category_id": "1", "last_modified": "100"}]"""
        test.syncVod()
        val show = test.catalog.allSeries().single()
        panel.replies["get_series_info:7"] =
            """{"info": {"genre": "Drama", "cast": "Someone", "plot": "A show."}, "episodes": {}}"""
        val hold = panel.hold("get_series_info:7")

        val opening = async(Dispatchers.Default) { test.catalog.openSeries(show.id).toList() }
        during(hold) {
            panel.replies["get_series"] = """
                [{"series_id": 7, "name": "Show", "cover": "https://example.com/cover.jpg",
                  "category_id": "1", "last_modified": "100"}]
            """
            test.syncVod()
            hold.release()
        }
        val shown = opening.await()

        val stored = db.series().byId(show.id)!!
        assertThat(stored.posterUrl).isEqualTo("https://example.com/cover.jpg")
        assertThat(listOf(stored.genre, stored.cast, stored.plot))
            .containsExactly("Drama", "Someone", "A show.")
            .inOrder()
        assertThat(shown).containsExactly(stored)
    }

    @Test
    fun `a film's TMDB answer doesn't replace one the background pass saved while the page was asking`() = runTest {
        panel.replies["get_vod_info"] = """{"info": {"releasedate": "2011-09-02"}}"""
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "100"}]"""
        test.syncVod()
        val film = test.catalog.allMovies().single()
        panel.replies["/3/search/movie"] = """{"results": [{"id": 11}]}"""
        panel.replies["/3/movie/11"] = """{"id": 11, "overview": "The page's answer."}"""
        test.settings.setTmdbApiKey("k")
        val hold = panel.hold("/3/movie/11")

        val opening = async(Dispatchers.Default) { test.catalog.movieDetail(film.id, now = NOW) }
        during(hold) {
            // As the background pass saves its own answer.
            db.movies().saveLookedUp(film.id) { it.copy(plot = "The pass's answer.", tmdbAnsweredAt = NOW - 1) }
            hold.release()
        }
        val shown = opening.await()

        val movie = db.movies().byId(film.id)!!
        assertThat(movie.plot).isEqualTo("The pass's answer.")
        assertThat(movie.tmdbAnsweredAt).isEqualTo(NOW - 1)
        assertThat(shown).isEqualTo(movie)
    }

    @Test
    fun `a refresh keeps the release date and artwork filled in when the movie was opened`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "100"}]"""
        panel.replies["get_vod_info"] =
            """{"info": {"releasedate": "2011-09-02", "backdrop_path": ["https://example.com/b.jpg"]}}"""
        test.syncVod()
        test.catalog.movieDetail(db.movies().all().single().id)

        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "300"}]"""
        test.syncVod()

        val movie = db.movies().all().single()
        assertThat(movie.addedMillis).isEqualTo(300_000L)
        assertThat(movie.releaseDate).isEqualTo(1_314_921_600_000L)
        assertThat(movie.backdropUrl).isEqualTo("https://example.com/b.jpg")
    }

    private fun films(vararg ids: Int) =
        ids.joinToString(",", "[", "]") { """{"stream_id": $it, "name": "Film $it", "added": "100"}""" }

    private fun shows(vararg ids: Int) =
        ids.joinToString(",", "[", "]") { """{"series_id": $it, "name": "Show $it", "last_modified": "100"}""" }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
