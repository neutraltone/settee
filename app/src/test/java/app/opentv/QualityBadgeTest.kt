/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.MediaKey
import app.opentv.ui.ResumeItem
import app.opentv.ui.vod.ContinueWatchingRow
import app.opentv.ui.vod.MoviePosterRow
import app.opentv.ui.vod.SeriesPosterRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A title's Quality shows on its card wherever it appears, not only in a category's grid (#180). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class QualityBadgeTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private fun movie(id: Long, name: String) = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = null,
        posterUrl = "https://example.com/$id.png",
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/$id",
    )

    private fun series(id: Long, name: String) = Series(
        id = id,
        sourceId = 1,
        seriesId = "$id",
        name = name,
        categoryId = null,
        posterUrl = "https://example.com/$id.png",
        rating = null,
        year = null,
        plot = null,
    )

    @Test
    fun `shelves and Continue Watching show a 4K title's badge, and none on a title with no tag`() {
        compose.setTvContent {
            Column {
                ContinueWatchingRow(
                    listOf(
                        ResumeItem(
                            MediaKey.Movie(5),
                            "Dune",
                            "https://example.com/5.png",
                            0.4f,
                            name = "4K-EN - Dune",
                        ),
                    ),
                    onResume = {},
                    onOptions = null,
                )
                MoviePosterRow("Recently added", listOf(movie(1, "EN - Superbad")), onOpenMovie = {})
                SeriesPosterRow("Recently added", listOf(series(2, "EN - Dragons")), onOpenSeries = {})
            }
        }

        compose.onNodeWithText("4K").assertExists()
    }

    @Test
    fun `a movie shelf and a show shelf each show their titles' badges`() {
        compose.setTvContent {
            Column {
                MoviePosterRow("Movies", listOf(movie(1, "4K-TOP - Superbad")), onOpenMovie = {})
                SeriesPosterRow("Shows", listOf(series(2, "EN - Dragons FHD")), onOpenSeries = {})
            }
        }

        compose.onNodeWithText("4K").assertExists()
        compose.onNodeWithText("FHD").assertExists()
        captureScreen("shelves_quality_badges")
    }
}
