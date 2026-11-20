/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.StatFs
import android.util.Log
import androidx.annotation.StringRes
import androidx.sqlite.db.SupportSQLiteDatabase
import app.opentv.BuildConfig
import app.opentv.R
import app.opentv.core.ErrorWording
import app.opentv.core.ViewerError
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** A part of the report, or why it couldn't be gathered: a broken database mustn't look like an empty one. */
sealed interface Gathered<out T> {
    data class Got<T>(val value: T) : Gathered<T>

    data class Failed(val error: ViewerError) : Gathered<Nothing>
}

/** [got] with what was gathered, or [failed] with why it couldn't be. Inline, so a screen can draw in either. */
inline fun <T, R> Gathered<T>.fold(got: (T) -> R, failed: (ViewerError) -> R): R = when (this) {
    is Gathered.Got -> got(value)
    is Gathered.Failed -> failed(error)
}

/** One table's rows, or why they couldn't be counted. */
data class TableRows(val name: String, val rows: Long?, val failure: ViewerError? = null)

/** [fileBytes] counts the -wal and -shm beside the file: the WAL is where a database that won't checkpoint grows. */
data class DatabaseState(
    val fileBytes: Long,
    val tables: List<TableRows>,
    val handMarkedPositions: Long,
    val removedFavourites: Long,
)

/** [freeBytes] and [totalBytes] are the app's own partition, which is what fills on a Stick. */
data class StorageState(
    val freeBytes: Long,
    val totalBytes: Long,
    val imageCacheBytes: Long,
    val subtitlesBytes: Long,
    val updatesBytes: Long,
    val looseCacheFiles: Int,
    val looseCacheBytes: Long,
)

/** [pssBytes] is what the system counts against the app; [lowMemory] is the system saying it's short itself. */
data class MemoryState(val heapUsedBytes: Long, val heapMaxBytes: Long, val pssBytes: Long, val lowMemory: Boolean)

/** [fireOsBuild] is Build.DISPLAY, which on a Fire TV names the Fire OS release. */
data class DeviceState(val appVersion: String, val androidVersion: String, val fireOsBuild: String, val model: String)

data class DiagnosticsReport(
    val database: Gathered<DatabaseState>,
    val storage: Gathered<StorageState>,
    val memory: Gathered<MemoryState>,
    val opens: Gathered<List<LoggedOpen>>,
    val errors: Gathered<List<LoggedError>>,
    val device: DeviceState,
)

/**
 * Gathers what Diagnostics shows: the state the app is in, so "it got slow" comes with numbers.
 * Everything is read on [io]; nothing here writes.
 */
class DiagnosticsReporter(
    private val context: Context,
    private val wording: ErrorWording,
    /** The app's database, opened: a seam so a test can have one that won't open. */
    private val openDatabase: () -> SupportSQLiteDatabase,
    private val log: DiagnosticsLog,
    private val places: Places,
    private val io: CoroutineDispatcher,
) {
    /** Where the files Diagnostics measures are. [database] is the main file; its -wal and -shm sit beside it. */
    data class Places(val database: File, val cache: File, val files: File)

    suspend fun gather(): DiagnosticsReport = withContext(io) {
        DiagnosticsReport(
            database = gathering(R.string.diagnostics_database_failed) { database() },
            storage = gathering(R.string.diagnostics_storage_failed) { storage() },
            memory = gathering(R.string.diagnostics_memory_failed) { memory() },
            opens = gathering(R.string.diagnostics_log_failed) { log.opens() },
            errors = gathering(R.string.diagnostics_log_failed) { log.errors() },
            device = DeviceState(BuildConfig.VERSION_NAME, Build.VERSION.RELEASE, Build.DISPLAY, Build.MODEL),
        )
    }

    /**
     * [part], or its failure in words. Cancellation isn't a failure: it's let through, so leaving the
     * screen stops the gathering rather than reporting it broken. Inline, so [part] may suspend, as
     * reading the log does.
     */
    private inline fun <T> gathering(@StringRes sentence: Int, part: () -> T): Gathered<T> =
        runCatching(part).fold(onSuccess = { Gathered.Got(it) }, onFailure = { failed(it, sentence) })

    private fun failed(error: Throwable, @StringRes sentence: Int): Gathered.Failed {
        if (error is CancellationException) throw error
        Log.w(DIAGNOSTICS_TAG, "Couldn't gather a part of the report", error)
        return Gathered.Failed(wording.of(error, sentence))
    }

    private fun memory(): MemoryState {
        val runtime = Runtime.getRuntime()
        val system = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(system)
        return MemoryState(
            heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            heapMaxBytes = runtime.maxMemory(),
            pssBytes = Debug.getPss() * BYTES_PER_KIB,
            lowMemory = system.lowMemory,
        )
    }

    private fun database(): DatabaseState {
        val sql = openDatabase()
        // From sqlite_master rather than a list kept here, so a table added later is counted too.
        val names = sql.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('android_metadata', 'room_master_table') ORDER BY name",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val file = places.database
        return DatabaseState(
            fileBytes = listOf(file, File("${file.path}-wal"), File("${file.path}-shm")).sumOf { it.length() },
            tables = names.map(::tableRows),
            handMarkedPositions = count(sql, "SELECT COUNT(*) FROM playback_positions WHERE mark IS NOT NULL"),
            removedFavourites = count(sql, "SELECT COUNT(*) FROM favourites WHERE removed = 1"),
        )
    }

    /** One table that can't be counted is said so on its own row, and the others are still counted. */
    private fun tableRows(name: String): TableRows =
        gathering(R.string.diagnostics_table_failed) { count(openDatabase(), "SELECT COUNT(*) FROM `$name`") }
            .fold(got = { TableRows(name, it) }, failed = { TableRows(name, null, it) })

    private fun storage(): StorageState {
        val partition = StatFs(places.files.path)
        // Files left straight in the cache are the staging and download temp files, which should go
        // once used: a pile of them is a leak.
        val loose = places.cache.listFiles().orEmpty().filter { it.isFile }
        return StorageState(
            freeBytes = partition.availableBytes,
            totalBytes = partition.totalBytes,
            imageCacheBytes = sizeOf(File(places.cache, "image_cache")),
            subtitlesBytes = sizeOf(File(places.files, "subtitles")),
            updatesBytes = sizeOf(File(places.cache, "updates")),
            looseCacheFiles = loose.size,
            looseCacheBytes = loose.sumOf { it.length() },
        )
    }

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun count(sql: SupportSQLiteDatabase, query: String): Long =
        sql.query(query).use { if (it.moveToFirst()) it.getLong(0) else 0L }
}
