/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player.torrent

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TorrentCacheTest {
    @get:Rule val temp = TemporaryFolder()

    private val gib = 1024L * 1024 * 1024
    private val config = TorrentEngineConfig(pieceCacheCapBytes = 4 * gib, keepFreeBytes = gib / 2)

    private fun cache(freeBytes: Long = 100 * gib) = TorrentCache(
        dir = File(temp.root, "torrents"),
        config = config,
        freeBytes = { freeBytes },
    )

    @Test
    fun `a leftover cache from a crash is cleared, leaving an empty folder`() {
        val dir = File(temp.root, "torrents")
        File(dir, "abc/Film/film.mkv").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(1024))
        }
        File(dir, "loose.part").writeBytes(ByteArray(10))

        cache().clearLeftovers()

        assertThat(dir.isDirectory).isTrue()
        assertThat(dir.list()).isEmpty()
    }

    @Test
    fun `clearing with no cache yet does nothing wrong`() {
        cache().clearLeftovers()

        assertThat(File(temp.root, "torrents").list()).isEmpty()
    }

    @Test
    fun `clearing leaves the rest of the app's cache alone`() {
        val images = File(temp.root, "image_cache/poster").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(10))
        }

        cache().clearLeftovers()

        assertThat(images.exists()).isTrue()
    }

    @Test
    fun `a file that fits under the cap with room to spare has room`() {
        assertThat(cache(freeBytes = 10 * gib).roomFor(2 * gib)).isTrue()
    }

    @Test
    fun `a file bigger than the cache cap has no room, however much space is free`() {
        assertThat(cache(freeBytes = 100 * gib).roomFor(5 * gib)).isFalse()
    }

    @Test
    fun `a file that would eat into the space kept free for the system has no room`() {
        // 2 GiB free, half a GiB kept back: 1.5 GiB is usable.
        assertThat(cache(freeBytes = 2 * gib).roomFor(gib + gib / 2)).isTrue()
        assertThat(cache(freeBytes = 2 * gib).roomFor(gib + gib / 2 + 1)).isFalse()
    }

    @Test
    fun `less free space than the amount kept back leaves room for nothing`() {
        assertThat(cache(freeBytes = gib / 4).roomFor(1)).isFalse()
    }

    @Test
    fun `the room a torrent has is the free space less what's kept back, up to the cache cap`() {
        assertThat(cache(freeBytes = 3 * gib).room()).isEqualTo(gib * 5 / 2)
        assertThat(cache(freeBytes = 100 * gib).room()).isEqualTo(4 * gib)
        assertThat(cache(freeBytes = gib / 4).room()).isEqualTo(0)
    }

    @Test
    fun `each torrent's pieces go in their own folder inside the cache`() {
        val folder = cache().folderFor("0123456789ABCDEF0123456789ABCDEF01234567")

        assertThat(folder.parentFile).isEqualTo(File(temp.root, "torrents"))
        assertThat(folder.name).isEqualTo("0123456789abcdef0123456789abcdef01234567")
    }
}
