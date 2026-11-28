/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player.torrent

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The [TorrentEngine] contract, as [FakeTorrentEngine] keeps it, so the player's tests (#670) can
 * trust the fake to behave like the real engine where it matters.
 */
class FakeTorrentEngineTest {
    private val content = ByteArray(100) { it.toByte() }
    private val engine = FakeTorrentEngine(content, pieceLength = 16)
    private val request = TorrentRequest("0123456789abcdef0123456789abcdef01234567", fileIndex = 2)
    private val reader = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        reader.shutdownNow()
    }

    @Test
    fun `starting goes from idle to connecting, then ready with the file's size`() {
        assertThat(engine.status.value).isEqualTo(TorrentStatus.Idle)

        engine.start(request)
        assertThat(engine.status.value).isEqualTo(TorrentStatus.Connecting(peers = 0))

        engine.findPeers(3)
        assertThat(engine.status.value).isEqualTo(TorrentStatus.Connecting(peers = 3))

        engine.becomeReady(peers = 3)
        assertThat(engine.status.value).isEqualTo(TorrentStatus.Ready(fileSize = 100, peers = 3))
    }

    @Test
    fun `a read returns the file's bytes at the position, up to the end of the piece`() {
        engine.start(request)
        engine.becomeReady()
        engine.arriveAll()
        val buffer = ByteArray(32)

        val count = engine.read(position = 20, buffer = buffer, offset = 4, length = 28)

        // Piece 1 covers bytes 16..31, so a read from 20 stops at 31.
        assertThat(count).isEqualTo(12)
        assertThat(buffer.copyOfRange(4, 16).toList()).isEqualTo((20..31).map { it.toByte() })
    }

    @Test
    fun `a read past the end of the file returns -1`() {
        engine.start(request)
        engine.becomeReady()

        assertThat(engine.read(position = 100, buffer = ByteArray(8), offset = 0, length = 8)).isEqualTo(-1)
    }

    @Test
    fun `a read waits for its piece, then returns it`() {
        engine.start(request)
        engine.becomeReady()
        val read = reader.submit<Int> { engine.read(position = 50, buffer = ByteArray(8), offset = 0, length = 8) }

        engine.awaitBlockedRead()
        assertThat(read.isDone).isFalse()
        engine.arrive(3)

        assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo(8)
    }

    @Test
    fun `a read raises the pieces at its position, so a seek is fetched first`() {
        engine.start(request)
        engine.becomeReady()
        engine.arriveAll()

        engine.read(position = 0, buffer = ByteArray(8), offset = 0, length = 8)
        engine.read(position = 70, buffer = ByteArray(8), offset = 0, length = 8)

        assertThat(engine.raised.last().first()).isEqualTo(4)
        assertThat(engine.raised.first().first()).isEqualTo(0)
    }

    @Test
    fun `stopping deletes the pieces and ends a waiting read with an error`() {
        engine.start(request)
        engine.becomeReady()
        val read = reader.submit<Int> { engine.read(position = 0, buffer = ByteArray(8), offset = 0, length = 8) }
        engine.awaitBlockedRead()

        engine.stop()

        val failure = runCatching { read.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertThat(failure?.cause).isInstanceOf(TorrentReadException::class.java)
        assertThat(engine.status.value).isEqualTo(TorrentStatus.Idle)
        assertThat(engine.deletions).isEqualTo(1)
    }

    @Test
    fun `a failure ends a waiting read with an error and keeps its reason`() {
        engine.start(request)
        val read = reader.submit<Int> { engine.read(position = 0, buffer = ByteArray(8), offset = 0, length = 8) }
        engine.awaitBlockedRead()

        engine.fail(TorrentFailure.NOBODY_SHARING)

        val failure = runCatching { read.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
        assertThat(failure?.cause).isInstanceOf(TorrentReadException::class.java)
        assertThat(engine.status.value).isEqualTo(TorrentStatus.Failed(TorrentFailure.NOBODY_SHARING))
    }

    @Test
    fun `stopping when nothing runs is harmless`() {
        engine.stop()

        assertThat(engine.status.value).isEqualTo(TorrentStatus.Idle)
        assertThat(engine.deletions).isEqualTo(0)
    }

    @Test
    fun `starting another torrent stops the first`() {
        engine.start(request)
        engine.start(request.copy(fileIndex = 0))

        assertThat(engine.deletions).isEqualTo(1)
        assertThat(engine.started.map { it.fileIndex }).containsExactly(2, 0).inOrder()
    }

    @Test
    fun `a read on a stopped engine fails rather than waiting for ever`() {
        assertThrows(TorrentReadException::class.java) {
            engine.read(position = 0, buffer = ByteArray(8), offset = 0, length = 8)
        }
    }
}
