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

/** The release and air dates Xtream panels give, read from the JSON shapes they give them in. */
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

        val movies = api.movies(source).associate { it.name to it.releaseDate }

        assertThat(movies).containsExactly("Dune", 1_710_460_800_000L, "Drive", 1_314_921_600_000L, "Heat", null)
    }

    @Test
    fun `a movie's detail gives its full release date`() = runTest {
        val api = panel("get_vod_info" to """{"info": {"releasedate": "2011-09-02", "year": "2011"}}""")

        assertThat(api.movieInfo(source, "2")?.releaseDate).isEqualTo(1_314_921_600_000L)
    }

    @Test
    fun `a show's latest air date is its newest episode's, across seasons`() = runTest {
        val api = panel(
            "get_series_info" to """
                {
                  "info": {"name": "Show", "releaseDate": "2012-01-05"},
                  "episodes": {
                    "1": [{"id": "11", "info": {"air_date": "2012-01-05"}}],
                    "2": [
                      {"id": "21", "info": {"releasedate": "2024-03-15"}},
                      {"id": "22", "info": {"air_date": "2011-09-02"}},
                      {"id": "23", "info": {}}
                    ]
                  }
                }
            """,
        )

        val info = api.seriesInfo(source, "7")

        assertThat(info?.latestAirDate).isEqualTo(1_710_460_800_000L)
        assertThat(info?.releaseDate).isEqualTo(1_325_721_600_000L)
    }
}
