/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.os.Build
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ErrorWording
import app.opentv.core.ViewerError
import app.opentv.data.model.Favourite
import app.opentv.data.model.HandMark
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.StreamKind
import app.opentv.diagnostics.DiagnosticsLog
import app.opentv.diagnostics.DiagnosticsReporter
import app.opentv.diagnostics.Gathered
import app.opentv.diagnostics.OpenKind
import app.opentv.diagnostics.asText
import app.opentv.diagnostics.diagnosticsFileName
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What Diagnostics gathers about the app's state, for the screen and the export (#586). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DiagnosticsReportTest {

    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val test = TestCatalog(context)

    @After
    fun close() = test.close()

    private val places by lazy {
        DiagnosticsReporter.Places(
            database = folder.newFile("opentv.db"),
            cache = folder.newFolder("cache"),
            files = folder.newFolder("files"),
        )
    }

    private val log by lazy { DiagnosticsLog(folder.newFolder("diagnostics"), GlobalScope, Dispatchers.Unconfined) }

    private fun File.bytes(name: String, size: Int) = resolve(name).apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(size))
    }

    private fun reporter(openDatabase: () -> SupportSQLiteDatabase = { test.db.openHelper.readableDatabase }) =
        DiagnosticsReporter(
            context,
            ErrorWording(context, onTelevision = true),
            openDatabase,
            log,
            places,
            Dispatchers.Unconfined,
        )

    @Test
    fun `the database section counts every table's rows, and the marks set by hand`() = runBlocking {
        test.db.positions().upsert(PlaybackPosition(1, "movie:1", 0, 0, 0, mark = HandMark.WATCHED))
        test.db.positions().upsert(PlaybackPosition(1, "movie:2", 5_000, 60_000, 0))
        test.db.favourites().upsert(Favourite(1, StreamKind.MOVIE, "Film Club", "7"))
        test.db.favourites().upsert(Favourite(1, StreamKind.MOVIE, "Film Club", "8", removed = true))

        val database = (reporter().gather().database as Gathered.Got).value

        val rows = database.tables.associate { it.name to it.rows }
        assertThat(rows).containsEntry("playback_positions", 2L)
        assertThat(rows).containsEntry("favourites", 2L)
        assertThat(rows).containsEntry("channels", 0L)
        assertThat(rows.keys).containsNoneOf("room_master_table", "android_metadata", "sqlite_sequence")
        assertThat(database.handMarkedPositions).isEqualTo(1)
        assertThat(database.removedFavourites).isEqualTo(1)
    }

    @Test
    fun `a database that can't be read says so, rather than looking empty`() = runBlocking {
        val report = reporter { throw SQLiteDatabaseCorruptException("database disk image is malformed") }.gather()

        val failure = (report.database as Gathered.Failed).error
        assertThat(failure.sentence).isEqualTo("Settee couldn't read its database, so these numbers are missing.")
        assertThat(failure.detail).isEqualTo("SQLiteDatabaseCorrupt")
    }

    @Test
    fun `the database's files and the app's caches are measured, temp files left in the cache counted`() = runBlocking {
        places.database.writeBytes(ByteArray(4_096))
        places.database.parentFile!!.bytes("opentv.db-wal", 1_000)
        places.database.parentFile!!.bytes("opentv.db-shm", 32)
        places.cache.bytes("image_cache/a", 300)
        places.cache.bytes("image_cache/b/c", 200)
        places.cache.bytes("updates/settee.apk", 7_000)
        places.files.bytes("subtitles/1.srt", 50)
        places.cache.bytes("staged-1.jsonl", 10)
        places.cache.bytes("xtream-get_vod_streams-2.json", 20)

        val report = reporter().gather()

        assertThat((report.database as Gathered.Got).value.fileBytes).isEqualTo(4_096L + 1_000 + 32)
        val storage = (report.storage as Gathered.Got).value
        assertThat(storage.imageCacheBytes).isEqualTo(500L)
        assertThat(storage.updatesBytes).isEqualTo(7_000L)
        assertThat(storage.subtitlesBytes).isEqualTo(50L)
        assertThat(storage.looseCacheFiles).isEqualTo(2)
        assertThat(storage.looseCacheBytes).isEqualTo(30L)
    }

    @Test
    fun `the report carries the device, its memory, and the last opens and errors`() = runBlocking {
        log.opened(OpenKind.Episode, millis = 1_800, failure = null)
        log.errorShown(ViewerError("The connection dropped.", "IO network connection failed"))

        val report = reporter().gather()

        assertThat((report.opens as Gathered.Got).value.map { it.millis }).containsExactly(1_800L)
        assertThat(
            (report.errors as Gathered.Got).value.map {
                it.detail
            },
        ).containsExactly("IO network connection failed")
        val memory = (report.memory as Gathered.Got).value
        assertThat(memory.heapMaxBytes).isGreaterThan(0L)
        assertThat(memory.heapUsedBytes).isIn(Range.closed(1L, memory.heapMaxBytes))
        assertThat(report.device.appVersion).isEqualTo(BuildConfig.VERSION_NAME)
        assertThat(report.device.model).isEqualTo(Build.MODEL)
        assertThat(report.device.fireOsBuild).isEqualTo(Build.DISPLAY)
    }

    @Test
    fun `the exported text has every section, failures in words, and no provider address`() = runBlocking {
        test.db.positions().upsert(PlaybackPosition(1, "movie:1", 0, 0, 0, mark = HandMark.WATCHED))
        log.opened(OpenKind.Episode, millis = 1_800, failure = null)
        log.opened(
            OpenKind.Film,
            millis = 9_250,
            failure = ViewerError("Failed.", "Source error · http://tv.example.com/film.mkv"),
        )
        repeat(3) { log.errorShown(ViewerError("The connection dropped.", "IO network connection failed")) }

        val text = reporter().gather().asText(madeAtUtcMillis = 1_763_644_500_000L)

        assertThat(text).contains("Made 2025-11-20 13:15 UTC")
        assertThat(text).contains("playback_positions: 1 row")
        assertThat(text).contains("Positions marked by hand: 1")
        assertThat(text).contains("Episode · played after 1.8 s")
        assertThat(text).contains("Film · failed after 9.3 s · Source error · [address]")
        assertThat(text).contains("The connection dropped. (IO network connection failed) ×3")
        assertThat(text).contains("Model: ${Build.MODEL}")
        assertThat(text).doesNotContain("example.com")
    }

    @Test
    fun `a report that couldn't be gathered says why in the exported text`() = runBlocking {
        val text = reporter { throw SQLiteDatabaseCorruptException("malformed") }.gather().asText(0L)

        assertThat(
            text,
        ).contains("Settee couldn't read its database, so these numbers are missing. (SQLiteDatabaseCorrupt)")
    }

    @Test
    fun `each export is a file of its own in Settee's diagnostics folder on the NAS`() {
        val name = diagnosticsFileName(deviceId = "a1b2", madeAtUtcMillis = 1_763_644_500_000L)

        assertThat(name).isEqualTo("Settee/diagnostics/a1b2-20251120T131500Z.txt")
    }
}
