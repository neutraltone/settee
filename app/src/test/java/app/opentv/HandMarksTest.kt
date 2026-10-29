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
import app.opentv.data.model.HandMark
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.SeriesDismissal
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.SeriesMainAction
import app.opentv.data.repo.ViewerLibrary
import app.opentv.data.repo.WatchStatus
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
class HandMarksTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val activeProfile = MutableStateFlow(1L)
    private var now = 1_000_000L
    private val library =
        ViewerLibrary(db.positions(), db.episodes(), db.favourites(), db.seriesDismissals(), activeProfile, clock = {
            now
        })

    @After
    fun close() = db.close()

    private suspend fun statusOf(ep: Episode): WatchStatus = library.episodeStatuses().first()[ep.id]

    @Test
    fun `an episode never played can be marked Watched`() = runTest {
        library.marks.markWatched("ep:${testEpisode(1, 2).id}")

        assertThat(statusOf(testEpisode(1, 2))).isEqualTo(WatchStatus.Watched)
    }

    /** Two seasons of three. */
    private val episodes = listOf(1, 2).flatMap { season ->
        (1..3).map { number -> testEpisode(season, number) }
    }

    private suspend fun seriesCards() = library.continueWatchingSeries().first()

    @Test
    fun `marking an episode Watched makes the one after it Up Next`() = runTest {
        db.episodes().upsertAll(episodes)

        library.marks.markWatched("ep:${testEpisode(1, 3).id}")

        assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(testEpisode(2, 1), progress = null))
    }

    private suspend fun watch(ep: Episode) {
        now += 60_000
        library.recordProgress("ep:${ep.id}", positionMillis = 2_400_000, durationMillis = 2_400_000)
    }

    @Test
    fun `marking the latest Watched episode Unwatched makes it Up Next again`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 1))
        watch(testEpisode(1, 2))
        watch(testEpisode(1, 3))

        now += 60_000
        library.marks.markUnwatched("ep:${testEpisode(1, 3).id}")

        assertThat(statusOf(testEpisode(1, 3))).isEqualTo(WatchStatus.Unwatched)
        assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(testEpisode(1, 3), progress = null))
    }

    @Test
    fun `the series page plays an episode marked Unwatched next, as Continue Watching does`() = runTest {
        watch(testEpisode(1, 1))
        watch(testEpisode(1, 2))

        now += 60_000
        library.marks.markUnwatched("ep:${testEpisode(1, 2).id}")

        assertThat(library.seriesMainAction(episodes).first()).isEqualTo(SeriesMainAction.Play(testEpisode(1, 2)))
    }

    @Test
    fun `removing an In Progress movie from Continue Watching clears its resume point`() = runTest {
        library.recordProgress("movie:3", positionMillis = 600_000, durationMillis = 2_400_000)
        val card = library.continueWatchingMovies().first().single()

        library.marks.removeFromContinueWatching(card)

        assertThat(library.continueWatchingMovies().first()).isEmpty()
        assertThat(library.resumeFrom("movie:3")).isEqualTo(0)
    }

    @Test
    fun `removing a series from Continue Watching hides it`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 1))

        library.marks.removeFromContinueWatching(seriesCards().single())

        assertThat(seriesCards()).isEmpty()
    }

    @Test
    fun `a removed series comes back when one of its episodes is next played`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 1))
        library.marks.removeFromContinueWatching(seriesCards().single())

        watch(testEpisode(1, 2))

        assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(testEpisode(1, 3), progress = null))
    }

    @Test
    fun `a removed series comes back when one of its episodes is next marked`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 1))
        library.marks.removeFromContinueWatching(seriesCards().single())

        now += 60_000
        library.marks.markWatched("ep:${testEpisode(1, 2).id}")

        assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(testEpisode(1, 3), progress = null))
    }

    @Test
    fun `a removed series comes back when a new episode arrives`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 1))
        library.marks.removeFromContinueWatching(seriesCards().single())

        db.episodes().upsertAll(listOf(testEpisode(3, 1)))

        assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(testEpisode(1, 2), progress = null))
    }

    @Test
    fun `a removed series stays away when its episodes are fetched again unchanged`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 1))
        library.marks.removeFromContinueWatching(seriesCards().single())

        db.episodes().upsertAll(episodes)

        assertThat(seriesCards()).isEmpty()
    }

    @Test
    fun `removing a series at an In Progress episode clears that episode's resume point`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 1))
        now += 60_000
        library.recordProgress("ep:${testEpisode(1, 2).id}", positionMillis = 600_000, durationMillis = 2_400_000)

        library.marks.removeFromContinueWatching(seriesCards().single())

        assertThat(library.resumeFrom("ep:${testEpisode(1, 2).id}")).isEqualTo(0)
        assertThat(seriesCards()).isEmpty()
    }

    @Test
    fun `removing a series takes it out of only the active Profile's Continue Watching`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 1))
        activeProfile.value = 2L
        watch(testEpisode(1, 1))

        library.marks.removeFromContinueWatching(seriesCards().single())
        activeProfile.value = 1L

        assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(testEpisode(1, 2), progress = null))
    }

    @Test
    fun `playing a title marked Watched picks up from how far it is played again`() = runTest {
        library.marks.markWatched("ep:${testEpisode(1, 2).id}")

        now += 60_000
        library.recordProgress("ep:${testEpisode(1, 2).id}", positionMillis = 600_000, durationMillis = 2_400_000)

        assertThat(statusOf(testEpisode(1, 2)))
            .isEqualTo(WatchStatus.InProgress(progress = 0.25f, remainingMillis = 1_800_000))
    }

    @Test
    fun `an Unwatched mark synced from another device replaces older progress here`() = runTest {
        watch(testEpisode(1, 2))

        val merged = library.mergeSynced(
            PlaybackPosition(
                profileId = 1,
                mediaKey = "ep:${testEpisode(1, 2).id}",
                positionMillis = 2_400_000,
                durationMillis = 2_400_000,
                updatedAtMillis = now + 1,
                mark = HandMark.UNWATCHED,
            ),
        )

        assertThat(merged).isTrue()
        assertThat(statusOf(testEpisode(1, 2))).isEqualTo(WatchStatus.Unwatched)
    }

    private fun dismissal(dismissedAtMillis: Long) = SeriesDismissal(
        profileId = 1,
        sourceId = 1,
        seriesId = "s",
        dismissedAtMillis = dismissedAtMillis,
        lastSeason = 2,
        lastEpisode = 3,
    )

    @Test
    fun `a series removed on another device is removed here too`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 1))

        val merged = library.marks.mergeSynced(dismissal(dismissedAtMillis = now + 1))

        assertThat(merged).isTrue()
        assertThat(seriesCards()).isEmpty()
        assertThat(library.marks.everyProfiles()).containsExactly(dismissal(dismissedAtMillis = now + 1))
    }

    @Test
    fun `an older removal synced from another device never replaces a newer one here`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 1))
        now += 60_000
        library.marks.removeFromContinueWatching(seriesCards().single())

        val merged = library.marks.mergeSynced(dismissal(dismissedAtMillis = now - 1))

        assertThat(merged).isFalse()
        assertThat(library.marks.everyProfiles().single().dismissedAtMillis).isEqualTo(now)
    }

    @Test
    fun `a movie's status is how far the Profile is with it, hand marks included`() = runTest {
        assertThat(library.marks.statusOf("movie:3")).isEqualTo(WatchStatus.Unwatched)

        library.recordProgress("movie:3", positionMillis = 600_000, durationMillis = 2_400_000)
        assertThat(library.marks.statusOf("movie:3")).isEqualTo(WatchStatus.InProgress(0.25f, 1_800_000))

        library.marks.markWatched("movie:3")
        assertThat(library.marks.statusOf("movie:3")).isEqualTo(WatchStatus.Watched)
    }
}
