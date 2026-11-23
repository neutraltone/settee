/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Series
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.MediaKey
import app.opentv.ui.ResumeItem
import app.opentv.ui.SeasonEpisode
import app.opentv.ui.VodViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application: OpenTvApp schedules WorkManager on start, which the view model doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodContinueWatchingTest {

    @get:Rule
    val testGraph = TestGraph()

    private val app = testGraph.app
    private val graph = testGraph.graph

    private fun viewModel() = testGraph.track(VodViewModel(app, graph))

    private val show = Series(
        sourceId = 1,
        seriesId = "s",
        name = "The Show",
        categoryId = null,
        posterUrl = "https://example.com/poster.jpg",
        rating = null,
        year = null,
        plot = null,
        backdropUrl = "https://example.com/backdrop.jpg",
    )

    @Test
    fun `a series card shows the series and its artwork, and plays under the episode's title`(): Unit = runBlocking {
        graph.database.series().upsertAll(listOf(show))
        graph.database.episodes().upsertAll(listOf(testEpisode(2, 4)))
        graph.viewerLibrary.recordProgress("ep:24", positionMillis = 600_000, durationMillis = 2_400_000)

        val cards = testGraph.firstWhenIdle(viewModel().continueWatchingSeries) { !it.isNullOrEmpty() }

        assertThat(cards).containsExactly(
            ResumeItem(
                key = MediaKey.Episode(24),
                title = "The Show",
                posterUrl = "https://example.com/backdrop.jpg",
                progress = 0.25f,
                episode = SeasonEpisode(season = 2, episode = 4),
                card = ContinueWatchingCard.Series(testEpisode(2, 4), progress = 0.25f),
                // So the backdrop on Shows can look the show up, as it does for a show's cover.
                titleId = graph.database.series().bySeriesId(1, "s")?.id,
                titleKey = "series:1:s",
            ),
        )
    }
}
