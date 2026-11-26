/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player.torrent

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TorrentEngineConfigTest {
    private val mib = 1024 * 1024

    @Test
    fun `the default memory cap leaves most of a 1 GB Stick to the player`() {
        // The Stick had 296 MB available at Home (docs/PERFORMANCE.md); the engine gets a small slice.
        assertThat(TorrentEngineConfig().memoryCapBytes).isAtMost(64 * mib)
    }

    @Test
    fun `upload is capped by default`() {
        assertThat(TorrentEngineConfig().uploadRateCapBytesPerSecond).isGreaterThan(0)
    }

    @Test
    fun `an uncapped upload is refused, since libtorrent reads 0 as no limit`() {
        assertThrows(IllegalArgumentException::class.java) {
            TorrentEngineConfig(uploadRateCapBytesPerSecond = 0)
        }
    }

    @Test
    fun `some space is always kept free for the system by default`() {
        assertThat(TorrentEngineConfig().keepFreeBytes).isGreaterThan(0L)
    }

    @Test
    fun `the session never seeds, opens no router port and stays off the local network`() {
        val settings = LibtorrentSettings.from(TorrentEngineConfig())

        assertThat(settings.activeSeeds).isEqualTo(0)
        assertThat(settings.enableUpnp).isFalse()
        assertThat(settings.enableNatPmp).isFalse()
        assertThat(settings.enableLsd).isFalse()
        assertThat(settings.enableDht).isTrue()
    }

    @Test
    fun `the session's buffers add up to no more than the memory cap`() {
        val settings = LibtorrentSettings.from(TorrentEngineConfig(memoryCapBytes = 40 * mib))

        assertThat(settings.maxQueuedDiskBytes).isEqualTo(30 * mib)
        assertThat(settings.sendBufferWatermark).isEqualTo(10 * mib)
    }

    @Test
    fun `the session takes its upload and connection limits from the config`() {
        val settings = LibtorrentSettings.from(
            TorrentEngineConfig(uploadRateCapBytesPerSecond = 50_000, connectionsLimit = 12),
        )

        assertThat(settings.uploadRateLimit).isEqualTo(50_000)
        assertThat(settings.connectionsLimit).isEqualTo(12)
    }

    @Test
    fun `a request needs a 40 character hex info hash`() {
        TorrentRequest("0123456789abcdefABCDEF0123456789abcdef01", fileIndex = 0)
        assertThrows(IllegalArgumentException::class.java) { TorrentRequest("abc", fileIndex = 0) }
        assertThrows(IllegalArgumentException::class.java) {
            TorrentRequest("z123456789abcdef0123456789abcdef01234567", fileIndex = 0)
        }
    }
}
