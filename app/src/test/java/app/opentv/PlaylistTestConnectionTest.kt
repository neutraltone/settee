/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ErrorWording
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.SourceRepository
import com.google.common.truth.Truth.assertThat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Test connection on an M3U playlist fetches it, as Save would, rather than trusting any address typed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rGB", application = android.app.Application::class)
class PlaylistTestConnectionTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val test = TestCatalog(context)
    private val wording = ErrorWording(context, onTelevision = true)

    /** What the playlist's address answers: a status and a body. */
    private var answer: () -> Response.Builder = { ok("#EXTM3U\n") }

    /** Every request the playlist's address got. */
    private val asked = mutableListOf<okhttp3.Request>()

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        asked += chain.request()
        answer().request(chain.request()).protocol(Protocol.HTTP_1_1).build()
    }.build()

    private val sources = SourceRepository(
        test.db.sources(),
        XtreamApi(http),
        StalkerApi(http),
        http,
        test.settings,
    )

    private val playlist = Source(name = "Playlist", kind = SourceKind.M3U, url = "http://example.com/list.m3u")

    @After
    fun close() = test.close()

    @Test
    fun `a playlist address that answers not found fails, with the HTTP status on the detail line`() = testing {
        answer = { status(404, "Not Found") }

        val result = sources.test(playlist)

        val failure = wording.ofProvider(result.exceptionOrNull()!!, R.string.error_test_failed)
        assertThat(failure.detail).contains("HTTP 404")
    }

    @Test
    fun `a web page at the address fails as there being no channels there`() = testing {
        answer = { ok("<!DOCTYPE html>\n<html><head><title>Login</title></head><body>Sign in</body></html>") }

        val result = sources.test(playlist)

        val failure = wording.ofProvider(result.exceptionOrNull()!!, R.string.error_test_failed)
        assertThat(failure.sentence).isEqualTo(context.getString(R.string.error_playlist_empty))
        assertThat(failure.detail).isEqualTo("NotAPlaylist")
    }

    @Test
    fun `a playlist passes from its first lines, without downloading the whole of a big one`() = testing {
        val body = HugePlaylist(totalBytes = 50L * 1024 * 1024)
        answer = { status(200, "OK").body(body.buffer().asResponseBody("audio/x-mpegurl".toMediaType())) }

        val result = sources.test(playlist)

        assertThat(result.isSuccess).isTrue()
        assertThat(body.served).isLessThan(1024L * 1024)
    }

    @Test
    fun `the playlist is fetched as the provider's own player, with the User-Agent set on the source`() = testing {
        sources.test(playlist.copy(userAgent = "VLC/3.0.20"))

        assertThat(asked.single().header("User-Agent")).isEqualTo("VLC/3.0.20")
    }

    private fun ok(body: String) = status(200, "OK", body)

    private fun status(code: Int, message: String, body: String = "") = Response.Builder()
        .code(code)
        .message(message)
        .body(body.toResponseBody("audio/x-mpegurl".toMediaType()))
}

/** `#EXTM3U` then channel lines up to [totalBytes], counting how much of it was [served]. */
private class HugePlaylist(private val totalBytes: Long) : okio.Source {
    var served = 0L
        private set

    private val line = "#EXTINF:-1 group-title=\"News\",Channel\nhttp://example.com/live/u/p/1.ts\n".encodeToByteArray()

    override fun read(sink: okio.Buffer, byteCount: Long): Long {
        if (served >= totalBytes) return -1
        val start = if (served == 0L) "#EXTM3U\n".encodeToByteArray() else line
        sink.write(start)
        served += start.size
        return start.size.toLong()
    }

    override fun timeout() = okio.Timeout.NONE

    override fun close() = Unit
}
