/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player.torrent

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player reads a torrent through the engine (#670): byte ranges from wherever it asks, with the
 * pieces under that position fetched first, and the engine's failures as failures it can word.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TorrentDataSourceTest {

    private val content = ByteArray(100) { it.toByte() }
    private val engine = FakeTorrentEngine(content, pieceLength = 16)
    private val source = TorrentDataSource(engine)
    private val background = Executors.newSingleThreadExecutor()

    @After
    fun close() {
        background.shutdownNow()
    }

    @Test
    fun `it reads from where the player asks, and the pieces there are fetched first`() {
        engine.start(REQUEST)
        engine.becomeReady()
        engine.arriveAll()

        val length = source.open(spec(position = 40))
        val read = readAll(source)

        assertThat(length).isEqualTo(60)
        assertThat(read).isEqualTo(content.copyOfRange(40, 100))
        assertThat(engine.raised.first().first()).isEqualTo(2)
    }

    @Test
    fun `a range the player asks for ends where it said`() {
        engine.start(REQUEST)
        engine.becomeReady()
        engine.arriveAll()

        val length = source.open(spec(position = 70, length = 20))
        val read = readAll(source)

        assertThat(length).isEqualTo(20)
        assertThat(read).isEqualTo(content.copyOfRange(70, 90))
    }

    @Test
    fun `opening waits for the torrent's file list, then reading waits for the piece under it`() {
        engine.start(REQUEST)
        val opened = background.submit<Long> { source.open(spec(position = 0)) }

        engine.findPeers(3)
        assertThat(runCatching { opened.get(200, TimeUnit.MILLISECONDS) }.isFailure).isTrue()
        engine.becomeReady()
        assertThat(opened.get(5, TimeUnit.SECONDS)).isEqualTo(100)

        val buffer = ByteArray(16)
        val read = background.submit<Int> { source.read(buffer, 0, 16) }
        engine.awaitBlockedRead()
        engine.arrive(0)

        assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo(16)
        assertThat(buffer).isEqualTo(content.copyOfRange(0, 16))
    }

    @Test
    fun `a torrent nobody is sharing fails to open, saying so`() {
        engine.start(REQUEST)
        engine.fail(TorrentFailure.NOBODY_SHARING, "0 peers")

        val thrown = assertThrows(TorrentFailedException::class.java) { source.open(spec(position = 0)) }

        assertThat(thrown.reason).isEqualTo(TorrentFailure.NOBODY_SHARING)
        assertThat(thrown.detail).isEqualTo("0 peers")
    }

    @Test
    fun `a torrent that fails while the player waits for a piece fails the read, saying why`() {
        engine.start(REQUEST)
        engine.becomeReady()
        source.open(spec(position = 0))
        val read = background.submit<Int> { source.read(ByteArray(16), 0, 16) }
        engine.awaitBlockedRead()

        engine.fail(TorrentFailure.NOT_ENOUGH_SPACE)

        val thrown = runCatching { read.get(5, TimeUnit.SECONDS) }.exceptionOrNull()?.cause
        assertThat(thrown).isInstanceOf(TorrentFailedException::class.java)
        assertThat((thrown as TorrentFailedException).reason).isEqualTo(TorrentFailure.NOT_ENOUGH_SPACE)
    }

    private fun readAll(source: TorrentDataSource): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(7)
        while (true) {
            val n = source.read(buffer, 0, buffer.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun spec(position: Long, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(Uri.parse(REQUEST.uri)).setPosition(position).setLength(length).build()

    private companion object {
        val REQUEST = TorrentRequest("0123456789abcdef0123456789abcdef01234567", fileIndex = 0)
    }
}
