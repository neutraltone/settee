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
import app.opentv.data.model.Channel
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.LivePreview
import app.opentv.data.repo.ViewerLibrary
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LivePreviewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val activeProfile = MutableStateFlow(1L)
    private var now = 1_000_000L
    private val library = ViewerLibrary(db, activeProfile, clock = { now })

    @Before
    fun provider() = runTest {
        db.sources().insert(Source(id = 1, name = "Provider A", kind = SourceKind.XTREAM, url = "http://example.com"))
    }

    @After
    fun close() = db.close()

    private suspend fun stored(streamId: String, name: String, hidden: Boolean = false, syncStamp: Long = 1): Channel {
        val incoming = Channel(
            sourceId = 1,
            streamId = streamId,
            name = name,
            categoryId = null,
            logoUrl = "http://example.com/$streamId.png",
            epgChannelId = null,
            number = null,
            streamUrl = "http://example.com/live/u/p/$streamId.ts",
            hidden = hidden,
        )
        db.channels().replaceCatalogue(sourceId = 1, incoming = listOf(incoming), syncStamp = syncStamp)
        return db.channels().forSource(1).single { it.streamId == streamId }
    }

    private suspend fun preview(): LivePreview? = library.live.preview().first()

    @Test
    fun `the live channel played last is the Live Preview`() = runTest {
        val bbc = stored("101", "BBC One")

        library.live.record(bbc)

        assertThat(preview()).isEqualTo(LivePreview.Playable(bbc))
    }

    @Test
    fun `anything played after the live channel means there is no Live Preview`() = runTest {
        val bbc = stored("101", "BBC One")
        for (key in listOf("movie:5", "ep:7", "rec:3")) {
            library.live.record(bbc)
            now += 1_000
            library.recordProgress(key, positionMillis = 60_000, durationMillis = 3_600_000)
            now += 1_000

            assertThat(preview()).isNull()
        }
    }

    @Test
    fun `one Profile's live channel is not another's Live Preview`() = runTest {
        val bbc = stored("101", "BBC One")
        library.live.record(bbc)

        activeProfile.value = 2L

        assertThat(preview()).isNull()
    }

    @Test
    fun `a movie another Profile played doesn't take this Profile's Live Preview away`() = runTest {
        val bbc = stored("101", "BBC One")
        library.live.record(bbc)
        now += 1_000
        activeProfile.value = 2L
        library.recordProgress("movie:5", positionMillis = 60_000, durationMillis = 3_600_000)

        activeProfile.value = 1L

        assertThat(preview()).isEqualTo(LivePreview.Playable(bbc))
    }

    @Test
    fun `a channel hidden since it was watched has no Live Preview`() = runTest {
        val bbc = stored("101", "BBC One")
        library.live.record(bbc)

        db.channels().setHidden(bbc.id, true)

        assertThat(preview()).isNull()
    }

    @Test
    fun `a channel gone from the catalogue is still shown, by the name and logo it had`() = runTest {
        val bbc = stored("101", "BBC One")
        library.live.record(bbc)

        stored("202", "ITV1", syncStamp = 2)

        assertThat(preview()).isEqualTo(LivePreview.Gone("BBC One", "http://example.com/101.png"))
    }

    @Test
    fun `marking a title Watched by hand isn't playing it, so the Live Preview stays`() = runTest {
        val bbc = stored("101", "BBC One")
        library.live.record(bbc)
        now += 1_000

        library.marks.markWatched("movie:5")

        assertThat(preview()).isEqualTo(LivePreview.Playable(bbc))
    }

    @Test
    fun `a movie watched on another device doesn't take this one's Live Preview away`() = runTest {
        val bbc = stored("101", "BBC One")
        library.live.record(bbc)

        library.mergeSynced(
            PlaybackPosition(
                profileId = 1,
                mediaKey = "movie:5",
                positionMillis = 60_000,
                durationMillis = 3_600_000,
                updatedAtMillis = now + 1_000,
            ),
        )

        assertThat(preview()).isEqualTo(LivePreview.Playable(bbc))
    }

    @Test
    fun `going back to live after a movie brings the Live Preview back`() = runTest {
        val bbc = stored("101", "BBC One")
        library.recordProgress("movie:5", positionMillis = 60_000, durationMillis = 3_600_000)
        now += 1_000

        library.live.record(bbc)

        assertThat(preview()).isEqualTo(LivePreview.Playable(bbc))
    }
}
