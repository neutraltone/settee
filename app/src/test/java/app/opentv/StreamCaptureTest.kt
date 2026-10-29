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
import app.opentv.data.repo.PlaybackSources
import app.opentv.recording.StreamCapture
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
    private val sources = PlaybackSources(db, XtreamApi(panel.http), StalkerApi(panel.http))

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
        assertThat(outcome).isEqualTo(StreamCapture.Outcome(bytes = 9, failure = null))
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
                channelId = db.channels().allForMatching().single().id,
                sourceId = STALKER_SOURCE.id,
                channelName = "News",
                title = "News",
                filePath = "/data/rec/news.ts",
                streamUrl = "stalker://2/9",
                status = RecordingStatus.RECORDING,
            ),
        )
    }

    private fun link(n: Int) = """{"js": {"cmd": "ffmpeg http://example.com/play/9.ts?play_token=$n"}}"""
}
