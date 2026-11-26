/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player.torrent

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Reads the torrent the [engine] is fetching, for the player (#670). Only `magnet:` URIs come here;
 * the player's controller starts the engine on the torrent before handing the player its link, so
 * this source only reads. Each read asks the engine for the bytes at the player's position, which
 * raises the priority of the pieces there: playback starts before the file is whole, and a seek
 * jumps without fetching everything before it. No local HTTP port is opened (ADR 0012).
 */
@OptIn(UnstableApi::class)
class TorrentDataSource(private val engine: TorrentEngine) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val fileSize = awaitFileSize()
        if (dataSpec.position > fileSize) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, fileSize - position)
        } else {
            fileSize - position
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    /**
     * Blocks Media3's loader thread until the torrent's file list is in. Cancelling a load interrupts
     * that thread, which ends the wait as Media3 expects of a blocked read.
     */
    private fun awaitFileSize(): Long {
        val settled = try {
            runBlocking { engine.status.first { it !is TorrentStatus.Connecting } }
        } catch (e: InterruptedException) {
            throw InterruptedIOException("Interrupted waiting for the torrent").apply { initCause(e) }
        }
        return (settled as? TorrentStatus.Ready)?.fileSize ?: throw failureOf(settled)
    }

    /** Why the torrent can't be read: its failure, when the status says one, which is the sentence. */
    private fun failureOf(status: TorrentStatus): IOException = when (status) {
        is TorrentStatus.Failed -> TorrentFailedException(status.reason, status.detail)
        else -> TorrentReadException("Torrent stopped")
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = when {
        length == 0 -> 0
        bytesRemaining == 0L -> C.RESULT_END_OF_INPUT
        else -> readFromEngine(buffer, offset, minOf(length.toLong(), bytesRemaining).toInt())
    }

    private fun readFromEngine(buffer: ByteArray, offset: Int, wanted: Int): Int {
        val read = try {
            engine.read(position, buffer, offset, wanted)
        } catch (e: TorrentReadException) {
            // The engine's exception only says it failed; the status says why, which is the sentence.
            throw (engine.status.value as? TorrentStatus.Failed)?.let(::failureOf) ?: e
        }
        if (read < 0) return C.RESULT_END_OF_INPUT
        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
    }

    class Factory(private val engine: TorrentEngine) : DataSource.Factory {
        override fun createDataSource(): DataSource = TorrentDataSource(engine)
    }
}

/**
 * The torrent can't play, and [reason] says why, for `PlaybackErrors`' sentence; [detail] is for the
 * error's detail line.
 */
class TorrentFailedException(val reason: TorrentFailure, val detail: String? = null) :
    IOException("Torrent failed: $reason")
