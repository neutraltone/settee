/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player.torrent

import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A [TorrentEngine] for tests: the selected file is [content], cut into pieces of [pieceLength]
 * bytes, and nothing arrives until the test says so with [arrive]. It records which pieces each
 * read raised, so a test can check the read position is what's fetched first, and whether the
 * pieces were deleted by [stop].
 */
class FakeTorrentEngine(private val content: ByteArray, val pieceLength: Int = 16) : TorrentEngine {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val arrived = mutableSetOf<Int>()
    private var waitingReads = 0

    private val _status = MutableStateFlow<TorrentStatus>(TorrentStatus.Idle)
    override val status: StateFlow<TorrentStatus> = _status

    /** Every request [start] was given, in order. */
    val started = mutableListOf<TorrentRequest>()

    /** The pieces each read raised, newest last. */
    val raised = mutableListOf<List<Int>>()

    /** How many times [stop] deleted the pieces. */
    var deletions = 0
        private set

    val pieceCount: Int get() = (content.size + pieceLength - 1) / pieceLength

    override fun start(request: TorrentRequest) {
        if (_status.value != TorrentStatus.Idle) stop()
        started += request
        _status.value = TorrentStatus.Connecting()
    }

    /** People found: still connecting. */
    fun findPeers(peers: Int) {
        _status.value = TorrentStatus.Connecting(peers)
    }

    /** The file list arrived and there's room: reads can begin. */
    fun becomeReady(peers: Int = 1) {
        _status.value = TorrentStatus.Ready(content.size.toLong(), peers)
    }

    fun fail(reason: TorrentFailure, detail: String? = null) = lock.withLock {
        _status.value = TorrentStatus.Failed(reason, detail)
        changed.signalAll()
    }

    /** Pieces [pieces] have downloaded and been verified. */
    fun arrive(vararg pieces: Int) = lock.withLock {
        arrived += pieces.toList()
        changed.signalAll()
    }

    fun arriveAll() = arrive(*IntArray(pieceCount) { it })

    /** Waits until a read is blocked on a missing piece, so a test can then let it arrive. */
    fun awaitBlockedRead(timeoutMillis: Long = 5_000) = lock.withLock {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (waitingReads == 0) {
            val left = deadline - System.nanoTime()
            check(left > 0) { "No read blocked" }
            changed.awaitNanos(left)
        }
    }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= content.size) return -1
        val piece = (position / pieceLength).toInt()
        lock.withLock {
            raised += (piece until minOf(piece + READ_AHEAD, pieceCount)).toList()
            waitingReads++
            changed.signalAll()
            try {
                while (piece !in arrived) {
                    when (val now = _status.value) {
                        is TorrentStatus.Failed -> throw TorrentReadException("Torrent failed: ${now.reason}")
                        TorrentStatus.Idle -> throw TorrentReadException("Torrent stopped")
                        else -> Unit
                    }
                    try {
                        changed.await()
                    } catch (e: InterruptedException) {
                        throw InterruptedIOException().apply { initCause(e) }
                    }
                }
            } finally {
                waitingReads--
            }
        }
        val pieceEnd = minOf((piece + 1L) * pieceLength, content.size.toLong())
        val count = minOf(length.toLong(), pieceEnd - position).toInt()
        System.arraycopy(content, position.toInt(), buffer, offset, count)
        return count
    }

    override fun stop() = lock.withLock {
        if (_status.value != TorrentStatus.Idle) deletions++
        arrived.clear()
        _status.value = TorrentStatus.Idle
        changed.signalAll()
    }

    private companion object {
        const val READ_AHEAD = 3
    }
}
