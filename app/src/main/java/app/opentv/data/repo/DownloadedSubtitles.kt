/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.SubtitleKind
import app.opentv.data.remote.SubtitleResult
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which title a Downloaded Subtitle belongs to: the source's name plus the provider's id, as
 * Favourites are matched, so it survives a Catalogue Refresh that gives the title a new row id.
 */
@Serializable
data class SubtitleTitle(val source: String, val episode: Boolean, val itemId: String) {
    companion object {
        fun film(source: String, streamId: String) = SubtitleTitle(source, episode = false, itemId = streamId)

        fun of(movie: Movie, source: String) = film(source, movie.streamId)

        fun of(episode: Episode, source: String) = SubtitleTitle(source, episode = true, itemId = episode.episodeId)
    }
}

/** A subtitle fetched from OpenSubtitles for one title and kept on this box. */
data class DownloadedSubtitle(
    val title: SubtitleTitle,
    val fileId: Long,
    val language: String,
    val kind: SubtitleKind,
    val release: String,
    val file: File,
    /** How far the viewer moved it in Timing: positive shows it later. */
    val offsetMillis: Long,
    val lastPlayedMillis: Long,
)

/**
 * Every Downloaded Subtitle on this box: the files, and an index of what each is for.
 *
 * Not Room: these never sync, are files anyway, and a table would cost a migration for what one
 * small JSON index does. They're kept to [capBytes] in total, dropping the ones unplayed for
 * longest first (saving counts as playing).
 */
class DownloadedSubtitles(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val capBytes: Long = CAP_BYTES,
) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val indexFile get() = File(dir, "index.json")

    suspend fun forTitle(title: SubtitleTitle): List<DownloadedSubtitle> = locked {
        read().filter { it.title == title }.map { it.toSubtitle() }
    }

    /**
     * Keeps [text], downloaded [from] OpenSubtitles, for [title], replacing an earlier download of the
     * same file, then trims to the cap.
     */
    suspend fun save(title: SubtitleTitle, from: SubtitleResult, text: String): DownloadedSubtitle = locked {
        dir.mkdirs()
        val fileId = from.fileId
        val entry = Entry(title, fileId, from.language, from.kind, from.release, fileName(title, fileId), 0, clock())
        File(dir, entry.fileName).writeText(text)
        val entries = read().filterNot { it.title == title && it.fileId == fileId } + entry
        write(trim(entries, keep = entry))
        entry.toSubtitle()
    }

    /** [subtitle] was shown, so it's the last to go when space is short. */
    suspend fun played(subtitle: DownloadedSubtitle) = update(subtitle) { it.copy(lastPlayedMillis = clock()) }

    suspend fun setOffset(subtitle: DownloadedSubtitle, offsetMillis: Long) =
        update(subtitle) { it.copy(offsetMillis = offsetMillis) }

    private suspend fun update(subtitle: DownloadedSubtitle, change: (Entry) -> Entry) = locked {
        write(read().map { if (it.title == subtitle.title && it.fileId == subtitle.fileId) change(it) else it })
    }

    private fun trim(entries: List<Entry>, keep: Entry): List<Entry> {
        val kept = entries.toMutableList()
        var total = kept.sumOf { File(dir, it.fileName).length() }
        val oldestFirst = entries.filter { it != keep }.sortedBy { it.lastPlayedMillis }.iterator()
        while (total > capBytes && oldestFirst.hasNext()) {
            val oldest = oldestFirst.next()
            total -= File(dir, oldest.fileName).length()
            File(dir, oldest.fileName).delete()
            kept -= oldest
        }
        return kept
    }

    private fun read(): List<Entry> =
        runCatching { json.decodeFromString<List<Entry>>(indexFile.readText()) }.getOrDefault(emptyList())
            .filter { File(dir, it.fileName).exists() }

    private fun write(entries: List<Entry>) {
        dir.mkdirs()
        // Written aside and renamed, so a crash mid-write can't lose every entry.
        val next = File(dir, "index.json.new")
        next.writeText(json.encodeToString(entries))
        next.renameTo(indexFile)
    }

    private fun Entry.toSubtitle() =
        DownloadedSubtitle(title, fileId, language, kind, release, File(dir, fileName), offsetMillis, lastPlayedMillis)

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    @Serializable
    private data class Entry(
        val title: SubtitleTitle,
        val fileId: Long,
        val language: String,
        val kind: SubtitleKind,
        val release: String,
        val fileName: String,
        val offsetMillis: Long,
        val lastPlayedMillis: Long,
    )

    private companion object {
        const val CAP_BYTES = 20L * 1024 * 1024

        /** A hashed name: source names and provider ids can hold anything, including slashes. */
        fun fileName(title: SubtitleTitle, fileId: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${title.source}\u0000${title.episode}\u0000${title.itemId}\u0000$fileId".toByteArray())
            return digest.take(HASH_BYTES).joinToString("") { "%02x".format(it) } + ".srt"
        }

        const val HASH_BYTES = 12
    }
}
