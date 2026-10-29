/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Episode
import app.opentv.data.model.Series
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.ViewerLibrary
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
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
class NewEpisodeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val activeProfile = MutableStateFlow(1L)
    private var now = 1_000_000L

    /** What the provider has added since the last fetch, by series id; fetching stores it as now. */
    private val added = mutableMapOf<String, List<Episode>>()
    private val fetched = mutableListOf<String>()
    private val library = ViewerLibrary(
        db.positions(),
        db.episodes(),
        db.favourites(),
        db.seriesDismissals(),
        activeProfile,
        clock = { now },
    )

    /** A fake provider: fetching a series stores what it has added since, first seen now. */
    private suspend fun seriesUpdated(vararg updated: Series) =
        library.newEpisodes.seriesUpdated(updated.toList()) { series ->
            fetched += series.seriesId
            check(series.seriesId != "broken") { "Panel timed out" }
            db.episodes().upsertAll(added[series.seriesId].orEmpty().map { it.copy(firstSeenMillis = now) })
        }

    @After
    fun close() = db.close()

    /** Two seasons of three. */
    private val episodes = listOf(1, 2).flatMap { season ->
        (1..3).map { number -> testEpisode(season, number) }
    }

    private val series = Series(
        sourceId = 1,
        seriesId = "s",
        name = "Show",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
    )

    private suspend fun watch(ep: Episode) =
        library.recordProgress("ep:${ep.id}", positionMillis = 2_400_000, durationMillis = 2_400_000)

    @Test
    fun `a new season after the finale is fetched and comes back as a New Episode`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(2, 3))
        added["s"] = listOf(testEpisode(3, 1))
        now += 60_000

        seriesUpdated(series)

        assertThat(library.continueWatchingSeries().first()).containsExactly(
            ContinueWatchingCard.Series(
                testEpisode(3, 1).copy(firstSeenMillis = now),
                progress = null,
                newEpisode = true,
            ),
        )
    }

    @Test
    fun `a series the Profile is part way through is not fetched`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 2))

        seriesUpdated(series)

        assertThat(fetched).isEmpty()
    }

    @Test
    fun `a series nobody has watched is not fetched`() = runTest {
        db.episodes().upsertAll(episodes)

        seriesUpdated(series)

        assertThat(fetched).isEmpty()
    }

    @Test
    fun `a series only another Profile is caught up on is still fetched`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 2))
        activeProfile.value = 2L
        watch(testEpisode(2, 3))
        activeProfile.value = 1L

        seriesUpdated(series)

        assertThat(fetched).containsExactly("s")
    }

    @Test
    fun `one series failing to fetch doesn't stop the others`() = runTest {
        val broken = testEpisode(1, 1).copy(id = 101, seriesId = "broken", episodeId = "b-1-1")
        db.episodes().upsertAll(episodes + broken)
        watch(broken)
        watch(testEpisode(2, 3))

        seriesUpdated(series.copy(seriesId = "broken"), series)

        assertThat(fetched).containsExactly("broken", "s").inOrder()
    }
}
