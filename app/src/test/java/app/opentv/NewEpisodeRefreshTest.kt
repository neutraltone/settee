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

    init {
        // A refresh only reaches movies and shows once live TV has loaded.
        panel.replies[""] = """{"user_info": {"status": "Active"}}"""
        panel.replies["get_live_streams"] = """[{"stream_id": 1, "name": "BBC One"}]"""
    }

    @After
    fun close() = test.close()

    /** `get_series_info` with [episodes] of season 1, each `"<id>" to "<title>"`. */
    private fun seriesInfo(vararg episodes: Pair<String, String>) = """
        {"info": {}, "episodes": {"1": [${
        episodes.withIndex().joinToString { (i, ep) ->
            """{"id": "${ep.first}", "episode_num": ${i + 1}, "title": "${ep.second}"}"""
        }
    }]}}
    """.trimIndent()

    private fun listShow(id: Int, lastModified: Int) {
        panel.replies["get_series"] = """[{"series_id": $id, "name": "Show", "last_modified": "$lastModified"}]"""
    }

    private suspend fun refresh() = test.catalog.sync(PANEL_SOURCE, now)

    @Test
    fun `fetching a show's episodes again updates them but keeps when each was first seen`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies["get_series_info"] = seriesInfo("1" to "Pilot")
        test.catalog.ensureEpisodes(PANEL_SOURCE, "7", now)
        val pilot = db.episodes().forSeries(1, "7").single()

        now += 60_000
        panel.replies["get_series_info"] = seriesInfo("1" to "Pilot (Remastered)", "2" to "Second")
        test.catalog.ensureEpisodes(PANEL_SOURCE, "7", now)

        val stored = db.episodes().forSeries(1, "7").sortedBy { it.episodeNumber }
        assertThat(stored.map { Triple(it.id, it.title, it.firstSeenMillis) }).containsExactly(
            Triple(pilot.id, "Pilot (Remastered)", 1_000_000L),
            Triple(stored[1].id, "Second", 1_060_000L),
        ).inOrder()
    }

    /** Show [id]'s pilot, Watched; the provider has since added a second episode. */
    private suspend fun caughtUpOnPilot(id: String) {
        db.sources().insert(PANEL_SOURCE)
        panel.replies["get_series_info:$id"] = seriesInfo("$id-1" to "Pilot")
        test.catalog.ensureEpisodes(PANEL_SOURCE, id, now)
        val pilot = db.episodes().forSeries(1, id).single()
        test.viewerLibrary.recordProgress("ep:${pilot.id}", positionMillis = 1_000, durationMillis = 1_000)
        now += 60_000
        panel.replies["get_series_info:$id"] = seriesInfo("$id-1" to "Pilot", "$id-2" to "Second")
    }

    @Test
    fun `a refresh where the provider updated a show the Profile is caught up on brings its New Episode`() = runTest {
        listShow(7, lastModified = 100)
        refresh()
        caughtUpOnPilot("7")

        listShow(7, lastModified = 200)
        refresh()

        val card = test.viewerLibrary.continueWatchingSeries().first().single() as ContinueWatchingCard.Series
        assertThat(card.episode.title).isEqualTo("Second")
        assertThat(card.newEpisode).isTrue()
    }

    @Test
    fun `a refresh where the provider didn't update the show fetches nothing`() = runTest {
        listShow(7, lastModified = 100)
        refresh()
        caughtUpOnPilot("7")

        refresh()

        assertThat(db.episodes().forSeries(1, "7")).hasSize(1)
    }

    @Test
    fun `a show listed for the first time is not fetched, even with episodes already stored`() = runTest {
        caughtUpOnPilot("7")

        listShow(7, lastModified = 200)
        refresh()

        assertThat(db.episodes().forSeries(1, "7")).hasSize(1)
    }
}
