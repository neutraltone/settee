/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.remote.XtreamApi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The release dates Xtream panels give for movies, read from the JSON shapes they give them in. */
class XtreamDatesTest {

    private val source = PANEL_SOURCE

    private fun panel(vararg replies: Pair<String, String>) = XtreamApi(FakePanel(*replies).http)

    @Test
    fun `a movie keeps the full release date from the list, under either spelling`() = runTest {
        val api = panel(
            "get_vod_streams" to """
                [
                  {"stream_id": 1, "name": "Dune", "releaseDate": "2024-03-15"},
                  {"stream_id": 2, "name": "Drive", "releasedate": "2011-09-02"},
                  {"stream_id": 3, "name": "Heat", "year": "1995"}
                ]
            """,
        )

        val movies = api.movies(source).use { list -> buildList { list.forEachBatch { addAll(it) } } }.associate {
            it.name to
                it.releaseDate
        }

        assertThat(movies).containsExactly("Dune", 1_710_460_800_000L, "Drive", 1_314_921_600_000L, "Heat", null)
    }

    @Test
    fun `a movie's detail gives its full release date`() = runTest {
        val api = panel("get_vod_info" to """{"info": {"releasedate": "2011-09-02", "year": "2011"}}""")

        assertThat(api.movieInfo(source, "2")?.releaseDate).isEqualTo(1_314_921_600_000L)
    }
}
