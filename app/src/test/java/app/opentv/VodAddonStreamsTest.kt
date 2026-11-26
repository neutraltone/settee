/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.ViewerError
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StreamLink
import app.opentv.data.model.StremioAddon
import app.opentv.ui.VodViewModel
import app.opentv.ui.vod.AddonSearch
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A film's Add-on sources: what the add-ons' answers come to, against a fake TMDB and fake add-ons
 * at placeholder addresses. TMDB maps the film's id 949 to IMDb's tt0113277.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodAddonStreamsTest {

    // What each add-on's host answers; TMDB knows the film unless told to drop the connection.
    private val addonReplies = mutableMapOf<String, String>()
    private var tmdbDown = false
    private val network = FakeJsonHttp { request: Request ->
        when (request.url.host) {
            "api.themoviedb.org" -> when {
                tmdbDown -> "!"
                request.url.encodedPath == "/3/movie/949" -> """{"imdb_id": "tt0113277"}"""
                request.url.encodedPath == "/3/tv/1399/external_ids" -> """{"imdb_id": "tt0000003"}"""
                else -> "404"
            }
            else -> addonReplies[request.url.host] ?: "404"
        }
    }

    @get:Rule
    val testGraph = TestGraph(name = "vod-addon-streams-test", http = network.http)

    private val app = testGraph.app
    private val graph = testGraph.graph.apply { tmdbKey = "key" }

    private val heat = Movie(
        id = 1,
        sourceId = 1,
        streamId = "1",
        name = "Heat",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 1995,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "",
        tmdbId = "949",
    )

    @After
    fun close() {
        graph.settings.stremioAddons.value.forEach { graph.settings.removeStremioAddon(it.manifestUrl) }
        graph.settings.setTorrentsEnabled(false)
    }

    private fun addon(host: String, name: String, reply: String, types: List<String> = listOf("movie")) {
        graph.settings.addStremioAddon(
            StremioAddon("https://$host/manifest.json", name, listOf("stream"), types, types),
        )
        addonReplies[host] = reply
    }

    /** What the picker ends up showing for [movie], once every add-on has answered. */
    private suspend fun search(movie: Movie): AddonSearch =
        withTimeout(10_000) { testGraph.viewModel { VodViewModel(app, graph) }.addonStreams(movie).last() }

    private val show = Series(
        id = 2,
        sourceId = 1,
        seriesId = "77",
        name = "The Show",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 2011,
        plot = null,
        backdropUrl = null,
        tmdbId = "1399",
    )

    /** What the picker ends up showing for season 2 episode 5 of [series]. */
    private suspend fun search(series: Series): AddonSearch = withTimeout(10_000) {
        val episode = testEpisode(2, 5)
        testGraph.viewModel { VodViewModel(app, graph) }.addonStreams(series, episode).last()
    }

    private fun streams(vararg urls: String) =
        """{"streams": [${urls.joinToString { """{"url": "$it", "title": "1080p"}""" }}]}"""

    @Test
    fun `when every add-on fails, the lookup failed rather than found nothing, saying why the first did`() =
        runBlocking {
            addon("films.example.com", "Films", "503")
            addon("more.example.com", "More films", "!")

            val found = search(heat)

            assertThat(found).isEqualTo(
                AddonSearch.Results(
                    streams = emptyList(),
                    finished = true,
                    failure = ViewerError(
                        "The add-on is having problems. That's at its end, so try again later.",
                        "HTTP 503",
                    ),
                ),
            )
        }

    @Test
    fun `one add-on failing doesn't sink another's streams`() = runBlocking {
        addon("broken.example.com", "Broken", "!")
        addon("films.example.com", "Films", streams("https://example.com/heat.mkv"))

        val found = search(heat)

        found as AddonSearch.Results
        assertThat(found.streams.map { it.stream.link to it.stream.addonName })
            .containsExactly(StreamLink.Direct("https://example.com/heat.mkv") to "Films")
        assertThat(found.finished).isTrue()
    }

    @Test
    fun `a film TMDB can't match to IMDb says the add-ons can't look it up, without asking them`() = runBlocking {
        addon("films.example.com", "Films", streams("https://example.com/heat.mkv"))

        val found = search(heat.copy(tmdbId = "1"))

        assertThat(found).isEqualTo(
            AddonSearch.CantAsk(
                ViewerError("Your add-ons can't look this film up, as it has no match on IMDb.", "NoImdbMatch"),
                canRetry = false,
            ),
        )
        assertThat(network.hosts()).doesNotContain("films.example.com")
    }

    @Test
    fun `when TMDB doesn't answer, the lookup failed and says so, without blaming the add-ons`() = runBlocking {
        addon("films.example.com", "Films", streams("https://example.com/heat.mkv"))
        tmdbDown = true

        val found = search(heat)

        assertThat(found).isEqualTo(
            AddonSearch.CantAsk(
                ViewerError(
                    "Couldn't look this film up to ask your add-ons. Check this device is online, then try again.",
                    "IO",
                ),
                canRetry = true,
            ),
        )
        assertThat(network.hosts()).doesNotContain("films.example.com")
    }

    @Test
    fun `torrents are offered, with whether the Torrents switch is on`() = runBlocking {
        addon("films.example.com", "Films", """{"streams": [{"infoHash": "abc", "name": "1080p"}]}""")

        val off = search(heat) as AddonSearch.Results
        graph.settings.setTorrentsEnabled(true)
        val on = search(heat) as AddonSearch.Results

        assertThat(off.streams.map { it.stream.link }).containsExactly(StreamLink.Torrent("abc"))
        assertThat(off.torrentsOn).isFalse()
        assertThat(on.torrentsOn).isTrue()
    }

    @Test
    fun `an episode's add-ons are asked by the show's IMDb id from TMDB, with its season and episode`() =
        runBlocking<Unit> {
            addon("shows.example.com", "Shows", streams("https://example.com/s2e5.mkv"), types = listOf("series"))

            val found = search(show) as AddonSearch.Results

            val links = found.streams.map { it.stream.link }
            assertThat(links).containsExactly(StreamLink.Direct("https://example.com/s2e5.mkv"))
            assertThat(network.asked.filter { it.url.host == "shows.example.com" }.map { it.url.encodedPath })
                .containsExactly("/stream/series/tt0000003:2:5.json")
        }

    @Test
    fun `a show that is already known by its IMDb id is asked by it, without TMDB`() = runBlocking<Unit> {
        addon("shows.example.com", "Shows", streams("https://example.com/s2e5.mkv"), types = listOf("series"))

        search(show.copy(seriesId = "tt0000009", tmdbId = null))

        assertThat(network.asked.map { it.url.encodedPath }).containsExactly("/stream/series/tt0000009:2:5.json")
    }

    @Test
    fun `a show TMDB can't match to IMDb says the add-ons can't look the show up`() = runBlocking {
        addon("shows.example.com", "Shows", streams("https://example.com/s2e5.mkv"), types = listOf("series"))

        assertThat(search(show.copy(tmdbId = "1"))).isEqualTo(
            AddonSearch.CantAsk(
                ViewerError("Your add-ons can't look this show up, as it has no match on IMDb.", "NoImdbMatch"),
                canRetry = false,
            ),
        )
    }

    @Test
    fun `when TMDB doesn't answer about a show, the lookup says so about the show`() = runBlocking {
        addon("shows.example.com", "Shows", streams("https://example.com/s2e5.mkv"), types = listOf("series"))
        tmdbDown = true

        assertThat(search(show)).isEqualTo(
            AddonSearch.CantAsk(
                ViewerError(
                    "Couldn't look this show up to ask your add-ons. Check this device is online, then try again.",
                    "IO",
                ),
                canRetry = true,
            ),
        )
    }
}
