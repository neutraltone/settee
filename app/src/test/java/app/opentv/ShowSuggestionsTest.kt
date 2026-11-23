/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Series
import app.opentv.ui.VodViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The covers a show's page draws under More like this: one per title, as Versions are grouped (ADR 0007),
 * even for shows TMDB doesn't know. On a real clock, for the title index's own thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShowSuggestionsTest {

    @get:Rule
    val testGraph = TestGraph()

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val viewModel by lazy { testGraph.viewModel { VodViewModel(app, graph) } }

    private fun show(id: Long, name: String) = Series(
        id = id,
        sourceId = 1,
        seriesId = "$id",
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 2019,
        plot = null,
        genre = "Drama",
    )

    @Test
    fun `a show's suggestions are one cover per title, and never another Version of the show`() = runBlocking<Unit> {
        graph.database.series().upsertAll(
            listOf(
                show(1, "Dragons"),
                show(2, "4K - Dragons"),
                show(3, "Harbour"),
                show(4, "4K - Harbour"),
            ),
        )
        testGraph.firstWhenIdle(graph.titleIndex.series.filterNotNull()) { it.of(4) != null }

        val covers = viewModel.moreLikeThis(graph.database.series().byIds(listOf(1L)).single())

        assertThat(covers.map { it.title }).containsExactly("Harbour")
    }
}
