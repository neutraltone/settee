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
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.SeriesRef
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.ViewerLibrary
import app.opentv.data.repo.coverStatuses
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the quiet line on a Favourites or Recently Added cover says about where the Profile is. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CoverStatusTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val activeProfile = MutableStateFlow(1L)
    private var now = 1_000_000L
    private val library = ViewerLibrary(db, activeProfile, clock = { now })

    private val show = SeriesRef(sourceId = 1, seriesId = "s")

    @After
    fun close() = db.close()

    @Test
    fun `a movie part way through says how long is left`() = runTest {
        library.recordProgress("movie:7", positionMillis = 600_000, durationMillis = 1_920_000)

        assertThat(library.coverStatuses().first().ofMovie(7))
            .isEqualTo(CoverStatus.InProgress(progress = 0.3125f, remainingMillis = 1_320_000))
    }

    @Test
    fun `a movie played past the credits is Watched`() = runTest {
        library.recordProgress("movie:7", positionMillis = 1_900_000, durationMillis = 1_920_000)

        assertThat(library.coverStatuses().first().ofMovie(7)).isEqualTo(CoverStatus.Watched)
    }

    @Test
    fun `a show part way through an episode names the episode and how long is left`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 1))
        now += 1_000
        library.recordProgress("ep:${testEpisode(1, 2).id}", positionMillis = 1_080_000, durationMillis = 2_400_000)

        assertThat(library.coverStatuses().first().ofSeries(show)).isEqualTo(
            CoverStatus.InProgress(progress = 0.45f, remainingMillis = 1_320_000, episode = testEpisode(1, 2)),
        )
    }

    @Test
    fun `a movie never played has no status`() = runTest {
        assertThat(library.coverStatuses().first().ofMovie(7)).isNull()
        assertThat(library.coverStatuses().first().ofSeries(show)).isNull()
    }

    @Test
    fun `a show between episodes names its Up Next`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(1, 3))

        assertThat(library.coverStatuses().first().ofSeries(show)).isEqualTo(CoverStatus.UpNext(testEpisode(2, 1)))
    }

    @Test
    fun `a show whose Up Next was started part way says how long is left of it`() = runTest {
        db.episodes().upsertAll(episodes)
        library.recordProgress("ep:${testEpisode(1, 3).id}", positionMillis = 600_000, durationMillis = 2_400_000)
        now += 1_000
        watch(testEpisode(1, 2))

        assertThat(library.coverStatuses().first().ofSeries(show)).isEqualTo(
            CoverStatus.InProgress(progress = 0.25f, remainingMillis = 1_800_000, episode = testEpisode(1, 3)),
        )
    }

    @Test
    fun `a show caught up on its last episode is Watched`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(2, 3))

        assertThat(library.coverStatuses().first().ofSeries(show)).isEqualTo(CoverStatus.Watched)
    }

    @Test
    fun `a show with an episode added after the Profile caught up has a New Episode`() = runTest {
        db.episodes().upsertAll(episodes)
        watch(testEpisode(2, 3))
        now += 60_000
        db.episodes().upsertAll(listOf(testEpisode(3, 1).copy(firstSeenMillis = now)))

        assertThat(library.coverStatuses().first().ofSeries(show)).isEqualTo(CoverStatus.NewEpisode)
    }

    @Test
    fun `with many shows played, each cover goes by that show's own episodes`() = runTest {
        playManyShows()

        val covers = library.coverStatuses().first()

        (0 until SHOWS).forEach { show ->
            val ref = SeriesRef(sourceId = 1, seriesId = "s$show")
            val expected = if (show % 2 == 0) {
                CoverStatus.UpNext(manyShowsEpisode(show, PLAYED + 1))
            } else {
                CoverStatus.InProgress(
                    progress = 0.25f,
                    remainingMillis = 1_800_000,
                    episode = manyShowsEpisode(show, PLAYED),
                )
            }
            assertThat(covers.ofSeries(ref)).isEqualTo(expected)
        }
    }

    @Test
    fun `working out the covers reads each saved position's key a few times, not once per show`() = runTest {
        playManyShows()
        var parses = 0
        MediaKey.onParse = { parses++ }
        try {
            library.coverStatuses().first()
        } finally {
            MediaKey.onParse = null
        }

        // Each position's key is read by Continue Watching's movies, its series and the covers; once
        // per show would be SHOWS * SHOWS * PLAYED.
        assertThat(parses).isAtMost(PARSES_PER_POSITION * SHOWS * PLAYED)
    }

    /**
     * [SHOWS] shows of 30 episodes, each played in order through its first [PLAYED]: the last of them
     * to the end in even-numbered shows, and a quarter of the way in odd-numbered ones.
     */
    private suspend fun playManyShows() {
        db.episodes().upsertAll((0 until SHOWS).flatMap { show -> (1..30).map { manyShowsEpisode(show, it) } })
        db.positions().upsertAll(
            (0 until SHOWS).flatMap { show ->
                (1..PLAYED).map { number ->
                    val partWay = show % 2 == 1 && number == PLAYED
                    PlaybackPosition(
                        profileId = 1,
                        mediaKey = "ep:${manyShowsEpisode(show, number).id}",
                        positionMillis = if (partWay) 600_000 else 2_400_000,
                        durationMillis = 2_400_000,
                        updatedAtMillis = now + show * 100L + number,
                    )
                }
            },
        )
    }

    private fun manyShowsEpisode(show: Int, number: Int) = testEpisode(1, number).copy(
        id = show * 100L + number,
        seriesId = "s$show",
        episodeId = "$show-$number",
    )

    /** Two seasons of three. */
    private val episodes = listOf(1, 2).flatMap { season -> (1..3).map { number -> testEpisode(season, number) } }

    private suspend fun watch(ep: Episode) =
        library.recordProgress("ep:${ep.id}", positionMillis = 2_400_000, durationMillis = 2_400_000)

    private companion object {
        const val SHOWS = 100

        /** Episodes played of each show: 2,000 positions in all. */
        const val PLAYED = 20

        const val PARSES_PER_POSITION = 5
    }
}
