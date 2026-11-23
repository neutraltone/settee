/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Channel
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.data.repo.PlaybackSources
import app.opentv.recording.StreamCapture
import app.opentv.recording.StreamCapture.Ending
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A recording reconnects as providers drop the stream, asking for its link again each time (#98). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StreamCaptureTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val db = test.db
    private val panel = test.panel
    private val sources = PlaybackSources(db, XtreamApi(panel.http), StalkerApi(panel.http), test.lineup)

    /** Each leg the provider serves: the link it was asked on, and the user agent. */
    private val legs = mutableListOf<Pair<String, String?>>()

    /** A provider that serves three bytes and closes the socket, as live .ts endpoints do every ~30 s. */
    private val provider = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        legs += request.url.toString() to request.header("User-Agent")
        // The portal's next link is a new one, as a real portal's would be once the last expired.
        panel.replies["create_link"] = link(legs.size)
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("abc".toResponseBody("video/mp2t".toMediaType()))
            .build()
    }.build()

    @After
    fun close() = test.close()

    @Test
    fun `a Stalker recording mints a fresh link on every reconnect and keeps appending`() = runTest {
        val id = stalkerRecording()
        val out = ByteArrayOutputStream()

        val outcome = StreamCapture(provider, clock = { legs.size * 1_000L }) {
            sources.forRecording(MediaKey.Recording(id))
        }.run(out, endAtMillis = 3_000L)

        assertThat(legs).containsExactly(
            "http://example.com/play/9.ts?play_token=0" to "OpenTV/0.1 (Android)",
            "http://example.com/play/9.ts?play_token=1" to "OpenTV/0.1 (Android)",
            "http://example.com/play/9.ts?play_token=2" to "OpenTV/0.1 (Android)",
        ).inOrder()
        assertThat(out.toString()).isEqualTo("abcabcabc")
        assertThat(outcome).isEqualTo(StreamCapture.Outcome(bytes = 9, ending = Ending.Finished))
    }

    @Test
    fun `storage running out ends the capture at once, saying it's full, with what was written`() = runTest {
        val outcome = StreamCapture(provider, clock = { legs.size * 1_000L }) { ready }
            .run(FullAfter(6), endAtMillis = 10_000L)

        // The third leg's write found no room: no fourth, though the end is far off.
        assertThat(legs).hasSize(3)
        assertThat(outcome).isEqualTo(StreamCapture.Outcome(bytes = 6, ending = Ending.StorageFull))
    }

    @Test
    fun `a NAS saying its disk is full is storage full too`() = runTest {
        // How smbj words it, wrapped as smbj wraps a failed write.
        val full = RuntimeException(
            "Write failed",
            RuntimeException("STATUS_DISK_FULL (0xc000007f): Write failed for \\\\nas\\rec\\a.ts"),
        )

        val outcome = StreamCapture(provider, clock = { legs.size * 1_000L }) { ready }
            .run(FailingWith(full), endAtMillis = 10_000L)

        assertThat(outcome).isEqualTo(StreamCapture.Outcome(bytes = 0, ending = Ending.StorageFull))
    }

    @Test
    fun `storage that fails a write any other way ends the capture at once, saying it was lost`() = runTest {
        val gone = IOException("write failed: EIO (I/O error)")

        val outcome = StreamCapture(provider, clock = { legs.size * 1_000L }) { ready }
            .run(FailingWith(gone), endAtMillis = 10_000L)

        assertThat(legs).hasSize(1)
        assertThat(outcome).isEqualTo(StreamCapture.Outcome(bytes = 0, ending = Ending.StorageLost(gone)))
    }

    @Test
    fun `a connection that breaks after sending video is reconnected, however often, until the end`() = runTest {
        val breaking = providerAnswering { request -> ok(request, BreaksAfter("abc")) }
        val out = ByteArrayOutputStream()

        val outcome = StreamCapture(breaking, clock = { legs.size * 1_000L }) { ready }
            .run(out, endAtMillis = 10_000L)

        assertThat(legs).hasSize(10)
        assertThat(outcome).isEqualTo(StreamCapture.Outcome(bytes = 30, ending = Ending.Finished))
    }

    @Test
    fun `a provider refusing every request ends the capture with its refusal`() = runTest {
        val refusing = providerAnswering { request -> answer(request, 403, "".toResponseBody()) }

        val outcome = StreamCapture(refusing, clock = { legs.size * 1_000L }) { ready }
            .run(ByteArrayOutputStream(), endAtMillis = 60_000L)

        assertThat(outcome).isEqualTo(StreamCapture.Outcome(bytes = 0, ending = Ending.Refused(403)))
    }

    @Test
    fun `a stream that stops sending anything ends the capture, saying it stopped`() = runTest {
        val stopping = providerAnswering { request ->
            ok(request, (if (legs.size == 1) "abc" else "").toResponseBody("video/mp2t".toMediaType()))
        }

        val outcome = StreamCapture(stopping, clock = { legs.size * 1_000L }) { ready }
            .run(ByteArrayOutputStream(), endAtMillis = 60_000L)

        assertThat(outcome).isEqualTo(StreamCapture.Outcome(bytes = 3, ending = Ending.StreamStopped))
    }

    /** A provider answering every leg with [reply], counted in [legs]. */
    private fun providerAnswering(reply: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        legs += request.url.toString() to request.header("User-Agent")
        reply(request)
    }.build()

    private fun ok(request: Request, body: ResponseBody) = answer(request, 200, body)

    private fun answer(request: Request, code: Int, body: ResponseBody) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Status $code")
        .body(body)
        .build()

    /** A body that sends [text] and then breaks, as a reset connection or a cut chunked body does. */
    private class BreaksAfter(private val text: String) : ResponseBody() {
        override fun contentType() = "video/mp2t".toMediaType()

        override fun contentLength() = -1L

        override fun source(): BufferedSource = object : Source {
            private var sent = false

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (sent) throw IOException("unexpected end of stream")
                sent = true
                sink.writeUtf8(text)
                return text.length.toLong()
            }

            override fun timeout() = Timeout.NONE

            override fun close() = Unit
        }.buffer()
    }

    /** Storage that fails every write with [error]. */
    private class FailingWith(private val error: Exception) : OutputStream() {
        override fun write(b: Int) = throw error

        override fun write(b: ByteArray, off: Int, len: Int) = throw error
    }

    /** Storage with room for [capacity] bytes, failing a write past that as Android's own files do. */
    private class FullAfter(private val capacity: Int) : OutputStream() {
        private var written = 0

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (written + len > capacity) throw IOException("write failed: ENOSPC (No space left on device)")
            written += len
        }
    }

    private suspend fun stalkerRecording(): Long {
        db.sources().insert(STALKER_SOURCE)
        panel.replies["handshake"] = """{"js": {"token": "t"}}"""
        panel.replies["create_link"] = link(0)
        db.channels().upsertAll(
            listOf(
                Channel(
                    sourceId = STALKER_SOURCE.id,
                    streamId = "9",
                    name = "News",
                    categoryId = null,
                    logoUrl = null,
                    epgChannelId = null,
                    number = 9,
                    streamUrl = "stalker://2/9",
                    cmd = "ffmpeg http://localhost/ch/9",
                ),
            ),
        )
        return db.recordings().insert(
            Recording(
                channelId = db.channels().everyRow().single().id,
                sourceId = STALKER_SOURCE.id,
                channelName = "News",
                title = "News",
                filePath = "/data/rec/news.ts",
                streamUrl = "stalker://2/9",
                status = RecordingStatus.RECORDING,
            ),
        )
    }

    /** A channel's link, for a test that doesn't care where it came from. */
    private val ready = Playable.Ready(
        MediaKey.Recording(1),
        url = "http://example.com/live/u/p/1.ts",
        userAgent = "OpenTV/0.1 (Android)",
        title = "News",
        isLive = true,
    )

    private fun link(n: Int) = """{"js": {"cmd": "ffmpeg http://example.com/play/9.ts?play_token=$n"}}"""
}
