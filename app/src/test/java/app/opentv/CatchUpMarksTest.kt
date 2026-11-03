/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.db.PlaybackPositionDao
import app.opentv.data.model.Episode
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.repo.CatchUpMarks
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.ViewerLibrary
import app.opentv.data.repo.WatchStatus
import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
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
class CatchUpMarksTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val activeProfile = MutableStateFlow(1L)
    private var now = 1_000_000L
    private val library = ViewerLibrary(db, activeProfile, clock = { now })

    /** Three seasons of three. */
    private val episodes = listOf(1, 2, 3).flatMap { season -> (1..3).map { testEpisode(season, it) } }

    @After
    fun close() = db.close()

    private suspend fun statusOf(ep: Episode): WatchStatus = library.episodeStatuses().first()[ep.id]

    private suspend fun seriesCards() = library.continueWatchingSeries().first()

    @Test
    fun `marking up to an episode marks it and every earlier one across seasons, and Up Next follows it`() = runTest {
        db.episodes().upsertAll(episodes)

        library.catchUp.markWatchedUpTo(testEpisode(2, 2))

        for (ep in episodes) {
            val expected = if (ep.season == 1 || ep == testEpisode(2, 1) || ep == testEpisode(2, 2)) {
                WatchStatus.Watched
            } else {
                WatchStatus.Unwatched
            }
            assertThat(statusOf(ep)).isEqualTo(expected)
        }
        assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(testEpisode(2, 3), progress = null))
    }

    @Test
    fun `marking up to an episode leaves the specials alone`() = runTest {
        db.episodes().upsertAll(episodes + testEpisode(0, 1))

        library.catchUp.markWatchedUpTo(testEpisode(1, 2))

        assertThat(statusOf(testEpisode(0, 1))).isEqualTo(WatchStatus.Unwatched)
        assertThat(statusOf(testEpisode(1, 1))).isEqualTo(WatchStatus.Watched)
    }

    @Test
    fun `marking up to a special marks only the specials before it`() = runTest {
        db.episodes().upsertAll(episodes + testEpisode(0, 1) + testEpisode(0, 2) + testEpisode(0, 3))

        library.catchUp.markWatchedUpTo(testEpisode(0, 2))

        assertThat(statusOf(testEpisode(0, 1))).isEqualTo(WatchStatus.Watched)
        assertThat(statusOf(testEpisode(0, 2))).isEqualTo(WatchStatus.Watched)
        assertThat(statusOf(testEpisode(0, 3))).isEqualTo(WatchStatus.Unwatched)
        assertThat(statusOf(testEpisode(1, 1))).isEqualTo(WatchStatus.Unwatched)
    }

    /** Plays [ep] to its end, a minute after whatever came before. */
    private suspend fun watch(ep: Episode) {
        now += 60_000
        library.recordProgress("ep:${ep.id}", positionMillis = 2_400_000, durationMillis = 2_400_000)
    }

    @Test
    fun `Up Next follows the chosen episode even when it was already Watched and an earlier one wasn't`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 3))
        watch(testEpisode(1, 1))

        now += 60_000
        library.catchUp.markWatchedUpTo(testEpisode(1, 3))

        assertThat(statusOf(testEpisode(1, 2))).isEqualTo(WatchStatus.Watched)
        assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(testEpisode(2, 1), progress = null))
    }

    @Test
    fun `a bulk mark keeps how far each episode was played and applies to the active Profile only`() = runTest {
        db.episodes().upsertAll(episodes)
        library.recordProgress("ep:${testEpisode(1, 1).id}", positionMillis = 600_000, durationMillis = 2_400_000)

        library.catchUp.markWatchedUpTo(testEpisode(1, 2))

        val kept = db.positions().get(1, "ep:${testEpisode(1, 1).id}")
        assertThat(kept?.positionMillis).isEqualTo(600_000)
        assertThat(kept?.durationMillis).isEqualTo(2_400_000)
        assertThat(db.positions().forProfile(2)).isEmpty()
    }

    @Test
    fun `no two episodes in a bulk mark share a stamp, and none is ahead of the clock`() = runTest {
        db.episodes().upsertAll(episodes)

        library.catchUp.markWatchedUpTo(testEpisode(3, 3))

        val stamps = db.positions().forProfile(1).map { it.updatedAtMillis }
        assertThat(stamps).containsNoDuplicates()
        assertThat(stamps.max()).isEqualTo(now)
    }

    @Test
    fun `a bulk mark over a long series reaches the database as one write`() = runTest {
        val long = (1..200).map { testEpisode(1, it) }
        db.episodes().upsertAll(long)
        val counting = CountingPositions(db.positions())
        val marks = CatchUpMarks(counting, db.episodes(), activeProfile, clock = { now }) { db.withTransaction(it) }

        marks.markWatchedUpTo(testEpisode(1, 200))

        assertThat(counting.writes).isEqualTo(1)
        assertThat(db.positions().forProfile(1)).hasSize(200)
    }

    @Test
    fun `an episode listed with the same numbers as the chosen one but after it is left alone`() = runTest {
        val repeat = testEpisode(1, 1).copy(id = 99, episodeId = "repeat")
        db.episodes().upsertAll(episodes + repeat)

        library.catchUp.markWatchedUpTo(testEpisode(1, 1))

        assertThat(statusOf(repeat)).isEqualTo(WatchStatus.Unwatched)
        assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(repeat, progress = null))
    }

    @Test
    fun `an earlier episode already Watched keeps when it was played, so only what changed syncs`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 1))
        val played = db.positions().get(1, "ep:${testEpisode(1, 1).id}")

        now += 60_000
        library.catchUp.markWatchedUpTo(testEpisode(1, 3))

        assertThat(db.positions().get(1, "ep:${testEpisode(1, 1).id}")).isEqualTo(played)
        assertThat(statusOf(testEpisode(1, 2))).isEqualTo(WatchStatus.Watched)
    }

    @Test
    fun `marking a season Unwatched doesn't override another device's progress on an episode not played here`() =
        runTest {
            db.episodes().upsertAll(episodes)
            watch(testEpisode(1, 1))
            val elsewhere = PlaybackPosition(
                profileId = 1,
                mediaKey = "ep:${testEpisode(1, 2).id}",
                positionMillis = 600_000,
                durationMillis = 2_400_000,
                updatedAtMillis = now,
            )

            now += 60_000
            library.catchUp.markSeasonUnwatched(episodes.filter { it.season == 1 })

            assertThat(statusOf(testEpisode(1, 1))).isEqualTo(WatchStatus.Unwatched)
            assertThat(library.mergeSynced(elsewhere)).isTrue()
            assertThat(statusOf(testEpisode(1, 2)))
                .isEqualTo(WatchStatus.InProgress(progress = 0.25f, remainingMillis = 1_800_000))
        }

    /** Counts writes, as Room's own flows coalesce and so can't tell one write from many. */
    private class CountingPositions(private val real: PlaybackPositionDao) : PlaybackPositionDao by real {
        var writes = 0

        override suspend fun upsert(position: PlaybackPosition) {
            writes++
            real.upsert(position)
        }

        override suspend fun upsertAll(positions: List<PlaybackPosition>) {
            writes++
            real.upsertAll(positions)
        }
    }

    @Test
    fun `marking a season Watched makes Up Next the episode after its last, even with a later season played`() =
        runTest {
            db.episodes().upsertAll(episodes)
            watch(testEpisode(3, 1))

            now += 60_000
            library.catchUp.markSeasonWatched(episodes.filter { it.season == 1 })

            assertThat(episodes.filter { it.season == 1 }.map { statusOf(it) }).containsExactly(
                WatchStatus.Watched,
                WatchStatus.Watched,
                WatchStatus.Watched,
            )
            assertThat(statusOf(testEpisode(2, 1))).isEqualTo(WatchStatus.Unwatched)
            assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(testEpisode(2, 1), progress = null))
        }

    @Test
    fun `marking a season Unwatched works Up Next out from what remains`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 1))
        watch(testEpisode(2, 1))
        watch(testEpisode(2, 2))

        now += 60_000
        library.catchUp.markSeasonUnwatched(episodes.filter { it.season == 2 })

        assertThat(statusOf(testEpisode(2, 2))).isEqualTo(WatchStatus.Unwatched)
        assertThat(statusOf(testEpisode(1, 1))).isEqualTo(WatchStatus.Watched)
        assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(testEpisode(1, 2), progress = null))
    }

    @Test
    fun `marking up to here has something to do only while an earlier episode isn't Watched`() = runTest {
        db.episodes().upsertAll(episodes + testEpisode(0, 1))
        assertThat(library.catchUp.hasEarlierToMark(testEpisode(1, 1))).isFalse()
        assertThat(library.catchUp.hasEarlierToMark(testEpisode(1, 2))).isTrue()

        library.catchUp.markWatchedUpTo(testEpisode(1, 1))

        // The unwatched special before S1 E1 doesn't count, as marking up to here skips it.
        assertThat(library.catchUp.hasEarlierToMark(testEpisode(1, 2))).isFalse()
        assertThat(library.catchUp.hasEarlierToMark(testEpisode(1, 3))).isTrue()
    }

    @Test
    fun `a bulk mark merged on another device in any order gives the same Up Next`() = runTest {
        db.episodes().upsertAll(episodes)
        library.catchUp.markWatchedUpTo(testEpisode(2, 2))
        val sent = db.positions().forProfile(1)

        val otherDb = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val other = ViewerLibrary(otherDb, activeProfile, clock = { now })
        otherDb.episodes().upsertAll(episodes)
        sent.shuffled(Random(77)).forEach { other.mergeSynced(it) }

        assertThat(other.continueWatchingSeries().first())
            .containsExactly(ContinueWatchingCard.Series(testEpisode(2, 3), progress = null))
        otherDb.close()
    }

    @Test
    fun `a specials season can be marked Watched with the rest`() = runTest {
        val specials = listOf(testEpisode(0, 1), testEpisode(0, 2))
        db.episodes().upsertAll(episodes + specials)

        library.catchUp.markSeasonWatched(specials)

        assertThat(statusOf(testEpisode(0, 1))).isEqualTo(WatchStatus.Watched)
        assertThat(seriesCards()).containsExactly(ContinueWatchingCard.Series(testEpisode(1, 1), progress = null))
    }
}
