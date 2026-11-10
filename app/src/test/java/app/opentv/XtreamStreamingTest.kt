/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Movie
import app.opentv.data.remote.XtreamApi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Test

/**
 * Movie and series lists are read off the wire a batch at a time. A big panel's list once had to fit
 * whole in memory, as a string and a JSON tree at once, which froze a Fire TV Stick until Fire OS
 * closed the app.
 */
class XtreamStreamingTest {

    @Test
    fun `a huge movie list is saved in batches while it is still downloading`() = runTest {
        val list = GeneratedList(MOVIES) { i ->
            """{"stream_id": $i, "name": "Film $i", "added": "100", "plot": "${"A long plot. ".repeat(20)}"}"""
        }
        val api = XtreamApi(serving(list))
        val batches = mutableListOf<Int>()
        var readByFirstBatch = -1L
        var names = 0

        api.movies(PANEL_SOURCE) { batch ->
            if (batches.isEmpty()) readByFirstBatch = list.bytesRead
            batches += batch.size
            names += batch.count { it.name.startsWith("Film ") }
        }

        assertThat(batches.sum()).isEqualTo(MOVIES)
        assertThat(names).isEqualTo(MOVIES)
        assertThat(batches.max()).isEqualTo(XtreamApi.CATALOGUE_BATCH)
        // The first 500 were saved having read a sliver of the body, not after reading all of it.
        assertThat(list.bytesRead).isGreaterThan(30_000_000L)
        assertThat(readByFirstBatch).isLessThan(list.bytesRead / 50)
    }

    @Test
    fun `a series list longer than a batch arrives whole, in batches`() = runTest {
        val list = GeneratedList(1_200) { i -> """{"series_id": "$i", "name": "Show $i"}""" }
        val batches = mutableListOf<Int>()

        XtreamApi(serving(list)).series(PANEL_SOURCE) { batches += it.size }

        assertThat(batches).containsExactly(500, 500, 200).inOrder()
    }

    @Test
    fun `odd entries in a streamed list are skipped, not fatal`() = runTest {
        val api = XtreamApi(
            FakePanel(
                "get_vod_streams" to """
                    [{"stream_id": 1, "name": "Dune"}, "junk", {"name": "No id"}, null, {"stream_id": "2", "name": "Heat"}]
                """,
            ).http,
        )

        assertThat(movies(api).map { it.name }).containsExactly("Dune", "Heat").inOrder()
    }

    @Test
    fun `an HTML page instead of the movie list still says it isn't a valid catalogue`() = runTest {
        val api = XtreamApi(FakePanel("get_vod_streams" to "<html><body>Just a moment...</body></html>").http)

        val error = failure { movies(api) }

        assertThat(error).hasMessageThat().contains("not a valid catalogue")
    }

    @Test
    fun `a list cut off halfway fails, keeping the batches before it`() = runTest {
        val whole = (1..700).joinToString(",", "[", "]") { """{"stream_id": $it, "name": "Film $it"}""" }
        val api = XtreamApi(FakePanel("get_vod_streams" to whole.dropLast(40)).http)
        val saved = mutableListOf<Movie>()

        val error = failure { api.movies(PANEL_SOURCE) { saved += it } }

        assertThat(error).hasMessageThat().contains("not a valid catalogue")
        assertThat(saved).hasSize(500)
    }

    @Test
    fun `a panel that answers with an object instead of a list has no movies`() = runTest {
        val api = XtreamApi(FakePanel("get_vod_streams" to """{"user_info": {"auth": 0}}""").http)

        assertThat(movies(api)).isEmpty()
    }

    @Test
    fun `an empty reply to the movie list says so`() = runTest {
        val api = XtreamApi(FakePanel("get_vod_streams" to "  ").http)

        val error = failure { movies(api) }

        assertThat(error).hasMessageThat().contains("empty response")
    }

    private suspend fun failure(block: suspend () -> Unit): Throwable {
        val error = runCatching { block() }.exceptionOrNull()
        assertThat(error).isInstanceOf(XtreamApi.XtreamException::class.java)
        return error!!
    }

    private suspend fun movies(api: XtreamApi): List<Movie> = buildList { api.movies(PANEL_SOURCE) { addAll(it) } }

    /** A client whose every reply is [list], streamed as it is generated. */
    private fun serving(list: GeneratedList) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(list.buffer().asResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    /**
     * A JSON array of [count] elements, written only as it's read, so the test never holds the body
     * either; [bytesRead] says how far the reader has got.
     */
    private class GeneratedList(private val count: Int, private val element: (Int) -> String) : Source {
        var bytesRead = 0L
            private set
        private var next = 0
        private val pending = Buffer().writeUtf8("[")

        override fun read(sink: Buffer, byteCount: Long): Long {
            while (pending.size == 0L && next <= count) {
                pending.writeUtf8(
                    when {
                        next == count -> "]"
                        next == 0 -> element(next)
                        else -> "," + element(next)
                    },
                )
                next++
            }
            if (pending.size == 0L) return -1
            return pending.read(sink, byteCount).also { bytesRead += it }
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }

    private companion object {
        /** About 33 MB of list: a big panel's, and far more than the reader ever holds. */
        const val MOVIES = 100_000
    }
}
