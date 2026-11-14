/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.EPISODES_OF_PLAYED_SERIES
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.PlaybackPosition
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The episodes Continue Watching and Home's covers are worked out from: each played series', whole (#277). */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlayedSeriesEpisodesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @After
    fun close() = db.close()

    @Test
    fun `each Profile gets every episode of the series it has played, and none of the others`() = runTest {
        db.episodes().upsertAll(
            listOf(
                episode(101, sourceId = 1, seriesId = "a"),
                episode(102, sourceId = 1, seriesId = "a"),
                episode(103, sourceId = 1, seriesId = "a"),
                episode(201, sourceId = 1, seriesId = "b"),
                episode(202, sourceId = 1, seriesId = "b"),
                // The same provider id on another source is another series.
                episode(301, sourceId = 2, seriesId = "a"),
                episode(302, sourceId = 2, seriesId = "a"),
                episode(401, sourceId = 1, seriesId = "c"),
                episode(402, sourceId = 1, seriesId = "c"),
            ),
        )
        db.positions().upsertAll(
            listOf(
                played(profileId = 1, "ep:102"),
                // Two played episodes of one series still list each of its episodes once.
                played(profileId = 1, "ep:401"),
                played(profileId = 1, "ep:402"),
                // A movie with an episode's row id is not that episode.
                played(profileId = 1, "movie:201"),
                played(profileId = 2, "ep:301"),
                played(profileId = 2, "ep:402"),
            ),
        )

        assertThat(db.episodes().ofPlayedSeries(1).map { it.id }).containsExactly(101L, 102L, 103L, 401L, 402L)
        assertThat(db.episodes().ofPlayedSeries(2).map { it.id }).containsExactly(301L, 302L, 401L, 402L)
        assertThat(db.episodes().ofPlayedSeries(3)).isEmpty()
    }

    @Test
    fun `the query doesn't look through every episode again for each episode stored`() {
        val plan = db.openHelper.readableDatabase
            .query("EXPLAIN QUERY PLAN $EPISODES_OF_PLAYED_SERIES", arrayOf<Any>(1L))
            .use { cursor ->
                buildList {
                    val detail = cursor.getColumnIndexOrThrow("detail")
                    while (cursor.moveToNext()) add(cursor.getString(detail))
                }
            }

        assertThat(plan).isNotEmpty()
        assertThat(plan.filter { it.contains("CORRELATED") }).isEmpty()
    }

    private fun episode(id: Long, sourceId: Long, seriesId: String) =
        testEpisode(1, 1).copy(id = id, sourceId = sourceId, seriesId = seriesId, episodeId = "$id")

    private fun played(profileId: Long, mediaKey: String) = PlaybackPosition(
        profileId = profileId,
        mediaKey = mediaKey,
        positionMillis = 600_000,
        durationMillis = 2_400_000,
        updatedAtMillis = 1_000,
    )
}
