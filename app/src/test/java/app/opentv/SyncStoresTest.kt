/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Favourite
import app.opentv.data.model.HandMark
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.ProfileSetting
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.SeriesDismissal
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.sync.SyncFavourite
import app.opentv.sync.SyncHiddenChannel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each store's portable form: what one device exports, another imports with its own row ids. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncStoresTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private inner class Device(val rowIdOffset: Long) {
        val db: OpenTvDatabase = Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val graph = ServiceLocator.Graph(app) { db }
        val library get() = graph.viewerLibrary
        val sourceId = 1 + rowIdOffset

        /** A row id for "Alex", different on each device; the base Profile takes the first one. */
        suspend fun alex(): Long = graph.profiles.findOrCreate("Alex")!!

        suspend fun seedSource() =
            db.sources().insert(Source(id = sourceId, name = "Provider", kind = SourceKind.M3U, url = "x"))

        suspend fun seedChannel(hidden: Boolean = false) = db.channels().upsertAll(
            listOf(
                Channel(
                    id = 1 + rowIdOffset,
                    sourceId = sourceId,
                    streamId = "c1",
                    name = "Channel 1",
                    categoryId = null,
                    logoUrl = null,
                    epgChannelId = null,
                    number = 1,
                    streamUrl = "x",
                    hidden = hidden,
                ),
            ),
        )

        suspend fun seedMovie() = db.movies().upsertAll(
            listOf(
                Movie(
                    id = 1 + rowIdOffset,
                    sourceId = sourceId,
                    streamId = "m1",
                    name = "Movie 1",
                    categoryId = null,
                    posterUrl = null,
                    rating = null,
                    year = null,
                    plot = null,
                    durationSeconds = 6_000,
                    containerExtension = "mkv",
                    streamUrl = "x",
                ),
            ),
        )
    }

    private val deviceA = Device(rowIdOffset = 0)
    private val deviceB = Device(rowIdOffset = 100)

    @After
    fun close() {
        deviceA.db.close()
        deviceB.db.close()
    }

    @Test
    fun `Profiles arrive by name and none is removed or doubled`() = runTest {
        deviceA.graph.profiles.create("Alex")
        deviceB.graph.profiles.create("alex")
        deviceB.graph.profiles.create("Sam")

        deviceB.graph.profiles.import(deviceA.graph.profiles.export())

        assertThat(deviceB.graph.profiles.export()).containsAtLeast("alex", "Sam")
        assertThat(deviceB.graph.profiles.export().filter { it.equals("alex", ignoreCase = true) }).hasSize(1)
    }

    @Test
    fun `Favourites land on the Profile with the same name, counted once`() = runTest {
        deviceB.graph.profiles.create("Spare")
        val onB = deviceB.alex()
        deviceA.db.favourites().upsert(Favourite(deviceA.alex(), StreamKind.LIVE, "Provider", "c1"))
        val exported = deviceA.library.favourites.export()

        assertThat(deviceB.library.favourites.import(exported)).isEqualTo(1)
        assertThat(deviceB.library.favourites.import(exported)).isEqualTo(0)

        assertThat(deviceB.db.favourites().all()).containsExactly(Favourite(onB, StreamKind.LIVE, "Provider", "c1"))
    }

    @Test
    fun `a Favourite for a Profile this device doesn't have is left out`() = runTest {
        val item = SyncFavourite(profile = "Nobody", kind = StreamKind.LIVE.name, source = "Provider", id = "c1")

        assertThat(deviceB.library.favourites.import(listOf(item))).isEqualTo(0)
    }

    @Test
    fun `a Favourite finds its Profile by the same rule that matches a Profile's name`() = runTest {
        // Lowercasing and ignoring case disagree about this name; Profiles ignores case.
        deviceB.graph.profiles.create("\u0130pek")
        val item = SyncFavourite(profile = "ipek", kind = StreamKind.LIVE.name, source = "Provider", id = "c1")

        deviceB.graph.profiles.import(listOf(item.profile))

        assertThat(deviceB.library.favourites.import(listOf(item))).isEqualTo(1)
    }

    @Test
    fun `Continue Watching removals travel by source and Profile name, newest wins`() = runTest {
        deviceA.seedSource()
        deviceB.seedSource()
        deviceB.graph.profiles.create("Spare")
        val onB = deviceB.alex()
        deviceA.db.seriesDismissals().upsert(
            SeriesDismissal(deviceA.alex(), deviceA.sourceId, "s", dismissedAtMillis = 2_000, 1, 2),
        )
        val exported = deviceA.library.syncedDismissals.export()

        assertThat(deviceB.library.syncedDismissals.import(exported)).isEqualTo(1)
        assertThat(deviceB.library.syncedDismissals.import(exported)).isEqualTo(0)

        assertThat(deviceB.db.seriesDismissals().all())
            .containsExactly(SeriesDismissal(onB, deviceB.sourceId, "s", dismissedAtMillis = 2_000, 1, 2))
    }

    @Test
    fun `settings land on the Profile with the same name and the newer choice wins`() = runTest {
        deviceB.graph.profiles.create("Spare")
        val onB = deviceB.alex()
        deviceA.db.profileSettings().upsert(ProfileSetting(deviceA.alex(), "subtitles.on", "true", 2_000))
        deviceB.db.profileSettings().upsert(ProfileSetting(onB, "subtitles.language", "fr", 5_000))
        deviceA.db.profileSettings().upsert(ProfileSetting(deviceA.alex(), "subtitles.language", "de", 1_000))

        val taken = deviceB.graph.profileSettings.import(deviceA.graph.profileSettings.export())

        assertThat(taken).isEqualTo(1)
        assertThat(deviceB.db.profileSettings().all()).containsExactly(
            ProfileSetting(onB, "subtitles.on", "true", 2_000),
            ProfileSetting(onB, "subtitles.language", "fr", 5_000),
        )
    }

    @Test
    fun `progress and a hand mark travel by source name and provider id`() = runTest {
        deviceA.seedSource()
        deviceB.seedSource()
        deviceA.seedMovie()
        deviceB.seedMovie()
        deviceB.graph.profiles.create("Spare")
        val onB = deviceB.alex()
        deviceA.library.mergeSynced(
            PlaybackPosition(deviceA.alex(), "movie:1", 1_000, 6_000, 2_000, HandMark.WATCHED),
        )
        val exported = deviceA.library.syncedProgress.export()

        val first = deviceB.library.syncedProgress.import(exported)
        val again = deviceB.library.syncedProgress.import(exported)

        assertThat(first.taken).isEqualTo(1)
        assertThat(first.waiting).isEmpty()
        assertThat(again.taken).isEqualTo(0)
        assertThat(deviceB.db.positions().all())
            .containsExactly(PlaybackPosition(onB, "movie:101", 1_000, 6_000, 2_000, HandMark.WATCHED))
    }

    @Test
    fun `an episode this device hasn't stored waits, and a movie it hasn't is dropped`() = runTest {
        deviceA.seedSource()
        deviceB.seedSource()
        deviceA.seedMovie()
        val alex = deviceA.alex()
        deviceB.alex()
        deviceA.db.episodes().upsertAll(listOf(testEpisode(1, 1)))
        deviceA.library.mergeSynced(PlaybackPosition(alex, "movie:1", 1_000, 6_000, 2_000))
        deviceA.library.mergeSynced(PlaybackPosition(alex, "ep:11", 1_000, 6_000, 2_000))

        val imported = deviceB.library.syncedProgress.import(deviceA.library.syncedProgress.export())

        assertThat(imported.taken).isEqualTo(0)
        assertThat(imported.waiting.map { it.seriesId to it.id }).containsExactly("s" to "1-1")
    }

    @Test
    fun `hidden channels travel by source name and stream id, counted when newly hidden`() = runTest {
        deviceA.seedSource()
        deviceB.seedSource()
        deviceA.seedChannel()
        deviceB.seedChannel()
        deviceA.graph.catalogRepository.setChannelHidden(1, true)
        val exported = deviceA.graph.catalogRepository.exportHiddenChannels()

        assertThat(exported.map { it.source to it.id }).containsExactly("Provider" to "c1")
        assertThat(exported.single().hidden).isTrue()
        assertThat(deviceB.graph.catalogRepository.importHiddenChannels(exported)).isEqualTo(1)
        assertThat(deviceB.graph.catalogRepository.importHiddenChannels(exported)).isEqualTo(0)
        assertThat(deviceB.db.channels().byId(101)?.hidden).isTrue()
    }

    @Test
    fun `a separator row the parser hid doesn't travel as a viewer's change`() = runTest {
        deviceA.seedSource()
        deviceA.seedChannel(hidden = true)

        assertThat(deviceA.graph.catalogRepository.exportHiddenChannels()).isEmpty()
    }

    @Test
    fun `when a channel's hidden state changed survives a Catalogue Refresh`() = runTest {
        deviceA.seedSource()
        deviceA.seedChannel()
        deviceA.graph.catalogRepository.setChannelHidden(1, true)
        val before = deviceA.graph.catalogRepository.exportHiddenChannels().single()

        val incoming = deviceA.db.channels().forSource(1).map { it.copy(hidden = false, hiddenChangedAtMillis = 0) }
        deviceA.db.channels().replaceCatalogue(1, incoming, syncStamp = 2)

        assertThat(deviceA.graph.catalogRepository.exportHiddenChannels()).containsExactly(before)
    }

    @Test
    fun `a hidden channel from a source this device doesn't have changes nothing`() = runTest {
        deviceB.seedSource()
        deviceB.seedChannel()

        val hidden = deviceB.graph.catalogRepository.importHiddenChannels(listOf(SyncHiddenChannel("Other", "c1")))

        assertThat(hidden).isEqualTo(0)
        assertThat(deviceB.db.channels().byId(101)?.hidden).isFalse()
    }

    @Test
    fun `only finished recordings on the NAS travel, once each`() = runTest {
        val recordings = deviceA.graph.recordingRepository
        recordings.insert(recording("smb://nas.example.com/share/a.ts", RecordingStatus.COMPLETED))
        recordings.insert(recording("smb://nas.example.com/share/b.ts", RecordingStatus.RECORDING))
        recordings.insert(recording("/data/local/c.ts", RecordingStatus.COMPLETED))
        val exported = recordings.export()

        assertThat(exported.map { it.filePath }).containsExactly("smb://nas.example.com/share/a.ts")
        assertThat(deviceB.graph.recordingRepository.import(exported)).isEqualTo(1)
        assertThat(deviceB.graph.recordingRepository.import(exported)).isEqualTo(0)
    }

    private fun recording(path: String, status: RecordingStatus) = Recording(
        channelId = 1,
        sourceId = 1,
        channelName = "Channel 1",
        title = "The Match",
        filePath = path,
        streamUrl = "x",
        status = status,
    )
}
