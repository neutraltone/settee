/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import app.opentv.recording.SmbReader

/**
 * Serves a data source's small reads from one large NAS read. Each [SmbReader.readAt] is a whole SMB
 * request and reply, and TsExtractor asks for ~9.4 KB at a time, so passing reads straight through
 * capped a recording at 9.4 KB per round trip: below an HD recording's bitrate on a Stick's Wi-Fi.
 *
 * The chunk is keyed by file offset, so a read anywhere outside it fetches afresh. Bytes already
 * written to a recording never change, which is why a chunk can outlive the growing edge; it is
 * still dropped whenever the [reader] behind it is set, since a different file could share offsets.
 */
internal class SmbReadAhead(private val chunkSize: Int = CHUNK_BYTES) {

    /** The file reads are served from. Setting it, even to the same one reopened, drops the chunk. */
    var reader: SmbReader? = null
        set(value) {
            field = value
            chunkFilled = 0
        }

    // Allocated on first use, not up front: every player's routing source creates one of these
    // whether or not it ever plays a NAS recording.
    private var chunk: ByteArray? = null
    private var chunkStart: Long = 0L
    private var chunkFilled: Int = 0

    /**
     * Copies up to [length] bytes at file [position] into [buffer], fetching a chunk of at most
     * [fetchLimit] bytes from [reader] if [position] isn't held. Returns the bytes copied, or the
     * reader's own result (0, or -1 at its end) when the fetch returned nothing; -1 with no reader.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int, fetchLimit: Long = Long.MAX_VALUE): Int {
        val reader = reader ?: return -1
        val chunk = chunk ?: ByteArray(chunkSize).also { chunk = it }
        if (position < chunkStart || position >= chunkStart + chunkFilled) {
            chunkFilled = 0
            val fetched = reader.readAt(chunk, position, 0, minOf(chunkSize.toLong(), fetchLimit).toInt())
            if (fetched <= 0) return fetched
            chunkStart = position
            chunkFilled = fetched
        }
        val at = (position - chunkStart).toInt()
        val n = minOf(length, chunkFilled - at)
        System.arraycopy(chunk, at, buffer, offset, n)
        return n
    }

    companion object {
        /** 20 requests for 10 MB, and within SMBJ's default 1 MB read size, so one request each. */
        const val CHUNK_BYTES = 512 * 1024
    }
}
