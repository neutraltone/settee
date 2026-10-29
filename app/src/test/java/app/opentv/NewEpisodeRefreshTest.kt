/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.ContinueWatchingCard
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NewEpisodeRefreshTest {

    private var now = 1_000_000L
    private val test = TestCatalog(ApplicationProvider.getApplicationContext(), clock = { now })
    private val db = test.db
    private val panel = test.panel

    @After
    fun close() = db.close()

    /** `get_series_info` for show 7 with [episodes] of season 1, each `"<id>" to "<title>"`. */
    private fun seriesInfo(vararg episodes: Pair<String, String>) = """
        {"info": {}, "episodes": {"1": [${
        episodes.withIndex().joinToString { (i, ep) ->
            """{"id": "${ep.first}", "episode_num": ${i + 1}, "title": "${ep.second}"}"""
        }
    }]}}
    """.trimIndent()

    @Test
    fun `fetching a show's episodes again updates them but keeps when each was first seen`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies["get_series_info"] = seriesInfo("1" to "Pilot")
        test.catalog.ensureEpisodes(PANEL_SOURCE, "7")
        val pilot = db.episodes().forSeries(1, "7").single()

        now += 60_000
        panel.replies["get_series_info"] = seriesInfo("1" to "Pilot (Remastered)", "2" to "Second")
        test.catalog.ensureEpisodes(PANEL_SOURCE, "7")

        val stored = db.episodes().forSeries(1, "7").sortedBy { it.episodeNumber }
        assertThat(stored.map { Triple(it.id, it.title, it.firstSeenMillis) }).containsExactly(
            Triple(pilot.id, "Pilot (Remastered)", 1_000_000L),
            Triple(stored[1].id, "Second", 1_060_000L),
        ).inOrder()
    }

    /** Show 7 with only its pilot, whose finale the Profile has just Watched. */
    private suspend fun caughtUpOnPilot() {
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Show", "last_modified": "100"}]"""
        panel.replies["get_series_info"] = seriesInfo("1" to "Pilot")
        test.syncVod()
        test.settle()
        test.catalog.ensureEpisodes(PANEL_SOURCE, "7")
        val pilot = db.episodes().forSeries(1, "7").single()
        test.viewerLibrary.recordProgress("ep:${pilot.id}", positionMillis = 1_000, durationMillis = 1_000)
        now += 60_000
        panel.replies["get_series_info"] = seriesInfo("1" to "Pilot", "2" to "Second")
    }

    @Test
    fun `a refresh where the provider updated a show the Profile is caught up on brings its New Episode`() = runTest {
        caughtUpOnPilot()

        panel.replies["get_series"] = """[{"series_id": 7, "name": "Show", "last_modified": "200"}]"""
        test.syncVod()
        test.settle()

        val card = test.viewerLibrary.continueWatchingSeries().first().single() as ContinueWatchingCard.Series
        assertThat(card.episode.title).isEqualTo("Second")
        assertThat(card.newEpisode).isTrue()
    }

    @Test
    fun `a refresh where the provider didn't update the show fetches nothing`() = runTest {
        caughtUpOnPilot()

        test.syncVod()
        test.settle()

        assertThat(db.episodes().forSeries(1, "7")).hasSize(1)
    }
}
