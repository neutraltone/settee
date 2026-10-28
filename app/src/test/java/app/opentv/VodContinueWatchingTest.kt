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
import app.opentv.ui.VodViewModel
import app.opentv.ui.VodViewModel.ResumeItem
import app.opentv.ui.VodViewModel.SeasonEpisode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application: OpenTvApp schedules WorkManager on start, which the view model doesn't need.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodContinueWatchingTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun close() {
        graph.database.close()
        Dispatchers.resetMain()
    }

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
    fun `a series card shows the series and its artwork, and plays under the episode's title`() = runTest {
        graph.database.series().upsertAll(listOf(show))
        graph.database.episodes().upsertAll(listOf(testEpisode(2, 4)))
        graph.viewerLibrary.recordProgress("ep:24", positionMillis = 600_000, durationMillis = 2_400_000)

        val cards = VodViewModel(app, graph).continueWatchingSeries.first { it.isNotEmpty() }

        assertThat(cards).containsExactly(
            ResumeItem(
                mediaKey = "ep:24",
                title = "The Show",
                playerTitle = "S2E4 · Episode 4",
                posterUrl = "https://example.com/backdrop.jpg",
                streamUrl = "https://example.com/2-4.mkv",
                progress = 0.25f,
                episode = SeasonEpisode(season = 2, episode = 4),
            ),
        )
    }
}
