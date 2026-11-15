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
    fun close() = test.close()

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
    fun `a film page open while a refresh removes the film doesn't bring it back`() = runTest {
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
        opening.await()

        assertThat(test.catalog.allMovies().map { it.streamId }).containsExactly("2")
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
}
