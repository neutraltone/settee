/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A movies-and-shows sync goes on to date what it saved, in the background. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ReleaseDateSyncTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val db = test.db
    private val panel = test.panel

    /** After Drive's 2011-09-02 release. */
    private val soonAfterRelease = 1_314_921_600_000L + 1_000

    @After
    fun close() {
        // AppSettings is a process-wide singleton, so a key one test sets would reach the next.
        test.settings.setTmdbApiKey("")
        test.close()
    }

    private suspend fun syncAndDate() {
        test.syncVod()
        test.catalog.awaitBackground()
    }

    @Test
    fun `movies a provider's list leaves undated reach the row without being opened`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "100"}]"""
        panel.replies["get_vod_info"] = """{"info": {"releasedate": "2011-09-02"}}"""

        syncAndDate()

        val row = test.catalog.recentlyReleasedMovies(soonAfterRelease).first()
        assertThat(row.map { it.name }).containsExactly("Drive")
    }

    @Test
    fun `movies are dated first, then shows, one lookup after the other`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive"}]"""
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Show"}]"""

        syncAndDate()

        assertThat(panel.asked.filter { ':' in it }).containsExactly("get_vod_info:3", "get_series_info:7").inOrder()
    }

    @Test
    fun `shows still get dates when the sync saved no movies`() = runTest {
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Show"}]"""
        panel.replies["get_series_info"] = """{"info": {"releaseDate": "2011-09-02"}}"""

        syncAndDate()

        assertThat(db.series().byProviderId(1, "7")?.latestAirDate).isEqualTo(1_314_921_600_000L)
    }

    @Test
    fun `a refresh keeps a date the lookup found, and doesn't ask for it again`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "100"}]"""
        panel.replies["get_vod_info"] = """{"info": {"releasedate": "2011-09-02"}}"""
        syncAndDate()
        panel.asked.clear()

        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "300"}]"""
        syncAndDate()

        val movie = db.movies().all().single()
        assertThat(movie.addedMillis).isEqualTo(300_000L)
        assertThat(movie.releaseDate).isEqualTo(1_314_921_600_000L)
        assertThat(panel.asked).doesNotContain("get_vod_info:3")
    }

    @Test
    fun `a panel failing every date lookup costs neither the sync nor the catalogue`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive"}]"""
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Show"}]"""
        panel.replies["get_vod_info"] = "!"
        panel.replies["get_series_info"] = "!"
        db.sources().insert(PANEL_SOURCE)

        val fetch = test.catalog.fetchVod(PANEL_SOURCE, nowUtcMillis = 0)
        test.catalog.awaitBackground()

        assertThat(fetch.complete).isTrue()
        assertThat(db.movies().all().single().releaseDate).isNull()
        assertThat(db.series().byProviderId(1, "7")).isNotNull()
    }

    @Test
    fun `with a TMDB key, a movie the provider can't date is found by its cleaned title`() = runTest {
        test.settings.setTmdbApiKey("key")
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "EN - Drive (2011)"}]"""
        panel.replies["get_vod_info"] = """{"info": {}}"""
        panel.replies["/3/search/movie"] = """{"results": [{"id": 64690}]}"""
        panel.replies["/3/movie/64690"] = """{"release_date": "2011-09-02"}"""

        syncAndDate()

        assertThat(panel.asked).contains("/3/search/movie:Drive")
        assertThat(db.movies().all().single().releaseDate).isEqualTo(1_314_921_600_000L)
    }

    @Test
    fun `without a TMDB key, a movie the provider can't date stays undated and TMDB isn't asked`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive"}]"""
        panel.replies["get_vod_info"] = """{"info": {}}"""

        syncAndDate()

        assertThat(panel.asked.filter { it.startsWith("/3/") }).isEmpty()
        assertThat(db.movies().all().single().releaseDate).isNull()
    }

    @Test
    fun `opening a movie while its date is being found doesn't lose the date`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive"}]"""
        panel.replies["get_vod_info"] = """{"info": {"plot": "A driver."}}"""
        syncAndDate()
        val movie = db.movies().all().single()
        val opening = panel.hold("get_vod_info:3")

        withContext(Dispatchers.Default) {
            val opened = async { test.catalog.movieDetail(movie.id) }
            during(opening) { db.movies().setReleaseDates(listOf(movie.id to 1_314_921_600_000L)) }
            opening.release()
            opened.await()
        }

        val saved = db.movies().all().single()
        assertThat(saved.plot).isEqualTo("A driver.")
        assertThat(saved.releaseDate).isEqualTo(1_314_921_600_000L)
    }

    @Test
    fun `saving rows read before a date was found keeps the date`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "100"}]"""
        syncAndDate()
        val readEarlier = db.movies().all().single()
        db.movies().setReleaseDates(listOf(readEarlier.id to 1_314_921_600_000L))

        db.movies().upsertKeepingDates(1, listOf(readEarlier.copy(addedMillis = 300)))

        val saved = db.movies().all().single()
        assertThat(saved.addedMillis).isEqualTo(300)
        assertThat(saved.releaseDate).isEqualTo(1_314_921_600_000L)
    }

    @Test
    fun `a panel that stops answering about movies doesn't hold up its shows`() = runTest {
        val movies = (1..8).joinToString { """{"stream_id": $it, "name": "M$it"}""" }
        panel.replies["get_vod_streams"] = "[$movies]"
        panel.replies["get_vod_info"] = "!"
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Show"}]"""
        panel.replies["get_series_info"] = """{"info": {"releaseDate": "2011-09-02"}}"""

        syncAndDate()

        assertThat(panel.asked.count { it.startsWith("get_vod_info") }).isEqualTo(5)
        assertThat(db.series().byProviderId(1, "7")?.latestAirDate).isEqualTo(1_314_921_600_000L)
    }

    @Test
    fun `a sync during another's lookup doesn't start a second one for that provider`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive"}]"""
        panel.replies["get_vod_info"] = """{"info": {}}"""
        val lookup = panel.hold("get_vod_info:3")

        withContext(Dispatchers.Default) {
            test.syncVod()
            during(lookup) { test.syncVod() }
            lookup.release()
        }
        test.catalog.awaitBackground()

        assertThat(panel.asked.count { it == "get_vod_info:3" }).isEqualTo(1)
    }

    @Test
    fun `the next sync after a lookup has finished starts another`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive"}]"""
        panel.replies["get_vod_info"] = """{"info": {}}"""

        syncAndDate()
        syncAndDate()

        assertThat(panel.asked.count { it == "get_vod_info:3" }).isEqualTo(2)
    }
}
