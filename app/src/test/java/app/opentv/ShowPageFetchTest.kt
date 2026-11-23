/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.Opened
import app.opentv.ui.OpenedShow
import app.opentv.ui.VodViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A show's page, opened as the page opens it, asks each Version's panel for `get_series_info` once
 * (#260). On a real clock, for Room's and the panel's own threads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShowPageFetchTest {

    private val panel = FakePanel()

    @get:Rule
    val testGraph = TestGraph(http = panel.http)

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val viewModel by lazy { testGraph.viewModel { VodViewModel(app, graph) } }

    /** A bare show as `get_series` lists it; one TMDB id makes two of them Versions of one title. */
    private fun series(id: Long, name: String) = Series(
        id = id,
        sourceId = PANEL_SOURCE.id,
        seriesId = "$id",
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        tmdbId = "94997",
    )

    private fun reply(episodeId: String) =
        """{"info": {"genre": "Drama"}, "episodes": {"1": [{"id": "$episodeId", "episode_num": 1}]}}"""

    private fun timesAsked(seriesId: Long) = panel.asked.count { it == "get_series_info:$seriesId" }

    private suspend fun episodeIds(seriesId: Long) =
        graph.database.episodes().forSeries(PANEL_SOURCE.id, "$seriesId").map { it.episodeId }

    /** Shows 1 ([first]) and 2, as Versions of one title, once the title index has grouped them. */
    private suspend fun saveTwoVersions(first: Series = series(1, "Dragons")) {
        graph.database.sources().insert(PANEL_SOURCE)
        graph.database.series().upsertAll(listOf(first, series(2, "4K - Dragons")))
        panel.replies["get_series_info:1"] = reply("101")
        panel.replies["get_series_info:2"] = reply("201")
        // Otherwise the page's look-up of Versions waits out the index's first grouping in real time.
        testGraph.firstWhenIdle(graph.titleIndex.series.filterNotNull()) { it.of(2) != null }
    }

    @Test
    fun `opening a show asks for each Version's series info once, and stores every Version's episodes`() =
        runBlocking<Unit> {
            saveTwoVersions()

            val opened = testGraph.firstWhenIdle(viewModel.openShow(1).found()) { it.fetched }

            assertThat(opened.series.genre).isEqualTo("Drama")
            assertThat(timesAsked(1)).isEqualTo(1)
            assertThat(timesAsked(2)).isEqualTo(1)
            assertThat(episodeIds(1)).containsExactly("101")
            assertThat(episodeIds(2)).containsExactly("201")
        }

    @Test
    fun `a show whose details are in opens, and its other Versions' episodes come, before its own panel answers`() {
        runBlocking { saveTwoVersions(first = series(1, "Dragons").copy(genre = "Comedy")) }
        val answer = panel.hold("get_series_info:1")
        val shown = java.util.Collections.synchronizedList(mutableListOf<OpenedShow>())
        val show = viewModel.openShow(1).found()
        val opening = testGraph.launch { show.collect { shown += it } }

        // Settled with the panel's answer held: the stored show is out, and the other Version's episodes in.
        testGraph.awaitIdle()
        assertThat(answer.reached.count).isEqualTo(0L)
        assertThat(runBlocking { episodeIds(2) }).isNotEmpty()
        assertThat(shown.single().series.genre).isEqualTo("Comedy")
        assertThat(shown.single().fetched).isFalse()
        answer.release()
        testGraph.awaitIdle()
        assertThat(opening.isCompleted).isTrue()

        assertThat(shown.last().fetched).isTrue()
        assertThat(runBlocking { episodeIds(1) }).containsExactly("101")
        assertThat(timesAsked(1)).isEqualTo(1)
        assertThat(timesAsked(2)).isEqualTo(1)
    }

    @Test
    fun `another Version's episodes are stored before TMDB answers about the show`() {
        graph.tmdbKey = TMDB_KEY
        runBlocking { saveTwoVersions() }
        // By its id, which these shows already have, rather than a search.
        val tmdb = panel.hold("/3/tv/94997")
        val show = viewModel.openShow(1)
        val opening = testGraph.launch { show.collect {} }

        testGraph.awaitIdle()
        assertThat(tmdb.reached.count).isEqualTo(0L)
        assertThat(runBlocking { episodeIds(2) }).isNotEmpty()
        tmdb.release()
        testGraph.awaitIdle()
        assertThat(opening.isCompleted).isTrue()

        assertThat(runBlocking { episodeIds(2) }).containsExactly("201")
        assertThat(timesAsked(2)).isEqualTo(1)
    }

    @Test
    fun `a show whose every Version's fetch times out says so`() = runBlocking<Unit> {
        saveTwoVersions()
        panel.replies["get_series_info:1"] = "!timeout"
        panel.replies["get_series_info:2"] = "!timeout"

        val opened = testGraph.firstWhenIdle(viewModel.openShow(1).found()) { it.fetched }

        assertThat(opened.noEpisodes).isTrue()
        assertThat(opened.failure?.sentence).isEqualTo(app.getString(R.string.error_timed_out))
    }

    @Test
    fun `a show with one Version that fails and one that answers reports no failure`() = runBlocking<Unit> {
        saveTwoVersions()
        panel.replies["get_series_info:1"] = "!503"

        val opened = testGraph.firstWhenIdle(viewModel.openShow(1).found()) { it.fetched }

        assertThat(opened.failure).isNull()
        assertThat(episodeIds(2)).containsExactly("201")
    }

    @Test
    fun `a show whose panel answers with no episodes hasn't failed`() = runBlocking<Unit> {
        graph.database.sources().insert(PANEL_SOURCE)
        graph.database.series().upsertAll(listOf(series(1, "Dragons").copy(tmdbId = null)))
        panel.replies["get_series_info:1"] = """{"episodes": {}}"""

        val opened = testGraph.firstWhenIdle(viewModel.openShow(1).found()) { it.fetched }

        assertThat(opened.noEpisodes).isTrue()
        assertThat(opened.failure).isNull()
    }

    @Test
    fun `a show on a playlist, with nothing to fetch, never reports a failure`() = runBlocking<Unit> {
        val playlist = Source(id = 3, name = "Playlist", kind = SourceKind.M3U, url = "http://example.com/list.m3u")
        graph.database.sources().insert(playlist)
        graph.database.series().upsertAll(listOf(series(1, "Dragons").copy(sourceId = playlist.id, tmdbId = null)))

        val opened = testGraph.firstWhenIdle(viewModel.openShow(1).found()) { it.fetched }

        assertThat(opened.failure).isNull()
    }

    @Test
    fun `a show with episodes stored reports no failure when its panel fails, so the page shows them`() =
        runBlocking<Unit> {
            graph.database.sources().insert(PANEL_SOURCE)
            graph.database.series().upsertAll(listOf(series(1, "Dragons").copy(tmdbId = null)))
            val stored = testEpisode(1, 1).copy(sourceId = PANEL_SOURCE.id, seriesId = "1")
            graph.database.episodes().upsertAll(listOf(stored))
            panel.replies["get_series_info:1"] = "!timeout"

            val opened = testGraph.firstWhenIdle(viewModel.openShow(1).found()) { it.fetched }

            assertThat(opened.noEpisodes).isFalse()
            assertThat(opened.failure).isNull()
        }

    /** The show as each opening gives it, as these shows are all stored. */
    private fun Flow<Opened<OpenedShow>>.found() = mapNotNull { (it as? Opened.Found)?.title }
}
