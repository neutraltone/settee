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
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.repo.InProgressTitle
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
class ViewerLibraryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val activeProfile = MutableStateFlow(1L)
    private var now = 1_000_000L
    private val library = ViewerLibrary(db.positions(), activeProfile, clock = { now })

    @After
    fun close() = db.close()

    private suspend fun statusOf(episodeId: Long): WatchStatus = library.episodeStatuses().first()[episodeId]

    @Test
    fun `an episode played part way is In Progress with how far through it is and how long is left`() = runTest {
        library.recordProgress("ep:7", positionMillis = 600_000, durationMillis = 2_400_000)

        assertThat(statusOf(7)).isEqualTo(WatchStatus.InProgress(progress = 0.25f, remainingMillis = 1_800_000))
    }

    @Test
    fun `an episode played past 93 percent is Watched`() = runTest {
        library.recordProgress("ep:7", positionMillis = 930_001, durationMillis = 1_000_000)

        assertThat(statusOf(7)).isEqualTo(WatchStatus.Watched)
    }

    @Test
    fun `an episode stopped at exactly 93 percent is still In Progress`() = runTest {
        library.recordProgress("ep:7", positionMillis = 930_000, durationMillis = 1_000_000)

        assertThat(statusOf(7)).isEqualTo(WatchStatus.InProgress(progress = 0.93f, remainingMillis = 70_000))
    }

    @Test
    fun `episodes skipped over stay Unwatched`() = runTest {
        library.recordProgress("ep:9", positionMillis = 1_000_000, durationMillis = 1_000_000)

        assertThat(statusOf(8)).isEqualTo(WatchStatus.Unwatched)
    }

    @Test
    fun `another Profile's progress is not this Profile's`() = runTest {
        library.recordProgress("ep:7", positionMillis = 1_000_000, durationMillis = 1_000_000)

        activeProfile.value = 2L

        assertThat(statusOf(7)).isEqualTo(WatchStatus.Unwatched)
    }

    @Test
    fun `progress with no known duration is In Progress from the start with no idea how long is left`() = runTest {
        library.recordProgress("ep:7", positionMillis = 600_000, durationMillis = 0)

        assertThat(statusOf(7)).isEqualTo(WatchStatus.InProgress(progress = 0f, remainingMillis = null))
    }

    @Test
    fun `an In Progress title resumes where the Profile stopped`() = runTest {
        library.recordProgress("movie:3", positionMillis = 600_000, durationMillis = 2_400_000)

        assertThat(library.resumeFrom("movie:3")).isEqualTo(600_000)
    }

    @Test
    fun `a Watched title starts again from the beginning`() = runTest {
        library.recordProgress("movie:3", positionMillis = 2_300_000, durationMillis = 2_400_000)

        assertThat(library.resumeFrom("movie:3")).isEqualTo(0)
    }

    @Test
    fun `a title never played starts from the beginning`() = runTest {
        assertThat(library.resumeFrom("movie:3")).isEqualTo(0)
    }

    @Test
    fun `In Progress titles are listed most recently played first, leaving out Watched ones`() = runTest {
        library.recordProgress("movie:3", positionMillis = 600_000, durationMillis = 2_400_000)
        now += 60_000
        library.recordProgress("movie:4", positionMillis = 2_400_000, durationMillis = 2_400_000)
        now += 60_000
        library.recordProgress("ep:7", positionMillis = 1_200_000, durationMillis = 2_400_000)

        assertThat(library.inProgress().first()).containsExactly(
            InProgressTitle("ep:7", progress = 0.5f),
            InProgressTitle("movie:3", progress = 0.25f),
        ).inOrder()
    }

    @Test
    fun `the movies a Profile has started are the ones it has any progress on, Watched or not`() = runTest {
        library.recordProgress("movie:3", positionMillis = 6_000, durationMillis = 2_400_000)
        library.recordProgress("movie:4", positionMillis = 2_400_000, durationMillis = 2_400_000)
        library.recordProgress("ep:7", positionMillis = 6_000, durationMillis = 2_400_000)
        activeProfile.value = 2L
        library.recordProgress("movie:5", positionMillis = 6_000, durationMillis = 2_400_000)

        assertThat(library.startedMovieIds(profileId = 1)).containsExactly(3L, 4L)
    }

    @Test
    fun `a nonsense negative position from sync counts as the very start`() = runTest {
        library.mergeSynced(synced(profileId = 1, positionMillis = -5_000, updatedAtMillis = now))

        assertThat(statusOf(7)).isEqualTo(WatchStatus.InProgress(progress = 0f, remainingMillis = 1_000_000))
        assertThat(library.resumeFrom("ep:7")).isEqualTo(0)
    }

    @Test
    fun `an In Progress title stays listed however many titles were Watched after it`() = runTest {
        library.recordProgress("movie:3", positionMillis = 600_000, durationMillis = 2_400_000)
        for (episode in 100L until 140L) {
            now += 60_000
            library.recordProgress("ep:$episode", positionMillis = 2_400_000, durationMillis = 2_400_000)
        }

        assertThat(library.inProgress().first()).containsExactly(InProgressTitle("movie:3", progress = 0.25f))
    }

    private fun synced(profileId: Long, positionMillis: Long, updatedAtMillis: Long) = PlaybackPosition(
        profileId = profileId,
        mediaKey = "ep:7",
        positionMillis = positionMillis,
        durationMillis = 1_000_000,
        updatedAtMillis = updatedAtMillis,
    )

    @Test
    fun `progress synced from another device replaces older progress here`() = runTest {
        library.recordProgress("ep:7", positionMillis = 100_000, durationMillis = 1_000_000)

        val merged = library.mergeSynced(synced(profileId = 1, positionMillis = 950_000, updatedAtMillis = now + 1))

        assertThat(merged).isTrue()
        assertThat(statusOf(7)).isEqualTo(WatchStatus.Watched)
    }

    @Test
    fun `progress synced from another device never overwrites newer progress here`() = runTest {
        library.recordProgress("ep:7", positionMillis = 500_000, durationMillis = 1_000_000)

        val merged = library.mergeSynced(synced(profileId = 1, positionMillis = 950_000, updatedAtMillis = now - 1))

        assertThat(merged).isFalse()
        assertThat(statusOf(7)).isEqualTo(WatchStatus.InProgress(progress = 0.5f, remainingMillis = 500_000))
    }

    @Test
    fun `synced progress lands on the Profile it belongs to`() = runTest {
        library.mergeSynced(synced(profileId = 2, positionMillis = 500_000, updatedAtMillis = now))

        assertThat(statusOf(7)).isEqualTo(WatchStatus.Unwatched)
        activeProfile.value = 2L
        assertThat(statusOf(7)).isEqualTo(WatchStatus.InProgress(progress = 0.5f, remainingMillis = 500_000))
    }
}
