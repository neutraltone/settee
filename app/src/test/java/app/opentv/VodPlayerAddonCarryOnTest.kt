/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Episode
import app.opentv.data.model.StremioAddon
import app.opentv.data.repo.AddonTitle
import app.opentv.data.repo.MediaKey
import app.opentv.ui.vod.VodPlayerViewModel
import com.google.common.truth.Truth.assertThat
import okhttp3.Request
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * After an add-on's stream of an episode, the Next Episode carries on with the same add-on's stream of
 * the same kind (its bingeGroup), as Stremio does, rather than stopping at the picker (ADR 0011). The
 * add-ons and IMDb ids are made up.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which the view model doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodPlayerAddonCarryOnTest {

    /** What the add-on answers for the second episode. */
    private var secondEpisode = streams(stream(OTHER_720, "shows-720p"), stream(NEXT_1080, BINGE))

    private val network = FakeJsonHttp { request: Request ->
        if (request.url.encodedPath == "/stream/series/tt0000201:1:2.json") secondEpisode else "404"
    }

    @get:Rule
    val testGraph = TestGraph(http = network.http)

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val playback = FakePlayback()
    private lateinit var vm: VodPlayerViewModel

    @After
    fun forgetAddons() {
        graph.settings.stremioAddons.value.forEach { graph.settings.removeStremioAddon(it.manifestUrl) }
    }

    /** Plays the pilot from the Shows add-on's [bingeGroup] stream to its end; returns the second episode. */
    private fun watchPilotToTheEnd(bingeGroup: String? = BINGE): Episode {
        lateinit var second: Episode
        testGraph.runOnMain {
            graph.settings.addStremioAddon(
                StremioAddon(SHOWS, "Shows", listOf("stream"), listOf("series"), listOf("series")),
            )
            val (pilot, next) = keptEpisodes()
            second = next
            vm = testGraph.track(VodPlayerViewModel(app, graph))
            vm.attach(MediaKey.AddonStream(pilot.key, PILOT, addonName = "Shows", bingeGroup = bingeGroup), playback)
            vm.playToEnd(playback)
        }
        return second
    }

    @Test
    fun `the next episode plays the same add-on's stream of the same kind by itself`() {
        val second = watchPilotToTheEnd()

        assertThat(playback.played.last().first)
            .isEqualTo(MediaKey.AddonStream(second.key, NEXT_1080, addonName = "Shows", bingeGroup = BINGE))
        assertThat(vm.addonPick.value).isNull()
    }

    @Test
    fun `with no stream of the same kind, the next episode waits on its picker`() {
        secondEpisode = streams(stream(OTHER_720, "shows-720p"))

        val second = watchPilotToTheEnd()

        assertThat(playback.played.map { it.first }).hasSize(1)
        assertThat(vm.addonPick.value).isEqualTo(second.key)
    }

    @Test
    fun `a stream with no bingeGroup leaves the next episode to its picker`() {
        val second = watchPilotToTheEnd(bingeGroup = null)

        assertThat(playback.played.map { it.first }).hasSize(1)
        assertThat(vm.addonPick.value).isEqualTo(second.key)
    }

    private suspend fun keptEpisodes(): Pair<Episode, Episode> {
        val show = graph.addonTitles.keepShow(SHOW)
        graph.database.episodes().upsertAll(
            listOf(1 to "Pilot", 2 to "Second").map { (number, title) ->
                Episode(
                    sourceId = show.sourceId,
                    seriesId = show.seriesId,
                    episodeId = "${show.seriesId}:1:$number",
                    season = 1,
                    episodeNumber = number,
                    title = title,
                    plot = null,
                    durationSeconds = 2_400,
                    stillUrl = null,
                    streamUrl = "",
                )
            },
        )
        val stored = graph.database.episodes().forSeries(show.sourceId, show.seriesId).sortedBy { it.episodeNumber }
        return stored[0] to stored[1]
    }

    private companion object {
        const val SHOWS = "https://shows.example.com/manifest.json"
        const val BINGE = "shows-1080p"
        const val PILOT = "https://example.com/s1e1-1080.mkv"
        const val NEXT_1080 = "https://example.com/s1e2-1080.mkv"
        const val OTHER_720 = "https://example.com/s1e2-720.mkv"

        val SHOW = AddonTitle(
            tmdbId = "201",
            imdbId = "tt0000201",
            name = "Lost Show",
            year = 2010,
            posterUrl = null,
            backdropUrl = null,
            overview = null,
            isMovie = false,
        )

        fun stream(url: String, bingeGroup: String) =
            """{"url": "$url", "title": "Episode", "behaviorHints": {"bingeGroup": "$bingeGroup"}}"""

        fun streams(vararg entries: String) = """{"streams": [${entries.joinToString()}]}"""
    }
}
