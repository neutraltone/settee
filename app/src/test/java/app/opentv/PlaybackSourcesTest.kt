/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ReportedSource
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
import app.opentv.data.repo.RecordedOn
import app.opentv.player.PlaybackErrors
import com.google.common.truth.Truth.assertThat
import java.util.TimeZone
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
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val sources =
        PlaybackSources(db, XtreamApi(test.panel.http), StalkerApi(test.panel.http), test.lineup, clock = { NOW })

    @After
    fun close() = test.close()

    @Test
    fun `an Xtream channel plays its stored link with the source's user agent`() = testing {
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
                source = ReportedSource.XTREAM,
            ),
        )
    }

    @Test
    fun `a playlist channel plays its stored link with the source's user agent`() = testing {
        db.sources().insert(PANEL_SOURCE.copy(kind = SourceKind.M3U, userAgent = "Playlist/1"))
        val id = channel(streamUrl = "http://example.com/9.ts")

        val playable = sources.forWatching(MediaKey.Live(id)) as Playable.Ready

        assertThat(playable.url).isEqualTo("http://example.com/9.ts")
        assertThat(playable.userAgent).isEqualTo("Playlist/1")
    }

    @Test
    fun `a Stalker channel plays a link the portal mints for it now`() = testing {
        db.sources().insert(STALKER_SOURCE)
        test.panel.replies["handshake"] = """{"js": {"token": "t"}}"""
        test.panel.replies["create_link"] = """{"js": {"cmd": "ffmpeg http://example.com/play/9.ts?play_token=abc"}}"""
        val id =
            channel(streamUrl = "stalker://2/9", sourceId = STALKER_SOURCE.id, cmd = "ffmpeg http://localhost/ch/9")

        val playable = sources.forWatching(MediaKey.Live(id)) as Playable.Ready

        assertThat(playable.url).isEqualTo("http://example.com/play/9.ts?play_token=abc")
    }

    @Test
    fun `a link the portal won't mint is a failure the viewer can act on, not the marker`() = testing {
        db.sources().insert(STALKER_SOURCE)
        test.panel.replies["handshake"] = """{"js": {"token": "t"}}"""
        test.panel.replies["create_link"] = "!"
        val key = MediaKey.Live(channel(streamUrl = "stalker://2/9", sourceId = STALKER_SOURCE.id, cmd = "ffmpeg x"))

        val playable = sources.forWatching(key)

        assertThat(playable).isEqualTo(Playable.Failed(key, Playable.Failure.LINK_REFUSED, title = "BBC One"))
        assertThat(message(Playable.Failure.LINK_REFUSED)).isEqualTo(
            "Your provider didn't give a link for this channel. Try refreshing your provider in Settings.",
        )
    }

    @Test
    fun `a portal that can't be reached for a link says so, not that the link was refused`() = testing {
        db.sources().insert(STALKER_SOURCE)
        test.panel.replies["handshake"] = "!host"
        val key = MediaKey.Live(channel(streamUrl = "stalker://2/9", sourceId = STALKER_SOURCE.id, cmd = "ffmpeg x"))

        val playable = sources.forWatching(key) as Playable.Failed

        assertThat(playable.failure).isEqualTo(Playable.Failure.UNREACHABLE)
        assertThat(playable.title).isEqualTo("BBC One")
        val shown = PlaybackErrors.describe(context, playable.failure, playable.cause, onTelevision = true)
        val cantReach = "Can't reach your provider. Check this TV is online and the address is right."
        assertThat(shown.sentence).isEqualTo(cantReach)
        assertThat(shown.detail).isEqualTo("UnknownHost")
    }

    @Test
    fun `a portal that stops responding when asked for a link says so`() = testing {
        db.sources().insert(STALKER_SOURCE)
        test.panel.replies["handshake"] = """{"js": {"token": "t"}}"""
        test.panel.replies["create_link"] = "!timeout"
        val key = MediaKey.Live(channel(streamUrl = "stalker://2/9", sourceId = STALKER_SOURCE.id, cmd = "ffmpeg x"))

        val playable = sources.forWatching(key) as Playable.Failed

        assertThat(PlaybackErrors.describe(context, playable.failure, playable.cause, onTelevision = true).sentence)
            .isEqualTo("Your provider stopped responding. It may be busy, so try again in a moment.")
    }

    @Test
    fun `a movie plays with its source's user agent, not the app's`() = testing {
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
                source = ReportedSource.XTREAM,
                isLive = false,
            ),
        )
    }

    @Test
    fun `a film's add-on stream plays as that film, with the app's own user agent`() = testing {
        db.sources().insert(PANEL_SOURCE.copy(userAgent = "Panel/1"))
        db.movies().upsertAll(listOf(movie()))
        val key = MediaKey.AddonStream(
            MediaKey.Movie(db.movies().all().single().id),
            "https://example.org/addon/drive.mp4",
        )

        val playable = sources.forWatching(key)

        assertThat(playable).isEqualTo(
            Playable.Ready(
                key,
                "https://example.org/addon/drive.mp4",
                "OpenTV/0.1 (Android)",
                "Drive",
                isLive = false,
                source = ReportedSource.STREMIO,
            ),
        )
    }

    @Test
    fun `a film's add-on stream goes with the add-on's headers and user agent, never the provider's`() = testing {
        db.sources().insert(PANEL_SOURCE.copy(userAgent = "Panel/1"))
        db.movies().upsertAll(listOf(movie()))
        val key = MediaKey.AddonStream(
            MediaKey.Movie(db.movies().all().single().id),
            "https://example.org/addon/drive.mp4",
            mapOf("Referer" to "https://example.org/", "user-agent" to "AddonPlayer/2"),
        )

        val playable = sources.forWatching(key)

        assertThat(playable).isEqualTo(
            Playable.Ready(
                key,
                "https://example.org/addon/drive.mp4",
                "AddonPlayer/2",
                "Drive",
                isLive = false,
                source = ReportedSource.STREMIO,
                requestHeaders = mapOf("Referer" to "https://example.org/"),
            ),
        )
    }

    @Test
    fun `an episode plays with its source's user agent, titled by its season and number`() = testing {
        db.sources().insert(PANEL_SOURCE.copy(userAgent = "Panel/1"))
        db.episodes().upsertAll(listOf(testEpisode(1, 2)))

        val playable = sources.forWatching(MediaKey.Episode(12))

        assertThat(playable).isEqualTo(
            Playable.Ready(
                key = MediaKey.Episode(12),
                url = "https://example.com/1-2.mkv",
                userAgent = "Panel/1",
                source = ReportedSource.XTREAM,
                title = "S1E2 · Episode 2",
                isLive = false,
            ),
        )
    }

    @Test
    fun `an episode's add-on stream goes with the add-on's headers, not the provider's, kept on the episode`() =
        testing {
            db.sources().insert(PANEL_SOURCE.copy(userAgent = "Panel/1"))
            db.episodes().upsertAll(listOf(testEpisode(1, 2)))
            val key = MediaKey.AddonStream(
                MediaKey.Episode(12),
                "https://example.org/addon/s1e2.mp4",
                mapOf("Referer" to "https://example.org/"),
            )

            val playable = sources.forWatching(key)

            assertThat(key.stored).isEqualTo("ep:12")
            assertThat(playable).isEqualTo(
                Playable.Ready(
                    key,
                    "https://example.org/addon/s1e2.mp4",
                    "OpenTV/0.1 (Android)",
                    "S1E2 · Episode 2",
                    isLive = false,
                    source = ReportedSource.STREMIO,
                    requestHeaders = mapOf("Referer" to "https://example.org/"),
                ),
            )
        }

    @Test
    fun `catch-up plays the programme from the panel's archive, for as long as the guide says it ran`() = testing {
        db.sources().insert(PANEL_SOURCE.copy(userAgent = "Panel/1"))
        val id = channel(streamUrl = "http://example.com/live/u/p/9.m3u8")
        db.programmes().insertOrReplace(listOf(news()))
        val key = MediaKey.Catchup(id, NEWS_START)

        val playable = inUtc { sources.forWatching(key) }

        assertThat(playable).isEqualTo(
            Playable.Ready(
                key = key,
                url = "http://example.com/timeshift/u/p/30/2023-11-14:21-00/9.ts",
                source = ReportedSource.XTREAM,
                userAgent = "Panel/1",
                title = "BBC One · News",
                isLive = false,
            ),
        )
    }

    @Test
    fun `catch-up from a provider whose address isn't one says so instead of crashing`() = testing {
        db.sources().insert(PANEL_SOURCE.copy(url = "not an address"))
        val id = channel(streamUrl = "http://example.com/live/u/p/9.m3u8")
        db.programmes().insertOrReplace(listOf(news()))
        val key = MediaKey.Catchup(id, NEWS_START)

        val playable = inUtc { sources.forWatching(key) }

        assertThat(playable).isEqualTo(Playable.Failed(key, Playable.Failure.BAD_ADDRESS, ""))
    }

    @Test
    fun `catch-up asks the panel for the programme's start in the panel's own time zone`() = testing {
        // November: London is on GMT and Amsterdam an hour ahead, so 21:00 UTC is 22:00 to the panel.
        db.sources().insert(PANEL_SOURCE.copy(serverTimeZone = "Europe/Amsterdam"))
        val id = channel(streamUrl = "http://example.com/live/u/p/9.m3u8")
        db.programmes().insertOrReplace(listOf(news()))

        val key = MediaKey.Catchup(id, NEWS_START)

        val playable = inZone("Europe/London") { sources.forWatching(key) } as Playable.Ready

        assertThat(playable.url).isEqualTo("http://example.com/timeshift/u/p/30/2023-11-14:22-00/9.ts")
    }

    @Test
    fun `catch-up from a panel that names its time zone by abbreviation asks in that zone`() = testing {
        // PHP, which Xtream panels run on, accepts "EST" as a zone: five hours behind UTC all year.
        db.sources().insert(PANEL_SOURCE.copy(serverTimeZone = "EST"))
        val id = channel(streamUrl = "http://example.com/live/u/p/9.m3u8")
        db.programmes().insertOrReplace(listOf(news()))

        val key = MediaKey.Catchup(id, NEWS_START)

        val playable = inZone("Europe/London") { sources.forWatching(key) } as Playable.Ready

        assertThat(playable.url).isEqualTo("http://example.com/timeshift/u/p/30/2023-11-14:16-00/9.ts")
    }

    @Test
    fun `catch-up from a panel that gives no time zone asks in the device's`() = testing {
        db.sources().insert(PANEL_SOURCE.copy(serverTimeZone = null))
        val id = channel(streamUrl = "http://example.com/live/u/p/9.m3u8")
        db.programmes().insertOrReplace(listOf(news()))

        val key = MediaKey.Catchup(id, NEWS_START)

        val playable = inZone("America/New_York") { sources.forWatching(key) } as Playable.Ready

        assertThat(playable.url).isEqualTo("http://example.com/timeshift/u/p/30/2023-11-14:16-00/9.ts")
    }

    @Test
    fun `catch-up from a panel whose time zone isn't one asks in the device's, not GMT`() = testing {
        db.sources().insert(PANEL_SOURCE.copy(serverTimeZone = "Not/AZone"))
        val id = channel(streamUrl = "http://example.com/live/u/p/9.m3u8")
        db.programmes().insertOrReplace(listOf(news()))

        val key = MediaKey.Catchup(id, NEWS_START)

        val playable = inZone("America/New_York") { sources.forWatching(key) } as Playable.Ready

        assertThat(playable.url).isEqualTo("http://example.com/timeshift/u/p/30/2023-11-14:16-00/9.ts")
    }

    @Test
    fun `catch-up plays the programme from the guide the guide shows for the channel`() = testing {
        db.sources().insert(PANEL_SOURCE.copy(userAgent = "Panel/1"))
        val hd = channel(streamUrl = "http://example.com/live/u/p/9.m3u8")
        // The Channel's FHD Quality is its primary, so its guide is the one the guide draws.
        db.channels().upsertAll(
            listOf(
                Channel(
                    sourceId = PANEL_SOURCE.id,
                    streamId = "10",
                    name = "UK| BBC ONE FHD",
                    displayName = "BBC One",
                    qualityRank = 300,
                    categoryId = null,
                    logoUrl = null,
                    epgChannelId = "bbc1.fhd",
                    number = 1,
                    streamUrl = "http://example.com/live/u/p/10.m3u8",
                ),
            ),
        )
        db.channels().everyRow().forEach { db.channels().upsertAll(listOf(it.copy(groupKey = "uk:bbc1"))) }
        db.programmes().insertOrReplace(listOf(news(), news().copy(epgChannelId = "bbc1.fhd", title = "Breakfast")))

        val playable = inUtc { sources.forWatching(MediaKey.Catchup(hd, NEWS_START)) } as Playable.Ready

        assertThat(playable.title).isEqualTo("BBC One · Breakfast")
    }

    @Test
    fun `playlists and portals say they have no catch-up rather than play something else`() = testing {
        val playlist = PANEL_SOURCE.copy(id = 3, name = "Playlist", kind = SourceKind.M3U)
        db.sources().insert(playlist)
        db.programmes().insertOrReplace(listOf(news()))
        val key = MediaKey.Catchup(channel(streamUrl = "http://example.com/9.ts", sourceId = 3), NEWS_START)

        val playable = sources.forWatching(key)

        assertThat(playable).isEqualTo(Playable.Failed(key, Playable.Failure.NO_CATCHUP))
        assertThat(message(Playable.Failure.NO_CATCHUP))
            .isEqualTo("Your provider doesn't offer catch-up, so past programmes can't be played.")
    }

    @Test
    fun `a recording plays from wherever it's stored, following the file while it's still recording`() = testing {
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
    fun `a finished recording says where it's kept, and one still being written doesn't`() = testing {
        val recordedOn = listOf(
            "/data/rec/news.ts" to RecordingStatus.COMPLETED,
            "smb://nas/rec/news.ts" to RecordingStatus.COMPLETED,
            "content://usb/doc/news.ts" to RecordingStatus.COMPLETED,
            "smb://nas/rec/news.ts" to RecordingStatus.RECORDING,
        ).map { (filePath, status) ->
            val id = db.recordings().insert(recording(filePath, status))
            (sources.forWatching(MediaKey.Recording(id)) as Playable.Ready).recordedOn
        }

        assertThat(recordedOn).containsExactly(RecordedOn.DEVICE, RecordedOn.NAS, RecordedOn.USB, null).inOrder()
    }

    @Test
    fun `a recording plays with the user agent it was captured with, titled as the library shows it`() = testing {
        val id = db.recordings().insert(recording("/data/rec/news.ts", RecordingStatus.COMPLETED))

        val playable = sources.forWatching(MediaKey.Recording(id))

        assertThat(playable).isEqualTo(
            Playable.Ready(
                key = MediaKey.Recording(id),
                url = "file:///data/rec/news.ts",
                userAgent = "Panel/1",
                title = "News",
                isLive = false,
                recordedOn = RecordedOn.DEVICE,
            ),
        )
    }

    @Test
    fun `a recording captures its channel's continuous stream rather than the HLS playlist`() = testing {
        db.sources().insert(PANEL_SOURCE.copy(userAgent = "Panel/2"))
        val channelId = channel(streamUrl = "http://example.com/live/u/p/9.m3u8")
        val id = db.recordings().insert(recording("/data/rec/news.ts", RecordingStatus.SCHEDULED, channelId))

        val capture = sources.forRecording(MediaKey.Recording(id))

        assertThat(capture).isEqualTo(
            Playable.Ready(
                key = MediaKey.Recording(id),
                source = ReportedSource.XTREAM,
                url = "http://example.com/live/u/p/9.ts",
                userAgent = "Panel/2",
                title = "News",
                isLive = true,
            ),
        )
    }

    @Test
    fun `a recording moved to another provider to avoid a clash captures that provider's copy of the channel`() =
        testing {
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
            val picked = db.channels().everyRow().single { it.sourceId == PANEL_SOURCE.id }.id
            val id = db.recordings().insert(
                recording("/data/rec/news.ts", RecordingStatus.SCHEDULED, picked).copy(sourceId = 3),
            )

            val capture = sources.forRecording(MediaKey.Recording(id)) as Playable.Ready

            assertThat(capture.url).isEqualTo("http://example.com/bbc1.ts")
            assertThat(capture.userAgent).isEqualTo("Playlist/1")
        }

    @Test
    fun `a clash recording whose other copy has gone captures the channel the viewer picked`() = testing {
        db.sources().insert(PANEL_SOURCE)
        db.sources().insert(STALKER_SOURCE)
        db.channels().upsertAll(
            listOf(bbcOne(sourceId = PANEL_SOURCE.id, streamUrl = "http://example.com/live/u/p/9.m3u8")),
        )
        val picked = db.channels().everyRow().single().id
        val id = db.recordings().insert(
            recording("/data/rec/news.ts", RecordingStatus.SCHEDULED, picked).copy(sourceId = STALKER_SOURCE.id),
        )

        val capture = sources.forRecording(MediaKey.Recording(id)) as Playable.Ready

        assertThat(capture.url).isEqualTo("http://example.com/live/u/p/9.ts")
    }

    @Test
    fun `a Stalker recording is given a freshly minted link each time it asks`() = testing {
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
    fun `a recording whose channel has left the catalogue captures from the link it last knew`() = testing {
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
    fun `a Stalker recording whose channel has left the catalogue fails, since its marker never plays`() = testing {
        db.sources().insert(STALKER_SOURCE)
        val id = db.recordings().insert(
            recording("/data/rec/news.ts", RecordingStatus.SCHEDULED, channelId = 404)
                .copy(sourceId = STALKER_SOURCE.id, streamUrl = "stalker://2/9"),
        )
        val key = MediaKey.Recording(id)

        assertThat(sources.forRecording(key)).isEqualTo(Playable.Failed(key, Playable.Failure.GONE))
        assertThat(message(Playable.Failure.GONE))
            .isEqualTo("This isn't on your provider any more. Try refreshing your provider in Settings.")
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
    fun `catch-up for a programme the guide has since dropped is gone`() = testing {
        db.sources().insert(PANEL_SOURCE)
        val key = MediaKey.Catchup(channel(streamUrl = "http://example.com/live/u/p/9.m3u8"), NEWS_START)

        assertThat(sources.forWatching(key)).isEqualTo(Playable.Failed(key, Playable.Failure.GONE))
    }

    @Test
    fun `a portal that won't take the MAC is a refused link, not a crash`() = testing {
        db.sources().insert(STALKER_SOURCE.copy(macAddress = ""))
        val key = MediaKey.Live(channel(streamUrl = "stalker://2/9", sourceId = STALKER_SOURCE.id, cmd = "ffmpeg x"))

        assertThat(sources.forWatching(key)).isEqualTo(Playable.Failed(key, Playable.Failure.LINK_REFUSED, "BBC One"))
    }

    @Test
    fun `catch-up finds its programme when another feed's entry overlaps its start`() = testing {
        db.sources().insert(PANEL_SOURCE)
        val id = channel(streamUrl = "http://example.com/live/u/p/9.m3u8")
        val overlapping = news().copy(feedId = 2, startUtcMillis = NEWS_START - 30 * 60_000L, title = "Quiz")
        db.programmes().insertOrReplace(listOf(overlapping, news()))

        val playable = inUtc { sources.forWatching(MediaKey.Catchup(id, NEWS_START)) } as Playable.Ready

        assertThat(playable.title).isEqualTo("BBC One · News")
    }

    @Test
    fun `a booking that is due follows its file even before the recorder has marked it started`() = testing {
        val due = recording("/data/rec/news.ts", RecordingStatus.SCHEDULED)
            .copy(scheduledStartMillis = NOW - 1_000, scheduledEndMillis = NOW + 60_000)
        val id = db.recordings().insert(due)
        val later = db.recordings().insert(
            due.copy(
                scheduledStartMillis = NOW + 60_000,
                scheduledEndMillis =
                NOW + 120_000,
            ),
        )

        assertThat((sources.forWatching(MediaKey.Recording(id)) as Playable.Ready).url).isEqualTo("optvrec://$id")
        assertThat(sources.isGrowing(MediaKey.Recording(id))).isTrue()
        assertThat(sources.isGrowing(MediaKey.Recording(later))).isFalse()
    }

    @Test
    fun `only a recording still being written grows`() = testing {
        db.sources().insert(PANEL_SOURCE)
        db.movies().upsertAll(listOf(movie()))
        val finished = db.recordings().insert(recording("/data/rec/news.ts", RecordingStatus.COMPLETED))

        assertThat(sources.isGrowing(MediaKey.Movie(db.movies().all().single().id))).isFalse()
        assertThat(sources.isGrowing(MediaKey.Recording(finished))).isFalse()
    }

    private fun message(failure: Playable.Failure) =
        PlaybackErrors.describe(context, failure, onTelevision = false).sentence

    private fun news() = Programme(
        feedId = 1,
        epgChannelId = "bbc1",
        startUtcMillis = NEWS_START,
        endUtcMillis = NEWS_START + 30 * 60_000L,
        title = "News",
    )

    /** The panel reads a catch-up start in its own time; these tests put the device in UTC. */
    private suspend fun <T> inUtc(block: suspend () -> T): T = inZone("UTC", block)

    /** Runs [block] with the device's time zone set to [id]. */
    private suspend fun <T> inZone(id: String, block: suspend () -> T): T {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(id))
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
        return db.channels().everyRow().single().id
    }
}

/** 2023-11-14 21:00 UTC. */
private const val NEWS_START = 1_699_995_600_000L

/** Where these tests' clock stands. */
private const val NOW = 1_700_000_000_000L
