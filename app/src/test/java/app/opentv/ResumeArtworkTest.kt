/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.ui.resumeItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The picture on a Continue Watching card: a frame from the title where the provider has one. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ResumeArtworkTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val db = test.db

    @After
    fun close() = test.close()

    private fun movie(backdrop: String?) = Movie(
        sourceId = 1,
        streamId = "m",
        name = "Heat",
        categoryId = null,
        posterUrl = "https://example.com/poster.jpg",
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "",
        backdropUrl = backdrop,
    )

    private val show = Series(
        sourceId = 1,
        seriesId = "s",
        name = "Severance",
        categoryId = null,
        posterUrl = "https://example.com/show-poster.jpg",
        rating = null,
        year = null,
        plot = null,
        backdropUrl = "https://example.com/show-backdrop.jpg",
    )

    private suspend fun storedMovie(backdrop: String?): Movie {
        db.movies().upsertAll(listOf(movie(backdrop)))
        return db.movies().all().single()
    }

    @Test
    fun `a movie's card shows its wide backdrop, not its portrait cover`() = runTest {
        val heat = storedMovie(backdrop = "https://example.com/backdrop.jpg")

        val card = resumeItem(test.catalog, ContinueWatchingCard.Movie(heat.id, 0.4f))

        assertThat(card!!.posterUrl).isEqualTo("https://example.com/backdrop.jpg")
    }

    @Test
    fun `a movie with no backdrop falls back to its cover`() = runTest {
        val heat = storedMovie(backdrop = null)

        val card = resumeItem(test.catalog, ContinueWatchingCard.Movie(heat.id, 0.4f))

        assertThat(card!!.posterUrl).isEqualTo("https://example.com/poster.jpg")
    }

    @Test
    fun `an episode's card shows the episode's own still, or the show's backdrop without one`() = runTest {
        db.series().upsertAll(listOf(show))
        val withStill = testEpisode(1, 2).copy(stillUrl = "https://example.com/still.jpg")
        val without = testEpisode(1, 3)
        db.episodes().upsertAll(listOf(withStill, without))

        val stillCard = resumeItem(test.catalog, ContinueWatchingCard.Series(withStill, 0.5f))
        val backdropCard = resumeItem(test.catalog, ContinueWatchingCard.Series(without, null))

        assertThat(stillCard!!.posterUrl).isEqualTo("https://example.com/still.jpg")
        assertThat(backdropCard!!.posterUrl).isEqualTo("https://example.com/show-backdrop.jpg")
    }
}
