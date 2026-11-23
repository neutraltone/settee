/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.WatchStatus
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Films, shows and episodes the provider stops listing are deleted, and a resume point on one moves to a
 * Version of the same title that's left (#351).
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RetiredTitlesTest {

    private var now = 1_000L
    private val test = TestCatalog(ApplicationProvider.getApplicationContext(), clock = { now })
    private val panel = test.panel
    private val library = test.viewerLibrary

    @After
    fun close() = test.close()

    @Test
    fun `a film losing the Version with a resume point resumes on the Version listed last`() = testing {
        panel.replies["get_vod_streams"] = films(1 to "550", 2 to "550", 3 to "550")
        test.syncVod()
        library.recordProgress(movie(2), positionMillis = 600_000, durationMillis = 6_000_000)

        panel.replies["get_vod_streams"] = films(1 to "550", 3 to "550")
        test.syncVod()

        assertThat(library.resumeFrom(movie(3))).isEqualTo(600_000L)
        assertThat(library.marks.statusOf(movie(3))).isInstanceOf(WatchStatus.InProgress::class.java)
        assertThat(library.marks.statusOf(movie(1))).isEqualTo(WatchStatus.Unwatched)
        assertThat(everyPosition()).hasSize(1)
    }

    @Test
    fun `a film marked Watched stays Watched when the Version it was marked on is removed`() = testing {
        panel.replies["get_vod_streams"] = films(1 to "550", 2 to "550")
        test.syncVod()
        library.marks.markWatched(movie(1))

        panel.replies["get_vod_streams"] = films(2 to "550")
        test.syncVod()

        assertThat(library.marks.statusOf(movie(2))).isEqualTo(WatchStatus.Watched)
    }

    @Test
    fun `a film losing every Version loses its resume point`() = testing {
        panel.replies["get_vod_streams"] = films(1 to "550", 2 to "551")
        test.syncVod()
        library.recordProgress(movie(1), positionMillis = 600_000, durationMillis = 6_000_000)

        panel.replies["get_vod_streams"] = films(2 to "551")
        test.syncVod()

        assertThat(everyPosition()).isEmpty()
    }

    @Test
    fun `a resume point goes to the Version already played, and the newer of the two points is kept`() = testing {
        panel.replies["get_vod_streams"] = films(1 to "550", 2 to "550", 3 to "550")
        test.syncVod()
        now = 1_000L
        library.recordProgress(movie(2), positionMillis = 300_000, durationMillis = 6_000_000)
        now = 2_000L
        library.recordProgress(movie(1), positionMillis = 900_000, durationMillis = 6_000_000)

        panel.replies["get_vod_streams"] = films(2 to "550", 3 to "550")
        test.syncVod()

        assertThat(library.resumeFrom(movie(2))).isEqualTo(900_000L)
        assertThat(library.marks.statusOf(movie(3))).isEqualTo(WatchStatus.Unwatched)
        assertThat(everyPosition()).hasSize(1)
    }

    @Test
    fun `a newer resume point already on the Version left is kept over the one moving to it`() = testing {
        panel.replies["get_vod_streams"] = films(1 to "550", 2 to "550")
        test.syncVod()
        now = 1_000L
        library.recordProgress(movie(1), positionMillis = 900_000, durationMillis = 6_000_000)
        now = 2_000L
        library.recordProgress(movie(2), positionMillis = 300_000, durationMillis = 6_000_000)

        panel.replies["get_vod_streams"] = films(2 to "550")
        test.syncVod()

        assertThat(library.resumeFrom(movie(2))).isEqualTo(300_000L)
        assertThat(everyPosition()).hasSize(1)
    }

    @Test
    fun `a film listed again under a new id without a TMDB id keeps its resume point`() = testing {
        panel.replies["get_vod_streams"] =
            """[{"stream_id": 1, "name": "EN - Brief Encounter (1945)", "added": "100"}]"""
        test.syncVod()
        library.recordProgress(movie(1), positionMillis = 600_000, durationMillis = 6_000_000)

        panel.replies["get_vod_streams"] =
            """[{"stream_id": 9, "name": "EN - Brief Encounter (1945)", "added": "100"}]"""
        test.syncVod()

        assertThat(library.resumeFrom(movie(9))).isEqualTo(600_000L)
        assertThat(everyPosition()).hasSize(1)
    }

    @Test
    fun `an episode dropped from a show follows to another Version whose episodes weren't fetched yet`() = testing {
        panel.replies["get_series"] = shows(7 to "1399", 8 to "1399")
        panel.replies["get_series_info:7"] = episodes(1 to 1, 2 to 2)
        panel.replies["get_series_info:8"] = episodes(21 to 1, 22 to 2)
        test.syncVod()
        test.catalog.ensureEpisodes(PANEL_SOURCE, "7")
        library.recordProgress(episode("7", "2"), positionMillis = 600_000, durationMillis = 2_400_000)

        panel.replies["get_series_info:7"] = episodes(1 to 1)
        test.catalog.ensureEpisodes(PANEL_SOURCE, "7")

        assertThat(library.resumeFrom(episode("8", "22"))).isEqualTo(600_000L)
        assertThat(everyPosition()).hasSize(1)
    }

    @Test
    fun `an episode reply that no longer lists an episode deletes it`() = testing {
        showSeven()
        panel.replies["get_series_info:7"] = episodes(1 to 1, 2 to 2)
        test.catalog.ensureEpisodes(PANEL_SOURCE, "7")

        panel.replies["get_series_info:7"] = episodes(1 to 1)
        test.catalog.ensureEpisodes(PANEL_SOURCE, "7")

        assertThat(episodesOf("7").map { it.episodeId }).containsExactly("1")
    }

    @Test
    fun `an episode reply that fails or lists nothing deletes no episodes`() = testing {
        showSeven()
        for (reply in listOf("!", """{"info": {}, "episodes": {}}""")) {
            panel.replies["get_series_info:7"] = episodes(1 to 1, 2 to 2)
            test.catalog.ensureEpisodes(PANEL_SOURCE, "7")

            panel.replies["get_series_info:7"] = reply
            test.catalog.ensureEpisodes(PANEL_SOURCE, "7")

            assertWithMessage(reply).that(episodesOf("7").map { it.episodeId }).containsExactly("1", "2")
        }
    }

    @Test
    fun `an episode uploaded again under a new id is one row, and its resume point follows it`() = testing {
        showSeven()
        panel.replies["get_series_info:7"] = episodes(1 to 1, 2 to 2)
        test.catalog.ensureEpisodes(PANEL_SOURCE, "7")
        library.recordProgress(episode("7", "2"), positionMillis = 600_000, durationMillis = 2_400_000)

        panel.replies["get_series_info:7"] = episodes(1 to 1, 5 to 2)
        test.catalog.ensureEpisodes(PANEL_SOURCE, "7")

        assertThat(episodesOf("7").map { it.episodeId }).containsExactly("1", "5")
        assertThat(library.resumeFrom(episode("7", "5"))).isEqualTo(600_000L)
        assertThat(everyPosition()).hasSize(1)
    }

    @Test
    fun `a removed show's resume point follows its episode to another Version of the show`() = testing {
        panel.replies["get_series"] = shows(7 to "1399", 8 to "1399")
        panel.replies["get_series_info:7"] = episodes(1 to 1)
        panel.replies["get_series_info:8"] = episodes(21 to 1)
        test.syncVod()
        test.catalog.ensureEpisodes(PANEL_SOURCE, "7")
        library.recordProgress(episode("7", "1"), positionMillis = 600_000, durationMillis = 2_400_000)

        panel.replies["get_series"] = shows(8 to "1399")
        test.syncVod()

        assertThat(test.catalog.allSeries().map { it.seriesId }).containsExactly("8")
        assertThat(episodesOf("7")).isEmpty()
        assertThat(library.resumeFrom(episode("8", "21"))).isEqualTo(600_000L)
        assertThat(everyPosition()).hasSize(1)
    }

    @Test
    fun `a removed show with no Version left takes its episodes and their resume points with it`() = testing {
        panel.replies["get_series"] = shows(7 to "1399", 8 to "1400")
        panel.replies["get_series_info:7"] = episodes(1 to 1)
        test.syncVod()
        test.catalog.ensureEpisodes(PANEL_SOURCE, "7")
        library.recordProgress(episode("7", "1"), positionMillis = 600_000, durationMillis = 2_400_000)

        panel.replies["get_series"] = shows(8 to "1400")
        test.syncVod()

        assertThat(episodesOf("7")).isEmpty()
        assertThat(everyPosition()).isEmpty()
    }

    private suspend fun showSeven() {
        panel.replies["get_series"] = shows(7 to "1399")
        test.syncVod()
    }

    private suspend fun movie(streamId: Int): String =
        "movie:" + test.catalog.allMovies().single { it.streamId == "$streamId" }.id

    private suspend fun episodesOf(seriesId: String) = test.catalog.observeEpisodes(1, seriesId).first()

    private suspend fun episode(seriesId: String, episodeId: String): String =
        "ep:" + episodesOf(seriesId).single { it.episodeId == episodeId }.id

    /** Every Profile's resume points, the one read past the library: none may be left on a deleted title. */
    private suspend fun everyPosition() = test.db.positions().all()

    /** A film list of (stream id, TMDB id) pairs. */
    private fun films(vararg films: Pair<Int, String>) = films.joinToString(",", "[", "]") { (id, tmdb) ->
        """{"stream_id": $id, "name": "Film $id", "added": "100", "tmdb": "$tmdb"}"""
    }

    /** A show list of (series id, TMDB id) pairs. */
    private fun shows(vararg shows: Pair<Int, String>) = shows.joinToString(",", "[", "]") { (id, tmdb) ->
        """{"series_id": $id, "name": "Show $id", "last_modified": "100", "tmdb": "$tmdb"}"""
    }

    /** A `get_series_info` reply of season 1's (episode id, episode number) pairs. */
    private fun episodes(vararg episodes: Pair<Int, Int>): String {
        val season = episodes.joinToString(",", "[", "]") { (id, number) ->
            """{"id": "$id", "episode_num": $number, "title": "Episode $number"}"""
        }
        return """{"info": {}, "episodes": {"1": $season}}"""
    }
}
