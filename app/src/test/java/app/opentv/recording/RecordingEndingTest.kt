/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * What a recording becomes when its capture stops by itself (#473): Failed when nothing was captured,
 * kept as Recorded but Cut short when something was, each saying why; and a stop by hand is a plain
 * finish. Driven through [RecordingService] as the alarm starts it, on a graph whose provider and
 * storage each test sets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecordingEndingTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** How the provider answers each leg, numbered from 1. */
    @Volatile
    private var answer: (leg: Int, Request) -> Response = { _, request -> video(request, "abc") }

    @Volatile
    private var legs = 0

    /** Where the recording's bytes go. */
    @Volatile
    private var storage: OutputStream = OutputStream.nullOutputStream()

    private val provider = OkHttpClient.Builder().addInterceptor { chain ->
        answer(++legs, chain.request())
    }.build()

    private val graph = ServiceLocator.Graph(
        app,
        http = provider,
        settingsFile = "recording-ending",
        openDatabase = {
            Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java).allowMainThreadQueries().build()
        },
        openRecordingFile = { RecordingStorage.Sink(storage) {} },
        captureReconnectMillis = 0,
    )
    private val recordings = graph.recordingRepository
    private var before: ServiceLocator.Graph? = null
    private var service: ServiceController<RecordingService>? = null

    @Before
    fun useGraph() {
        before = ServiceLocator.swap(graph)
    }

    @After
    fun close() {
        service?.destroy()
        ServiceLocator.swap(before)
        runBlocking { graph.stopBackgroundWork() }
        graph.database.close()
    }

    @Test
    fun `storage filling after some video keeps the recording, saying the storage is full`() = runBlocking {
        storage = FullAfter(6)

        val row = record()

        assertThat(row.status).isEqualTo(RecordingStatus.COMPLETED)
        assertThat(row.sizeBytes).isEqualTo(6)
        assertThat(row.reason).isEqualTo(ViewerError(app.getString(R.string.rec_error_full_device), "StorageFull"))
    }

    @Test
    fun `storage full before any video fails the recording, saying the storage is full`() = runBlocking {
        storage = FullAfter(0)

        val row = record()

        assertThat(row.status).isEqualTo(RecordingStatus.FAILED)
        assertThat(row.reason).isEqualTo(ViewerError(app.getString(R.string.rec_error_full_device), "StorageFull"))
    }

    @Test
    fun `a provider refusing every request fails the recording with its refusal`() = runBlocking {
        answer = { _, request -> refusal(request, 403) }

        val row = record()

        assertThat(row.status).isEqualTo(RecordingStatus.FAILED)
        assertThat(row.reason).isEqualTo(ViewerError(app.getString(R.string.error_http_403), "HTTP 403"))
    }

    @Test
    fun `a refusal after some video keeps the recording, with the refusal's sentence`() = runBlocking {
        answer = { leg, request -> if (leg == 1) video(request, "abc") else refusal(request, 403) }

        val row = record()

        assertThat(row.status).isEqualTo(RecordingStatus.COMPLETED)
        assertThat(row.reason).isEqualTo(ViewerError(app.getString(R.string.error_http_403), "HTTP 403"))
    }

    @Test
    fun `the stream going quiet after some video keeps the recording, saying the channel stopped`() = runBlocking {
        answer = { leg, request -> video(request, if (leg == 1) "abc" else "") }

        val row = record()

        assertThat(row.status).isEqualTo(RecordingStatus.COMPLETED)
        assertThat(row.reason).isEqualTo(
            ViewerError(app.getString(R.string.rec_error_channel_stopped), "ChannelStopped"),
        )
    }

    @Test
    fun `a recording stopped by hand is kept with no reason`() = runBlocking {
        val delivered = CountDownLatch(1)
        answer = { _, request -> video(request, "abc").also { delivered.countDown() } }
        val id = start(endMillis = 0)
        assertThat(delivered.await(10, TimeUnit.SECONDS)).isTrue()

        RecordingService.stop(app, id)
        service!!.withIntent(shadowOf(app).nextStartedService).startCommand(0, 2)
        val row = finished(id)

        assertThat(row.status).isEqualTo(RecordingStatus.COMPLETED)
        assertThat(row.error).isNull()
    }

    /** Records a programme that's on now until its capture stops by itself, and gives its row then. */
    private suspend fun record(): Recording = finished(start(endMillis = System.currentTimeMillis() + HOUR))

    /** Starts recording, as an alarm does, until [endMillis] (0 for no end, as Record now has). */
    private suspend fun start(endMillis: Long): Long {
        val now = System.currentTimeMillis()
        val id = recordings.insert(
            Recording(
                channelId = 1,
                sourceId = 1,
                channelName = "ITV",
                title = "Countryfile",
                filePath = "/recordings/countryfile.ts",
                // No channel 1 in the catalogue: the capture falls back to the link it last had.
                streamUrl = "http://example.com/live/u/p/1.ts",
                scheduledStartMillis = if (endMillis > 0) now else 0,
                scheduledEndMillis = endMillis,
                status = RecordingStatus.SCHEDULED,
            ),
        )
        RecordingService.start(app, id)
        service = Robolectric.buildService(RecordingService::class.java, shadowOf(app).nextStartedService)
            .create()
            .startCommand(0, 1)
        return id
    }

    /**
     * Waits for the capture to finish. It runs on the service's own dispatcher, so this waits for the
     * row to change rather than for time to pass; the timeout only stops a broken run hanging.
     */
    private suspend fun finished(id: Long): Recording = withTimeout(10_000) {
        recordings.observeAll()
            .map { rows -> rows.single { it.id == id } }
            .first { it.status != RecordingStatus.SCHEDULED && it.status != RecordingStatus.RECORDING }
    }

    private val Recording.reason get() = error?.let { ViewerError.fromStored(it) }

    private fun video(request: Request, body: String) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("video/mp2t".toMediaType()))
        .build()

    private fun refusal(request: Request, code: Int) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Refused")
        .body("".toResponseBody())
        .build()

    /** Storage with room for [capacity] bytes, failing a write past that as Android's own files do. */
    private class FullAfter(private val capacity: Int) : OutputStream() {
        private var written = 0

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (written + len > capacity) throw IOException("write failed: ENOSPC (No space left on device)")
            written += len
        }
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
