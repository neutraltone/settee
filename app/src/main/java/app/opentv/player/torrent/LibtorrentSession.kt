/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player.torrent

import android.os.SystemClock
import android.util.Log
import com.frostwire.jlibtorrent.SessionHandle
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.swig.settings_pack
import java.io.File
import java.io.RandomAccessFile
import java.net.URLEncoder

/** A libtorrent session set up from [config] (see [LibtorrentSettings]), on the POSIX disk backend. */
internal fun sessionParams(config: TorrentEngineConfig): SessionParams {
    val wanted = LibtorrentSettings.from(config)
    val pack = SettingsPack()
        .uploadRateLimit(wanted.uploadRateLimit)
        .connectionsLimit(wanted.connectionsLimit)
        .activeSeeds(wanted.activeSeeds)
        .setInteger(settings_pack.int_types.max_queued_disk_bytes.swigValue(), wanted.maxQueuedDiskBytes)
        .setInteger(settings_pack.int_types.send_buffer_watermark.swigValue(), wanted.sendBufferWatermark)
        .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), wanted.enableUpnp)
        .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), wanted.enableNatPmp)
        .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), wanted.enableLsd)
        .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), wanted.enableDht)
    return SessionParams(pack).apply { setPosixDiskIO() }
}

/** The magnet link for [request]: its info hash and the add-on's trackers. */
internal fun magnetFor(request: TorrentRequest): String = buildString {
    append("magnet:?xt=urn:btih:").append(request.infoHash)
    request.trackers.forEach { append("&tr=").append(URLEncoder.encode(it, "UTF-8")) }
}

/** One torrent in [LibtorrentEngine], from start to stop, with the session made for it. */
internal class RunningTorrent(val request: TorrentRequest, val folder: File) {
    val startedAt = SystemClock.elapsedRealtime()

    // Touched by the caller's, the worker's and the alert thread.
    @Volatile var session: SessionManager? = null

    @Volatile var handle: TorrentHandle? = null

    @Volatile var peersSeen = 0

    @Volatile var stopped = false

    // Set under the engine's lock once the file list arrives, read after waiting on it.
    var pieceLength = 0
    var fileOffset = 0L
    var fileSize = 0L
    var lastPiece = 0
    var path: File? = null

    // Only the reading thread touches these.
    private var file: RandomAccessFile? = null
    private var deadlinesFrom = -1

    fun pieceAt(position: Long): Int = ((fileOffset + position) / pieceLength).toInt()

    /** Reads from the file on disk, up to the end of the piece holding [position]. */
    fun readFile(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val pieceEnd = (pieceAt(position) + 1L) * pieceLength - fileOffset
        val count = minOf(length.toLong(), pieceEnd - position, fileSize - position).toInt()
        val file = file ?: RandomAccessFile(path, "r").also { file = it }
        file.seek(position)
        return file.read(buffer, offset, count)
    }

    /**
     * Deadlines on the pieces from [piece] on, nearest first. Moving on within the window only
     * adds the newly reached pieces; a jump outside it (a seek) clears the old deadlines first, or
     * libtorrent would keep spending the connection on the place the viewer left.
     */
    fun raise(handle: TorrentHandle, piece: Int, config: TorrentEngineConfig) {
        val from = deadlinesFrom
        if (piece == from) return
        if (from < 0 || piece < from || piece > from + config.readAheadPieces) handle.clearPieceDeadlines()
        val last = minOf(piece + config.readAheadPieces - 1, lastPiece)
        for (p in piece..last) {
            if (!handle.havePiece(p)) handle.setPieceDeadline(p, (p - piece + 1) * config.deadlineStepMillis)
        }
        deadlinesFrom = piece
    }

    /** Removes the torrent with its files, shuts the session down and empties its folder. */
    fun tearDown() {
        runCatching { file?.close() }
        val session = session
        this.session = null
        if (session != null) {
            runCatching {
                handle?.takeIf { it.isValid }?.let { session.remove(it, SessionHandle.DELETE_FILES) }
                session.stop()
            }.onFailure { Log.w(TAG, "Torrent session didn't stop cleanly", it) }
        }
        folder.deleteRecursively()
    }

    private companion object {
        const val TAG = "TorrentEngine"
    }
}
