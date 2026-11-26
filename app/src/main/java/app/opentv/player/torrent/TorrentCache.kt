/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player.torrent

import java.io.File
import java.util.Locale

/**
 * Where a torrent's pieces live on the box: one folder in the app's cache dir, a sub-folder per
 * torrent. Nothing is kept between plays (#665), so anything found here at app start is a
 * leftover from a crash or a kill and is deleted.
 */
class TorrentCache(
    val dir: File,
    private val config: TorrentEngineConfig,
    /** Bytes free on [dir]'s volume; a parameter so tests needn't fill a disk. */
    private val freeBytes: (File) -> Long = { it.usableSpace },
) {
    /** Deletes everything under [dir] and leaves it empty. Called once, at app start. */
    fun clearLeftovers() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
        dir.mkdirs()
    }

    /**
     * Whether a file of [fileSize] bytes can be played. Pieces are never evicted while a torrent
     * plays (libtorrent has no call to drop a verified piece), so the whole file has to fit, under
     * the cache cap and without eating into the space kept free for the system.
     */
    fun roomFor(fileSize: Long): Boolean {
        dir.mkdirs()
        val usable = freeBytes(dir) - config.keepFreeBytes
        return fileSize <= config.pieceCacheCapBytes && fileSize <= usable
    }

    /** The folder for one torrent's pieces, named by its info hash. */
    fun folderFor(infoHash: String): File = File(dir, infoHash.lowercase(Locale.ROOT))

    companion object {
        /** The torrent cache inside the app's [cacheDir]. */
        fun dirIn(cacheDir: File): File = File(cacheDir, "torrents")
    }
}
