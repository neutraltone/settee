/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Series
import app.opentv.ui.OpenedShow
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A show's page, opened as the page opens it, asks each Version's panel for `get_series_info` once
 * (#260). On a real clock, for Room's and the panel's own threads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShowPageFetchTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val panel = FakePanel()
    private val graph = ServiceLocator.Graph(app, http = panel.http)

    // Lazily, so that it's made on the main dispatcher set below.
    private val viewModels = TestViewModels()
    private val viewModel by lazy { viewModels.vod(app, graph) }

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun close() {
        graph.settings.setTmdbApiKey("")
        viewModels.clear()
        graph.database.close()
        Dispatchers.resetMain()
    }

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
        withTimeout(10_000) { graph.titleIndex.series.filterNotNull().first { it.of(2) != null } }
    }

    @Test
    fun `opening a show asks for each Version's series info once, and stores every Version's episodes`() =
        runBlocking<Unit> {
            saveTwoVersions()

            val opened = withTimeout(10_000) { viewModel.openShow(1).first { it.fetched } }

            assertThat(opened.series.genre).isEqualTo("Drama")
            assertThat(timesAsked(1)).isEqualTo(1)
            assertThat(timesAsked(2)).isEqualTo(1)
            assertThat(episodeIds(1)).containsExactly("101")
            assertThat(episodeIds(2)).containsExactly("201")
        }

    @Test
    fun `a show whose details are in opens, and its other Versions' episodes come, before its own panel answers`() =
        runBlocking<Unit> {
            saveTwoVersions(first = series(1, "Dragons").copy(genre = "Comedy"))
            val answer = panel.hold("get_series_info:1")
            val shown = java.util.Collections.synchronizedList(mutableListOf<OpenedShow>())
            val show = viewModel.openShow(1)
            val opening = launch(Dispatchers.Default) { show.collect { shown += it } }

            during(answer) {
                withTimeout(10_000) { while (shown.isEmpty() || episodeIds(2).isEmpty()) delay(20) }
                assertThat(shown.single().series.genre).isEqualTo("Comedy")
                assertThat(shown.single().fetched).isFalse()
            }
            answer.release()
            opening.join()

            assertThat(shown.last().fetched).isTrue()
            assertThat(episodeIds(1)).containsExactly("101")
            assertThat(timesAsked(1)).isEqualTo(1)
            assertThat(timesAsked(2)).isEqualTo(1)
        }

    @Test
    fun `another Version's episodes are stored before TMDB answers about the show`() = runBlocking<Unit> {
        saveTwoVersions()
        graph.settings.setTmdbApiKey("k")
        // By its id, which these shows already have, rather than a search.
        val tmdb = panel.hold("/3/tv/94997")
        val show = viewModel.openShow(1)
        val opening = launch(Dispatchers.Default) { show.collect {} }

        during(tmdb) { withTimeout(10_000) { while (episodeIds(2).isEmpty()) delay(20) } }
        tmdb.release()
        opening.join()

        assertThat(episodeIds(2)).containsExactly("201")
        assertThat(timesAsked(2)).isEqualTo(1)
    }
}
