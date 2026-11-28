/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player.torrent

/**
 * The torrent engine's limits, sized for a 1 GB Fire TV Stick.
 *
 * PROVISIONAL: every default below is a first guess, to be set by Stick measurements (#669) and
 * recorded with them in docs/PERFORMANCE.md ("Torrent engine"). Don't tune them on a fast phone.
 */
data class TorrentEngineConfig(
    /**
     * Roughly what libtorrent may hold in memory beyond its fixed cost: blocks waiting to be written
     * to disk and blocks queued to send. To be set by Stick measurements (#669).
     */
    val memoryCapBytes: Int = DEFAULT_MEMORY_CAP,
    /**
     * The most a torrent's pieces may take on disk. Pieces aren't evicted while it plays, so this is
     * also the biggest file that can play. To be set by Stick measurements (#669).
     */
    val pieceCacheCapBytes: Long = DEFAULT_PIECE_CACHE_CAP,
    /** Free space left alone for Fire OS and other apps. To be set by Stick measurements (#669). */
    val keepFreeBytes: Long = DEFAULT_KEEP_FREE,
    /**
     * Upload while playing, in bytes a second. Never 0: libtorrent reads 0 as no limit. To be set
     * by Stick measurements (#669).
     */
    val uploadRateCapBytesPerSecond: Int = DEFAULT_UPLOAD_CAP,
    /** Peer connections at once; each costs buffers and a socket. To be set by Stick measurements (#669). */
    val connectionsLimit: Int = 40,
    /** How long a torrent has to find people and its file list before it fails. */
    val startTimeoutMillis: Long = 60_000,
    /** Pieces after the read position given deadlines, so playback runs ahead of the reads. */
    val readAheadPieces: Int = 8,
    /** The first read-ahead piece's deadline; each later one gets this much more. */
    val deadlineStepMillis: Int = 500,
) {
    init {
        require(memoryCapBytes > 0) { "The memory cap must be positive" }
        require(pieceCacheCapBytes > 0) { "The piece cache cap must be positive" }
        require(keepFreeBytes >= 0) { "The space kept free can't be negative" }
        // libtorrent's 0 is "unlimited", which is never what Settee wants.
        require(uploadRateCapBytesPerSecond > 0) { "Upload must be capped" }
        require(connectionsLimit > 0) { "At least one connection" }
        require(readAheadPieces > 0) { "At least one piece of read-ahead" }
    }

    companion object {
        const val KIB = 1024
        const val MIB = 1024 * KIB
        const val GIB = 1024 * MIB

        // Provisional, to be set by Stick measurements (#669).
        private const val DEFAULT_MEMORY_CAP = 32 * MIB
        private const val DEFAULT_PIECE_CACHE_CAP = 4L * GIB
        private const val DEFAULT_KEEP_FREE = 512L * MIB
        private const val DEFAULT_UPLOAD_CAP = 128 * KIB
    }
}
