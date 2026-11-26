/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player.torrent

import java.io.IOException
import kotlinx.coroutines.flow.StateFlow

/**
 * What playback asks of a BitTorrent library (ADR 0012). The player's Media3 `DataSource` (#670)
 * depends on this alone, so its tests use a fake, and the library can change without touching it.
 *
 * One torrent at a time, and only while it plays: [start] when the player opens one, [stop] when
 * it leaves, which also deletes the pieces. There is no seeding afterwards. Reads come straight
 * from the engine; no local HTTP port is opened, so no other app on the box can reach the stream.
 */
interface TorrentEngine {
    /** Where the current torrent has got to; [TorrentStatus.Idle] before [start] and after [stop]. */
    val status: StateFlow<TorrentStatus>

    /**
     * Starts fetching one file of a torrent. Returns at once; [status] follows it. Starting another
     * torrent stops the current one first.
     */
    fun start(request: TorrentRequest)

    /**
     * Reads up to [length] bytes of the selected file at [position] into [buffer], blocking until
     * the piece holding [position] has arrived. Each call raises the priority of the pieces at and
     * just after [position], so the player's read position, after a seek too, is what's fetched
     * first. Returns the number of bytes read, which may be fewer than [length], or -1 at the end of
     * the file.
     *
     * Throws [TorrentReadException] if the torrent failed or was stopped while waiting, and
     * [java.io.InterruptedIOException] if the reading thread was interrupted.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** Stops the torrent and deletes its pieces. Safe to call when nothing is running. */
    fun stop()
}

/**
 * One file of a torrent, as an add-on's stream gives it: the info hash (40 hex characters), the
 * file's index in the torrent, and any trackers the add-on listed besides the DHT.
 */
data class TorrentRequest(val infoHash: String, val fileIndex: Int, val trackers: List<String> = emptyList()) {
    init {
        require(infoHash.length == INFO_HASH_LENGTH && infoHash.all { it.isHexDigit() }) {
            "An info hash is 40 hex characters"
        }
        require(fileIndex >= 0) { "A file index can't be negative" }
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private companion object {
        const val INFO_HASH_LENGTH = 40
    }
}

/** Where a torrent has got to, for the player to show and to decide when to start reading. */
sealed interface TorrentStatus {
    /** Nothing running. */
    data object Idle : TorrentStatus

    /** Asking trackers and the DHT for people sharing it, and for its list of files. */
    data class Connecting(val peers: Int = 0) : TorrentStatus

    /** The file is known and there's room for it; reads can begin. */
    data class Ready(val fileSize: Long, val peers: Int) : TorrentStatus

    /** It can't play; [detail] is for the error's detail line, never a sentence for the viewer. */
    data class Failed(val reason: TorrentFailure, val detail: String? = null) : TorrentStatus
}

/** Why a torrent can't play. Each becomes a sentence through `PlaybackErrors` (#670). */
enum class TorrentFailure {
    /** Nobody was found sharing the file. */
    NOBODY_SHARING,

    /** The file is bigger than the cache cap, or than the free space less what's kept back. */
    NOT_ENOUGH_SPACE,

    /** People were found, but the torrent's file list didn't arrive in time. */
    START_TIMED_OUT,

    /** The library can't run on this device: below Android 8.0, or an ABI the APK doesn't carry. */
    UNSUPPORTED_DEVICE,

    /** The library reported an error of its own, in [TorrentStatus.Failed.detail]. */
    ENGINE_ERROR,
}

/** A read that couldn't finish because the torrent failed or was stopped. */
class TorrentReadException(message: String) : IOException(message)
