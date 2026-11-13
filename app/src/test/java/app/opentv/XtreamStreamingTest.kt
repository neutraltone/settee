/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.remote.XtreamApi
import com.google.common.truth.Truth.assertThat
import java.io.RandomAccessFile
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Movie and series lists go to a file as they download and are read back a batch at a time. A big
 * panel's list once had to fit whole in memory, as a string and a JSON tree at once, which froze a
 * Fire TV Stick until Fire OS closed the app.
 */
class XtreamStreamingTest {

    @get:Rule
    val downloads = TemporaryFolder()

    @Test
    fun `a huge movie list is read a batch at a time, never whole`() = runTest {
        val api = XtreamApi(serving { GeneratedList(MOVIES, ::film).buffer().asResponseBody(JSON) }, downloads.root)
        val batches = mutableListOf<Int>()
        val names = mutableMapOf<String, String>()

        api.movies(PANEL_SOURCE).use { list ->
            val file = downloads.root.listFiles().orEmpty().single()
            assertThat(file.length()).isGreaterThan(30_000_000L)
            list.forEachBatch { batch ->
                // Renames a film near the end of the file once the first batch is out. Had the whole
                // file been read already, the old name would come through.
                if (batches.isEmpty()) renameInFile(file, LATE_FILM, "Edit")
                batches += batch.size
                batch.forEach { names[it.streamId] = it.name }
            }
        }

        assertThat(batches.sum()).isEqualTo(MOVIES)
        assertThat(batches.max()).isEqualTo(XtreamApi.CATALOGUE_BATCH)
        assertThat(names[id(LATE_FILM)]).isEqualTo("Edit ${id(LATE_FILM)}")
        assertThat(names[id(LATE_FILM - 1)]).isEqualTo("Film ${id(LATE_FILM - 1)}")
        // Closing the list deletes its download.
        assertThat(downloads.root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a series list longer than a batch arrives whole, in batches`() = runTest {
        val list = GeneratedList(1_200) { i -> """{"series_id": "$i", "name": "Show $i"}""" }
        val batches = mutableListOf<Int>()

        XtreamApi(serving { list.buffer().asResponseBody(JSON) }, downloads.root).series(PANEL_SOURCE)
            .use { it.forEachBatch { batch -> batches += batch.size } }

        assertThat(batches).containsExactly(500, 500, 200).inOrder()
    }

    @Test
    fun `a huge live channel list is read a batch at a time, never whole`() = runTest {
        val list = GeneratedList(CHANNELS) { i -> """{"stream_id": "$i", "name": "Channel $i", "num": "${i + 1}"}""" }
        val batches = mutableListOf<Int>()
        var last: Channel? = null

        XtreamApi(serving { list.buffer().asResponseBody(JSON) }, downloads.root).liveStreams(PANEL_SOURCE).use {
            assertThat(downloads.root.listFiles().orEmpty()).hasLength(1)
            it.forEachBatch { batch ->
                batches += batch.size
                last = batch.last()
            }
        }

        assertThat(batches.sum()).isEqualTo(CHANNELS)
        assertThat(batches.max()).isEqualTo(XtreamApi.CATALOGUE_BATCH)
        assertThat(last?.name).isEqualTo("Channel ${CHANNELS - 1}")
        assertThat(last?.number).isEqualTo(CHANNELS)
        assertThat(last?.streamUrl).isEqualTo("http://example.com/live/u/p/${CHANNELS - 1}.m3u8")
        assertThat(downloads.root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a list in another charset is read in that charset`() = runTest {
        val reply = """[{"stream_id": 1, "name": "Amélie"}]""".toByteArray(Charsets.ISO_8859_1)
        val api = XtreamApi(
            serving { reply.toResponseBody("application/json; charset=ISO-8859-1".toMediaType()) },
            downloads.root,
        )

        assertThat(movies(api).map { it.name }).containsExactly("Amélie")
    }

    @Test
    fun `odd entries in a list are skipped, not fatal`() = runTest {
        val api = panel(
            """
                [{"stream_id": 1, "name": "Dune"}, "junk", {"name": "No id"}, null, {"stream_id": "2", "name": "Heat"}]
            """,
        )

        assertThat(movies(api).map { it.name }).containsExactly("Dune", "Heat").inOrder()
    }

    @Test
    fun `an HTML page instead of the movie list still says it isn't a valid catalogue`() = runTest {
        val error = failure { movies(panel("<html><body>Just a moment...</body></html>")) }

        assertThat(error).hasMessageThat().contains("not a valid catalogue")
    }

    @Test
    fun `a list cut off halfway says it isn't a valid catalogue`() = runTest {
        val whole = (1..700).joinToString(",", "[", "]") { """{"stream_id": $it, "name": "Film $it"}""" }

        val error = failure { movies(panel(whole.dropLast(40))) }

        assertThat(error).hasMessageThat().contains("not a valid catalogue")
    }

    @Test
    fun `a panel that answers with an object instead of a list has no movies`() = runTest {
        assertThat(movies(panel("""{"user_info": {"auth": 0}}"""))).isEmpty()
    }

    @Test
    fun `an empty reply to the movie list says so`() = runTest {
        val error = failure { movies(panel("  ")) }

        assertThat(error).hasMessageThat().contains("empty response")
    }

    @Test
    fun `a failed download leaves no file behind`() = runTest {
        failure(java.io.IOException::class.java) { movies(panel("!")) }

        assertThat(downloads.root.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `a live list cut off partway leaves no file behind`() = runTest {
        val whole = (1..700).joinToString(",", "[", "]") { """{"stream_id": $it, "name": "Channel $it"}""" }
        val api = XtreamApi(FakePanel("get_live_streams" to whole.dropLast(40)).http, downloads.root)

        failure { api.liveStreams(PANEL_SOURCE).use { list -> list.forEachBatch {} } }

        assertThat(downloads.root.listFiles().orEmpty()).isEmpty()
    }

    private fun panel(movies: String) = XtreamApi(FakePanel("get_vod_streams" to movies).http, downloads.root)

    private suspend fun failure(
        type: Class<out Throwable> = XtreamApi.XtreamException::class.java,
        block: suspend () -> Unit,
    ): Throwable {
        val error = runCatching { block() }.exceptionOrNull()
        assertThat(error).isInstanceOf(type)
        return error!!
    }

    private suspend fun movies(api: XtreamApi): List<Movie> =
        api.movies(PANEL_SOURCE).use { list -> buildList { list.forEachBatch { addAll(it) } } }

    /** A client whose every reply is [body]. */
    private fun serving(body: () -> ResponseBody) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body())
            .build()
    }.build()

    /** Overwrites the start of film [index]'s name, which sits at a known offset since every film is as long. */
    private fun renameInFile(file: java.io.File, index: Int, word: String) {
        val start = 1L + index * (film(0).length + 1L)
        RandomAccessFile(file, "rw").use {
            it.seek(start + film(0).indexOf("Film"))
            it.write(word.toByteArray())
        }
    }

    /**
     * A JSON array of [count] elements, written only as it's read, so the test never holds the body
     * either.
     */
    private class GeneratedList(private val count: Int, private val element: (Int) -> String) : Source {
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
            return pending.read(sink, byteCount)
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }

    private companion object {
        /** About 33 MB of list: a big panel's, and far more than the reader ever holds. */
        const val MOVIES = 100_000
        const val LATE_FILM = 90_000

        /** A very large panel's line-up. */
        const val CHANNELS = 30_000
        val JSON = "application/json".toMediaType()
        val PLOT = "A long plot. ".repeat(20)

        fun id(index: Int) = "%06d".format(index)

        /** Every film is the same length, so [renameInFile] can find one. */
        fun film(index: Int) =
            """{"stream_id": "${id(index)}", "name": "Film ${id(index)}", "added": "100", "plot": "$PLOT"}"""
    }
}
