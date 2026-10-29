/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Programme
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.data.repo.PlaybackSources
import app.opentv.player.PlaybackErrors
import com.google.common.truth.Truth.assertThat
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every title becomes something playable in one place, with its source's user agent (#98). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaybackSourcesTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val db = test.db
    private val sources = PlaybackSources(db, XtreamApi(test.panel.http), StalkerApi(test.panel.http))

    @After
    fun close() = test.close()

    @Test
    fun `an Xtream channel plays its stored link with the source's user agent`() = runTest {
        db.sources().insert(PANEL_SOURCE.copy(userAgent = "Panel/1"))
        val id = channel(streamUrl = "http://example.com/live/u/p/9.m3u8")

        val playable = sources.forWatching(MediaKey.Live(id))

        assertThat(playable).isEqualTo(
            Playable.Ready(
                key = MediaKey.Live(id),
                url = "http://example.com/live/u/p/9.m3u8",
                userAgent = "Panel/1",
                title = "BBC One",
                isLive = true,
            ),
        )
    }

    @Test
    fun `a playlist channel plays its stored link with the source's user agent`() = runTest {
        db.sources().insert(PANEL_SOURCE.copy(kind = SourceKind.M3U, userAgent = "Playlist/1"))
        val id = channel(streamUrl = "http://example.com/9.ts")

        val playable = sources.forWatching(MediaKey.Live(id)) as Playable.Ready

        assertThat(playable.url).isEqualTo("http://example.com/9.ts")
        assertThat(playable.userAgent).isEqualTo("Playlist/1")
    }

    @Test
    fun `a Stalker channel plays a link the portal mints for it now`() = runTest {
        db.sources().insert(STALKER_SOURCE)
        test.panel.replies["handshake"] = """{"js": {"token": "t"}}"""
        test.panel.replies["create_link"] = """{"js": {"cmd": "ffmpeg http://example.com/play/9.ts?play_token=abc"}}"""
        val id =
            channel(streamUrl = "stalker://2/9", sourceId = STALKER_SOURCE.id, cmd = "ffmpeg http://localhost/ch/9")

        val playable = sources.forWatching(MediaKey.Live(id)) as Playable.Ready

        assertThat(playable.url).isEqualTo("http://example.com/play/9.ts?play_token=abc")
    }

    @Test
    fun `a link the portal won't mint is a failure the viewer can act on, not the marker`() = runTest {
        db.sources().insert(STALKER_SOURCE)
        test.panel.replies["handshake"] = """{"js": {"token": "t"}}"""
        test.panel.replies["create_link"] = "!"
        val key = MediaKey.Live(channel(streamUrl = "stalker://2/9", sourceId = STALKER_SOURCE.id, cmd = "ffmpeg x"))

        val playable = sources.forWatching(key)

        assertThat(playable).isEqualTo(Playable.Failed(key, Playable.Failure.LINK_REFUSED))
        assertThat(PlaybackErrors.describe(Playable.Failure.LINK_REFUSED)).isEqualTo(
            "The portal didn't give a link for this channel. Refresh the channel list, or check the " +
                "MAC address is still authorised.",
        )
    }

    @Test
    fun `a movie plays with its source's user agent, not the app's`() = runTest {
        db.sources().insert(PANEL_SOURCE.copy(userAgent = "Panel/1"))
        db.movies().upsertAll(listOf(movie()))
        val id = db.movies().all().single().id

        val playable = sources.forWatching(MediaKey.Movie(id))

        assertThat(playable).isEqualTo(
            Playable.Ready(
                key = MediaKey.Movie(id),
                url = "http://example.com/movie/u/p/3.mkv",
                userAgent = "Panel/1",
                title = "Drive",
                isLive = false,
            ),
        )
    }

    @Test
    fun `a film's add-on stream plays as that film, with the app's own user agent`() = runTest {
        db.sources().insert(PANEL_SOURCE.copy(userAgent = "Panel/1"))
        db.movies().upsertAll(listOf(movie()))
        val key = MediaKey.AddonStream(db.movies().all().single().id, "https://example.org/addon/drive.mp4")

        val playable = sources.forWatching(key)

        assertThat(playable).isEqualTo(
            Playable.Ready(key, "https://example.org/addon/drive.mp4", "OpenTV/0.1 (Android)", "Drive", isLive = false),
        )
    }

    @Test
    fun `an episode plays with its source's user agent, titled by its season and number`() = runTest {
        db.sources().insert(PANEL_SOURCE.copy(userAgent = "Panel/1"))
        db.episodes().upsertAll(listOf(testEpisode(1, 2)))

        val playable = sources.forWatching(MediaKey.Episode(12))

        assertThat(playable).isEqualTo(
            Playable.Ready(
                key = MediaKey.Episode(12),
                url = "https://example.com/1-2.mkv",
                userAgent = "Panel/1",
                title = "S1E2 · Episode 2",
                isLive = false,
            ),
        )
    }

    @Test
    fun `catch-up plays the programme from the panel's archive, for as long as the guide says it ran`() = runTest {
        db.sources().insert(PANEL_SOURCE.copy(userAgent = "Panel/1"))
        val id = channel(streamUrl = "http://example.com/live/u/p/9.m3u8")
        db.programmes().upsertAll(listOf(news()))
        val key = MediaKey.Catchup(id, NEWS_START)

        val playable = inUtc { sources.forWatching(key) }

        assertThat(playable).isEqualTo(
            Playable.Ready(
                key = key,
                url = "http://example.com/timeshift/u/p/30/2023-11-14:21-00/9.ts",
                userAgent = "Panel/1",
                title = "BBC One — News",
                isLive = false,
            ),
        )
    }

    @Test
    fun `playlists and portals say they have no catch-up rather than play something else`() = runTest {
        val playlist = PANEL_SOURCE.copy(id = 3, name = "Playlist", kind = SourceKind.M3U)
        db.sources().insert(playlist)
        db.programmes().upsertAll(listOf(news()))
        val key = MediaKey.Catchup(channel(streamUrl = "http://example.com/9.ts", sourceId = 3), NEWS_START)

        val playable = sources.forWatching(key)

        assertThat(playable).isEqualTo(Playable.Failed(key, Playable.Failure.NO_CATCHUP))
        assertThat(PlaybackErrors.describe(Playable.Failure.NO_CATCHUP))
            .isEqualTo("This provider doesn't offer catch-up. Only Xtream providers can play past programmes.")
    }

    @Test
    fun `a recording plays from wherever it's stored, following the file while it's still recording`() = runTest {
        val played = listOf(
            "/data/rec/news.ts" to RecordingStatus.COMPLETED,
            "/data/rec/news.ts" to RecordingStatus.RECORDING,
            "smb://nas/rec/news.ts" to RecordingStatus.COMPLETED,
            "smb://nas/rec/news.ts" to RecordingStatus.RECORDING,
            "content://usb/doc/news.ts" to RecordingStatus.RECORDING,
        ).map { (filePath, status) ->
            val id = db.recordings().insert(recording(filePath, status))
            (sources.forWatching(MediaKey.Recording(id)) as Playable.Ready).url.replace("$id", "<id>")
        }

        assertThat(played).containsExactly(
            "file:///data/rec/news.ts",
            "optvrec://<id>",
            "smb://nas/rec/news.ts",
            "optvrec://<id>",
            "content://usb/doc/news.ts",
        ).inOrder()
    }

    @Test
    fun `a recording plays with the user agent it was captured with, titled as the library shows it`() = runTest {
        val id = db.recordings().insert(recording("/data/rec/news.ts", RecordingStatus.COMPLETED))

        val playable = sources.forWatching(MediaKey.Recording(id))

        assertThat(playable).isEqualTo(
            Playable.Ready(
                key = MediaKey.Recording(id),
                url = "file:///data/rec/news.ts",
                userAgent = "Panel/1",
                title = "News",
                isLive = false,
            ),
        )
    }

    @Test
    fun `a recording captures its channel's continuous stream rather than the HLS playlist`() = runTest {
        db.sources().insert(PANEL_SOURCE.copy(userAgent = "Panel/2"))
        val channelId = channel(streamUrl = "http://example.com/live/u/p/9.m3u8")
        val id = db.recordings().insert(recording("/data/rec/news.ts", RecordingStatus.SCHEDULED, channelId))

        val capture = sources.forRecording(MediaKey.Recording(id))

        assertThat(capture).isEqualTo(
            Playable.Ready(
                key = MediaKey.Recording(id),
                url = "http://example.com/live/u/p/9.ts",
                userAgent = "Panel/2",
                title = "News",
                isLive = true,
            ),
        )
    }

    @Test
    fun `a recording moved to another provider to avoid a clash captures that provider's copy of the channel`() =
        runTest {
            db.sources().insert(PANEL_SOURCE)
            db.sources().insert(
                PANEL_SOURCE.copy(id = 3, name = "Playlist", kind = SourceKind.M3U, userAgent = "Playlist/1"),
            )
            db.channels().upsertAll(
                listOf(
                    bbcOne(sourceId = PANEL_SOURCE.id, streamUrl = "http://example.com/live/u/p/9.m3u8"),
                    bbcOne(sourceId = 3, streamUrl = "http://example.com/bbc1.ts"),
                ),
            )
            val picked = db.channels().allForMatching().single { it.sourceId == PANEL_SOURCE.id }.id
            val id = db.recordings().insert(
                recording("/data/rec/news.ts", RecordingStatus.SCHEDULED, picked).copy(sourceId = 3),
            )

            val capture = sources.forRecording(MediaKey.Recording(id)) as Playable.Ready

            assertThat(capture.url).isEqualTo("http://example.com/bbc1.ts")
            assertThat(capture.userAgent).isEqualTo("Playlist/1")
        }

    @Test
    fun `a Stalker recording is given a freshly minted link each time it asks`() = runTest {
        db.sources().insert(STALKER_SOURCE)
        test.panel.replies["handshake"] = """{"js": {"token": "t"}}"""
        val channelId = channel(streamUrl = "stalker://2/9", sourceId = STALKER_SOURCE.id, cmd = "ffmpeg x")
        val id = db.recordings().insert(
            recording("/data/rec/news.ts", RecordingStatus.RECORDING, channelId).copy(sourceId = STALKER_SOURCE.id),
        )

        val links = listOf("a", "b").map { token ->
            test.panel.replies["create_link"] =
                """{"js": {"cmd": "ffmpeg http://example.com/play/9.ts?play_token=$token"}}"""
            (sources.forRecording(MediaKey.Recording(id)) as Playable.Ready).url
        }

        assertThat(links).containsExactly(
            "http://example.com/play/9.ts?play_token=a",
            "http://example.com/play/9.ts?play_token=b",
        ).inOrder()
    }

    @Test
    fun `a recording whose channel has left the catalogue captures from the link it last knew`() = runTest {
        db.sources().insert(PANEL_SOURCE.copy(userAgent = "Panel/2"))
        val id = db.recordings().insert(
            recording("/data/rec/news.ts", RecordingStatus.SCHEDULED, channelId = 404)
                .copy(streamUrl = "http://example.com/live/u/p/9.m3u8"),
        )

        val capture = sources.forRecording(MediaKey.Recording(id)) as Playable.Ready

        assertThat(capture.url).isEqualTo("http://example.com/live/u/p/9.ts")
        assertThat(capture.userAgent).isEqualTo("Panel/1")
    }

    @Test
    fun `a Stalker recording whose channel has left the catalogue fails, since its marker never plays`() = runTest {
        db.sources().insert(STALKER_SOURCE)
        val id = db.recordings().insert(
            recording("/data/rec/news.ts", RecordingStatus.SCHEDULED, channelId = 404)
                .copy(sourceId = STALKER_SOURCE.id, streamUrl = "stalker://2/9"),
        )
        val key = MediaKey.Recording(id)

        assertThat(sources.forRecording(key)).isEqualTo(Playable.Failed(key, Playable.Failure.GONE))
        assertThat(PlaybackErrors.describe(Playable.Failure.GONE))
            .isEqualTo("This is no longer in the provider's list. Refresh the channel list and try again.")
    }

    private fun recording(filePath: String, status: RecordingStatus, channelId: Long = 1) = Recording(
        channelId = channelId,
        sourceId = PANEL_SOURCE.id,
        channelName = "BBC One",
        title = "News",
        filePath = filePath,
        streamUrl = "http://example.com/live/u/p/9.ts",
        userAgent = "Panel/1",
        status = status,
    )

    @Test
    fun `catch-up for a programme the guide has since dropped is gone`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        val key = MediaKey.Catchup(channel(streamUrl = "http://example.com/live/u/p/9.m3u8"), NEWS_START)

        assertThat(sources.forWatching(key)).isEqualTo(Playable.Failed(key, Playable.Failure.GONE))
    }

    private fun news() = Programme(
        feedId = 1,
        epgChannelId = "bbc1",
        startUtcMillis = NEWS_START,
        endUtcMillis = NEWS_START + 30 * 60_000L,
        title = "News",
    )

    /** The panel reads a catch-up start in its own time; these tests put the device in UTC. */
    private suspend fun <T> inUtc(block: suspend () -> T): T {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        return try {
            block()
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    private fun movie() = Movie(
        sourceId = PANEL_SOURCE.id,
        streamId = "3",
        name = "Drive",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 2011,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "http://example.com/movie/u/p/3.mkv",
    )

    private fun bbcOne(sourceId: Long, streamUrl: String) = Channel(
        sourceId = sourceId,
        streamId = "9",
        name = "BBC One",
        groupKey = "bbcone",
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = 1,
        streamUrl = streamUrl,
    )

    private suspend fun channel(streamUrl: String, sourceId: Long = PANEL_SOURCE.id, cmd: String? = null): Long {
        db.channels().upsertAll(
            listOf(
                Channel(
                    sourceId = sourceId,
                    streamId = "9",
                    name = "UK| BBC ONE",
                    displayName = "BBC One",
                    categoryId = null,
                    logoUrl = null,
                    epgChannelId = "bbc1",
                    number = 1,
                    streamUrl = streamUrl,
                    cmd = cmd,
                ),
            ),
        )
        return db.channels().allForMatching().single().id
    }
}

/** 2023-11-14 21:00 UTC. */
private const val NEWS_START = 1_699_995_600_000L
