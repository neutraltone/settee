/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.net.Uri
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Episode
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.shownName
import app.opentv.data.parser.displayTitle
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.Playable.Failure
import app.opentv.recording.RecordingStorage
import app.opentv.recording.SmbClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What a player or the recorder is asked to play: one title, by its local id. [stored] is the form
 * kept in playback_positions, carried by sync and matched in SQL (`Daos.kt`), so it never changes;
 * this is the one place it is built and read.
 */
sealed interface MediaKey {
    val stored: String

    /** A live channel. Never resumed, so never stored; [stored] exists only to name it. */
    data class Live(val channelId: Long) : MediaKey {
        override val stored get() = "live:$channelId"
    }

    data class Movie(val movieId: Long) : MediaKey {
        override val stored get() = "$MOVIE:$movieId"
    }

    /**
     * A stream a Stremio add-on offered for [movieId]. Its [url] isn't in the catalogue, so the key
     * carries it; progress is kept on the film, the same as playing it from the panel.
     */
    data class AddonStream(val movieId: Long, val url: String) : MediaKey {
        override val stored get() = "$MOVIE:$movieId"
    }

    data class Episode(val episodeId: Long) : MediaKey {
        override val stored get() = "$EPISODE:$episodeId"
    }

    /** A finished programme on [channelId], played back from the provider's archive. */
    data class Catchup(val channelId: Long, val startUtcMillis: Long) : MediaKey {
        override val stored get() = "$CATCHUP:$channelId:$startUtcMillis"
    }

    data class Recording(val recordingId: Long) : MediaKey {
        override val stored get() = "$RECORDING:$recordingId"
    }

    companion object {
        const val MOVIE = "movie"
        const val EPISODE = "ep"
        const val CATCHUP = "catchup"
        const val RECORDING = "rec"

        /** The key [stored] names, or null when it names none. */
        fun parse(stored: String): MediaKey? {
            val kind = stored.substringBefore(':')
            val ids = stored.substringAfter(':', missingDelimiterValue = "").split(':').map { it.toLongOrNull() }
            val id = ids.first() ?: return null
            return when (kind) {
                MOVIE -> Movie(id).takeIf { ids.size == 1 }
                EPISODE -> Episode(id).takeIf { ids.size == 1 }
                RECORDING -> Recording(id).takeIf { ids.size == 1 }
                CATCHUP -> ids.getOrNull(1)?.takeIf { ids.size == 2 }?.let { Catchup(id, it) }
                else -> null
            }
        }
    }
}

/** What [PlaybackSources] makes of a [MediaKey]: something to play, or why there isn't. */
sealed interface Playable {
    data class Ready(
        val key: MediaKey,
        val url: String,
        val userAgent: String,
        val title: String,
        val isLive: Boolean,
    ) : Playable

    /** [app.opentv.player.PlaybackErrors] says what went wrong and what to try. */
    data class Failed(val key: MediaKey, val failure: Failure) : Playable

    enum class Failure {
        /** The Stalker portal answered create_link with nothing, or not at all. */
        LINK_REFUSED,

        /** Only Xtream panels have an archive; playlists and portals don't. */
        NO_CATCHUP,

        /** The title, or the channel it comes from, is no longer in the catalogue. */
        GONE,
    }
}

/**
 * The one place a title becomes something playable: its URL (minted now for a Stalker channel),
 * its source's user agent and its title.
 */
class PlaybackSources(
    private val db: OpenTvDatabase,
    private val xtreamApi: XtreamApi,
    private val stalkerApi: StalkerApi,
) {

    suspend fun forWatching(key: MediaKey): Playable = resolving(key) {
        when (key) {
            is MediaKey.Live -> live(key)
            is MediaKey.Movie -> movie(key)
            is MediaKey.AddonStream -> addonStream(key)
            is MediaKey.Episode -> episode(key)
            is MediaKey.Catchup -> catchup(key)
            is MediaKey.Recording -> recording(key)
        }
    }

    /**
     * What the recorder pulls for [key]: its channel's stream, resolved anew each time it is asked, so
     * a Stalker link that has expired by a reconnect, or by a booking's start, is minted again.
     */
    suspend fun forRecording(key: MediaKey.Recording): Playable = resolving(key) {
        val recording = db.recordings().byId(key.recordingId).or(Failure.GONE)
        val channel = capturedChannel(recording)
        if (channel == null) {
            lastKnown(key, recording)
        } else {
            live(MediaKey.Live(channel.id)).let {
                it.copy(key = key, url = recordUrlFor(it.url), title = recording.title)
            }
        }
    }

    /**
     * The channel [recording] pulls from. It keeps the channel the viewer picked, but a clash with
     * another booking can move the capture to the same channel on another provider: the row's
     * source says which.
     */
    private suspend fun capturedChannel(recording: Recording): Channel? {
        val picked = db.channels().byId(recording.channelId)
        return if (picked == null || picked.sourceId == recording.sourceId || picked.groupKey.isEmpty()) {
            picked
        } else {
            db.channels().variantsInGroup(picked.groupKey)
                .filter { it.sourceId == recording.sourceId }
                .maxByOrNull { it.qualityRank }
        }
    }

    /**
     * The link and user agent [recording] last captured with, for a channel the provider has since
     * dropped. A Stalker row only ever held the marker, so there is nothing to fall back on.
     */
    private suspend fun lastKnown(key: MediaKey.Recording, recording: Recording): Playable.Ready {
        if (db.sources().byId(recording.sourceId)?.kind == SourceKind.STALKER) throw Unplayable(Failure.GONE)
        return Playable.Ready(
            key,
            recordUrlFor(recording.streamUrl),
            recording.userAgent,
            recording.title,
            isLive = true,
        )
    }

    private suspend fun live(key: MediaKey.Live): Playable.Ready {
        val channel = db.channels().byId(key.channelId).or(Failure.GONE)
        val source = db.sources().byId(channel.sourceId)
        // The marker a Stalker channel stores is never playable, so a refused mint is a failure:
        // handing the marker on would show a player error that says nothing about the portal.
        val url = if (source?.kind == SourceKind.STALKER) {
            stalkerApi.createLink(source, channel.cmd.orEmpty()).or(Failure.LINK_REFUSED)
        } else {
            channel.streamUrl
        }
        return Playable.Ready(key, url, userAgentOf(source), channel.shownName, isLive = true)
    }

    private suspend fun movie(key: MediaKey.Movie): Playable.Ready {
        val movie = db.movies().byId(key.movieId).or(Failure.GONE)
        val source = db.sources().byId(movie.sourceId)
        return Playable.Ready(key, movie.streamUrl, userAgentOf(source), movie.displayTitle, isLive = false)
    }

    /** An add-on's stream isn't the panel's, so the panel's user agent has no business on it. */
    private suspend fun addonStream(key: MediaKey.AddonStream): Playable.Ready {
        val movie = db.movies().byId(key.movieId).or(Failure.GONE)
        return Playable.Ready(key, key.url, Source.DEFAULT_USER_AGENT, movie.displayTitle, isLive = false)
    }

    private suspend fun episode(key: MediaKey.Episode): Playable.Ready {
        val episode = db.episodes().byId(key.episodeId).or(Failure.GONE)
        val source = db.sources().byId(episode.sourceId)
        return Playable.Ready(key, episode.streamUrl, userAgentOf(source), episode.playerTitle, isLive = false)
    }

    private suspend fun catchup(key: MediaKey.Catchup): Playable.Ready {
        val channel = db.channels().byId(key.channelId).or(Failure.GONE)
        val source = db.sources().byId(channel.sourceId).or(Failure.GONE)
        if (source.kind != SourceKind.XTREAM) throw Unplayable(Failure.NO_CATCHUP)
        // The archive wants the programme's length, which only the guide knows.
        val programme = channel.epgCandidates.firstNotNullOfOrNull { epgId ->
            db.programmes().upcoming(epgId, key.startUtcMillis, limit = 1)
                .firstOrNull { it.startUtcMillis == key.startUtcMillis }
        }.or(Failure.GONE)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(programme.durationMillis).toInt()
        val url = xtreamApi.catchupUrl(source, channel.streamId, key.startUtcMillis, minutes)
        return Playable.Ready(
            key,
            url,
            userAgentOf(source),
            "${channel.shownName} — ${programme.title}",
            isLive = false,
        )
    }

    private suspend fun recording(key: MediaKey.Recording): Playable.Ready {
        val recording = db.recordings().byId(key.recordingId).or(Failure.GONE)
        val path = recording.filePath
        // One still being written is followed through the tail-reading optvrec:// source, so it can be
        // watched as it records with no second connection to the provider. A USB document can't be
        // followed that way, so it plays only what is there when it opens.
        val growable = !RecordingStorage.isContent(path) && !RecordingStorage.isUsbPlaceholder(path)
        val url = when {
            recording.status == RecordingStatus.RECORDING && growable -> "optvrec://${recording.id}"
            SmbClient.isSmb(path) || RecordingStorage.isContent(path) -> path
            else -> Uri.fromFile(File(path)).toString()
        }
        return Playable.Ready(key, url, recording.userAgent, recording.title, isLive = false)
    }
}

/** Why a title can't be played, thrown from deep in a lookup and turned into [Playable.Failed]. */
private class Unplayable(val failure: Failure) : Exception()

private fun <T : Any> T?.or(failure: Failure): T = this ?: throw Unplayable(failure)

private inline fun resolving(key: MediaKey, resolve: () -> Playable.Ready): Playable = try {
    resolve()
} catch (e: Unplayable) {
    Playable.Failed(key, e.failure)
}

/**
 * Live Xtream URLs are stored as `.m3u8` (HLS) for playback, but the raw `.ts` MPEG-TS variant
 * is a single continuous body that captures to a directly-playable file with no segment
 * stitching. Other URL shapes are recorded verbatim.
 */
private fun recordUrlFor(streamUrl: String): String =
    if (streamUrl.endsWith(".m3u8")) streamUrl.removeSuffix(".m3u8") + ".ts" else streamUrl

private fun userAgentOf(source: Source?) = source?.userAgent ?: Source.DEFAULT_USER_AGENT

/** What the player calls an episode, wherever it was started from. */
val Episode.playerTitle: String get() = "S${season}E$episodeNumber · $title"
