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
import app.opentv.ui.resumeItems
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which title a Continue Watching card stands for, which Home finds the card again by. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ResumeCardIdentityTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val db = test.db

    @After
    fun close() = test.close()

    private fun show(sourceId: Long) = Series(
        sourceId = sourceId,
        seriesId = "s",
        name = "Severance",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
    )

    private fun heat(id: Long) = Movie(
        id = id,
        sourceId = 1,
        streamId = "m",
        name = "Heat",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "",
    )

    @Test
    fun `a show's card stands for the same title whichever episode it has moved on to`() = runTest {
        db.series().upsertAll(listOf(show(sourceId = 1)))
        val third = testEpisode(1, 3)
        val fourth = testEpisode(1, 4)
        db.episodes().upsertAll(listOf(third, fourth))

        val before = resumeItem(test.catalog, ContinueWatchingCard.Series(third, 0.95f))!!
        val after = resumeItem(test.catalog, ContinueWatchingCard.Series(fourth, null))!!

        assertThat(after.titleKey).isEqualTo(before.titleKey)
        // Still the episode the card plays.
        assertThat(after.key).isNotEqualTo(before.key)
    }

    @Test
    fun `two shows with the same series id from different sources are different titles`() = runTest {
        db.series().upsertAll(listOf(show(sourceId = 1), show(sourceId = 2)))
        val fromFirst = testEpisode(1, 3)
        val fromSecond = testEpisode(1, 3).copy(id = 99, sourceId = 2)
        db.episodes().upsertAll(listOf(fromFirst, fromSecond))

        val first = resumeItem(test.catalog, ContinueWatchingCard.Series(fromFirst, 0.5f))!!
        val second = resumeItem(test.catalog, ContinueWatchingCard.Series(fromSecond, 0.5f))!!

        assertThat(second.titleKey).isNotEqualTo(first.titleKey)
    }

    @Test
    fun `a film's card stands for the same title after a refresh gives the film a new local id`() = runTest {
        db.movies().upsertAll(listOf(heat(id = 100)))
        val before = resumeItem(test.catalog, ContinueWatchingCard.Movie(100, 0.4f))!!
        db.movies().deleteForSource(1)
        db.movies().upsertAll(listOf(heat(id = 200)))

        val after = resumeItem(test.catalog, ContinueWatchingCard.Movie(200, 0.4f))!!

        assertThat(after.titleKey).isEqualTo(before.titleKey)
    }

    @Test
    fun `handed two cards for one title, Continue Watching draws only the first`() = runTest {
        db.movies().upsertAll(listOf(heat(id = 100)))

        val cards = resumeItems(
            test.catalog,
            listOf(ContinueWatchingCard.Movie(100, 0.4f), ContinueWatchingCard.Movie(100, 0.2f)),
        )

        assertThat(cards.map { it.progress }).containsExactly(0.4f)
    }
}
