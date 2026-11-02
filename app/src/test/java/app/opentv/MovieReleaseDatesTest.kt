/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Movie
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.MovieReleaseDates
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The step after a sync that finds when recently added movies came out. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MovieReleaseDatesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val panel = FakePanel()
    private val tmdbAsked = mutableListOf<String>()
    private var tmdbReleaseDates = mapOf<String, Long>()
    private val step = MovieReleaseDates(db.movies(), XtreamApi(panel.http)) { movie ->
        tmdbAsked += movie.streamId
        tmdbReleaseDates[movie.streamId]
    }

    @After
    fun close() = db.close()

    private fun movie(id: String, added: Long, sourceId: Long = 1, releaseDate: Long? = null) = Movie(
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
        releaseDate = releaseDate,
    )

    private suspend fun releaseDate(id: String): Long? = db.movies().forSource(1).single {
        it.streamId == id
    }.releaseDate

    @Test
    fun `a movie the provider can date gets that date, without asking TMDB`() = runTest {
        db.movies().upsertAll(listOf(movie("7", added = 1)))
        panel.replies["get_vod_info"] = """{"info": {"releasedate": "2011-09-02"}}"""

        step.refresh(PANEL_SOURCE)

        assertThat(releaseDate("7")).isEqualTo(1_314_921_600_000L)
        assertThat(tmdbAsked).isEmpty()
    }

    @Test
    fun `when the provider answers without a date, TMDB's is used`() = runTest {
        db.movies().upsertAll(listOf(movie("7", added = 1)))
        panel.replies["get_vod_info"] = """{"info": {"year": "2011"}}"""
        tmdbReleaseDates = mapOf("7" to 1_314_921_600_000L)

        step.refresh(PANEL_SOURCE)

        assertThat(releaseDate("7")).isEqualTo(1_314_921_600_000L)
    }

    @Test
    fun `a movie that already has a date costs no calls, and an undated one is asked about every time`() = runTest {
        db.movies().upsertAll(listOf(movie("dated", added = 2, releaseDate = 500), movie("undated", added = 1)))
        panel.replies["get_vod_info"] = """{"info": {}}"""

        step.refresh(PANEL_SOURCE)
        step.refresh(PANEL_SOURCE)

        assertThat(panel.asked).containsExactly("get_vod_info:undated", "get_vod_info:undated")
        assertThat(tmdbAsked).containsExactly("undated", "undated")
        assertThat(releaseDate("dated")).isEqualTo(500)
    }

    @Test
    fun `only the 50 most recently added movies from the synced provider are looked up`() = runTest {
        db.movies().upsertAll(
            (1..52).map { movie("$it", added = it.toLong()) } + movie("other", added = 99, sourceId = 2),
        )

        step.refresh(PANEL_SOURCE)

        assertThat(panel.asked.map { it.substringAfter(':') }).containsExactlyElementsIn((3..52).map { "$it" })
    }

    @Test
    fun `dated movies still count towards the 50, so older undated ones aren't reached for`() = runTest {
        db.movies().upsertAll(
            (1..50).map { movie("$it", added = it + 1L, releaseDate = 500) } + movie("older", added = 1),
        )

        step.refresh(PANEL_SOURCE)

        assertThat(panel.asked).isEmpty()
    }

    @Test
    fun `when the provider can't answer, the movie stays undated and TMDB isn't asked`() = runTest {
        db.movies().upsertAll(listOf(movie("broken", added = 2), movie("fine", added = 1)))
        panel.replies["get_vod_info:broken"] = "!"
        panel.replies["get_vod_info:fine"] = """{"info": {"releasedate": "2011-09-02"}}"""

        step.refresh(PANEL_SOURCE)

        assertThat(releaseDate("broken")).isNull()
        assertThat(releaseDate("fine")).isEqualTo(1_314_921_600_000L)
        assertThat(tmdbAsked).isEmpty()
    }

    @Test
    fun `a TMDB lookup that throws costs only that movie`() = runTest {
        db.movies().upsertAll(listOf(movie("throws", added = 2), movie("fine", added = 1)))
        panel.replies["get_vod_info:throws"] = """{"info": {}}"""
        panel.replies["get_vod_info:fine"] = """{"info": {"releasedate": "2011-09-02"}}"""
        val throwing = MovieReleaseDates(db.movies(), XtreamApi(panel.http)) { error("TMDB is down") }

        throwing.refresh(PANEL_SOURCE)

        assertThat(releaseDate("throws")).isNull()
        assertThat(releaseDate("fine")).isEqualTo(1_314_921_600_000L)
    }

    @Test
    fun `a provider answering with an empty info still sends the movie to TMDB`() = runTest {
        db.movies().upsertAll(listOf(movie("7", added = 1)))
        panel.replies["get_vod_info"] = """{"info": []}"""
        tmdbReleaseDates = mapOf("7" to 1_314_921_600_000L)

        step.refresh(PANEL_SOURCE)

        assertThat(releaseDate("7")).isEqualTo(1_314_921_600_000L)
    }

    @Test
    fun `a provider that stops answering is left alone after five in a row, keeping what it already gave`() = runTest {
        db.movies().upsertAll(listOf(movie("fine", added = 9)) + (1..8).map { movie("$it", added = it.toLong()) })
        panel.replies["get_vod_info"] = "!"
        panel.replies["get_vod_info:fine"] = """{"info": {"releasedate": "2011-09-02"}}"""

        step.refresh(PANEL_SOURCE)

        assertThat(panel.asked).hasSize(6)
        assertThat(releaseDate("fine")).isEqualTo(1_314_921_600_000L)
    }

    @Test
    fun `an answer starts the count of failures again`() = runTest {
        db.movies().upsertAll((1..10).map { movie("$it", added = it.toLong()) })
        panel.replies["get_vod_info"] = "!"
        panel.replies["get_vod_info:6"] = """{"info": {}}"""

        step.refresh(PANEL_SOURCE)

        assertThat(panel.asked).hasSize(10)
    }

    @Test
    fun `dates are saved ten at a time, so the Movies tab isn't reloaded for each one`() = runTest {
        db.movies().upsertAll((1..11).map { movie("$it", added = it.toLong()) })
        panel.replies["get_vod_info"] = """{"info": {"releasedate": "2011-09-02"}}"""
        val fifth = panel.hold("get_vod_info:7")
        val eleventh = panel.hold("get_vod_info:1")

        withContext(Dispatchers.Default) {
            val lookup = async { step.refresh(PANEL_SOURCE) }
            during(fifth) {
                assertThat(db.movies().forSource(1).count { it.releaseDate != null }).isEqualTo(0)
            }
            fifth.release()
            during(eleventh) {
                assertThat(db.movies().forSource(1).count { it.releaseDate != null }).isEqualTo(10)
            }
            eleventh.release()
            lookup.await()
        }

        assertThat(db.movies().forSource(1).count { it.releaseDate != null }).isEqualTo(11)
    }

    @Test
    fun `movies added at the same moment go newest row first, so a new film still gets looked up`() = runTest {
        // A panel with no `added` field leaves every movie at 0.
        db.movies().upsertAll((1..51).map { movie("$it", added = 0) })

        step.refresh(PANEL_SOURCE)

        assertThat(panel.asked.map { it.substringAfter(':') }).containsExactlyElementsIn((2..51).map { "$it" })
    }

    @Test(expected = CancellationException::class)
    fun `cancellation still gets out`() = runTest {
        db.movies().upsertAll(listOf(movie("7", added = 1)))
        panel.replies["get_vod_info"] = """{"info": {}}"""
        val cancelled = MovieReleaseDates(db.movies(), XtreamApi(panel.http)) { throw CancellationException("gone") }

        cancelled.refresh(PANEL_SOURCE)
    }
}
