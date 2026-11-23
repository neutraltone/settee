/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Series
import app.opentv.player.NextEpisodeCountdown.Overlay
import app.opentv.ui.vod.VodPlayerViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The episode player's Next Episode card, placed by the timings TheIntroDB and IntroDB have (#517). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodPlayerTimingsTest {

    /** What TheIntroDB answers; IntroDB and TMDB answer with nothing. `"!"` fails every request. */
    @Volatile private var theIntroDb = """{"credits":[{"start_ms":2280000,"end_ms":null}]}"""
    private val network = FakeJsonHttp { request ->
        when {
            theIntroDb == "!" -> "!"
            request.url.host == "api.theintrodb.org" -> theIntroDb
            else -> "{}"
        }
    }
    private val http = network.http
    private val askedHosts get() = network.hosts()

    @get:Rule
    val testGraph = TestGraph(http = http)

    private val app = testGraph.app
    private val graph = testGraph.graph.apply { tmdbKey = "key" }
    private val playback = FakePlayback()

    @After
    fun close() {
        graph.settings.setSkipIntrosAndCredits(true)
    }

    private fun startSeries(): VodPlayerViewModel {
        runBlocking {
            graph.database.series().upsertAll(listOf(show))
            graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        }
        val vm = testGraph.track(VodPlayerViewModel(app, graph))
        // As the player's screen attaches it: blocked in runBlocking, the main thread would sit out the
        // title index's pause in real time, where a settle skips it.
        testGraph.runOnMain { vm.attach(testEpisode(1, 1).key, playback) }
        return vm
    }

    private fun VodPlayerViewModel.at(millis: Long): Overlay {
        playback.positionMillis = millis
        runBlocking { poll() }
        return overlay.value
    }

    /** Waits for the timings, looked up beside playback, to place the card, and polls at [millis]. */
    private fun VodPlayerViewModel.waitForCardAt(millis: Long) {
        arrived()
        assertThat(at(millis)).isNotEqualTo(Overlay.None)
    }

    /** The timings, once the lookup started beside playback has finished, which places them on this thread. */
    private fun VodPlayerViewModel.arrived() {
        testGraph.awaitIdle()
        runBlocking { timingsArrived() }
    }

    @Test
    fun `on a timed episode the card comes up 3 seconds into the credits and counts down from 15`() {
        val vm = startSeries()

        vm.waitForCardAt(CREDITS_MILLIS + 3_000)

        assertThat(vm.at(CREDITS_MILLIS + 3_000)).isEqualTo(Overlay.Card(secondsLeft = 15, counting = true))
        assertThat(vm.at(CREDITS_MILLIS + 2_000)).isEqualTo(Overlay.None)
        assertThat(askedHosts.first { it != "api.themoviedb.org" }).isEqualTo("api.theintrodb.org")
    }

    @Test
    fun `with Skip intros and credits off nothing is asked and the card comes up 30 seconds before the end`() {
        graph.settings.setSkipIntrosAndCredits(false)
        val vm = startSeries()

        assertThat(vm.at(CREDITS_MILLIS + 3_000)).isEqualTo(Overlay.None)
        assertThat(vm.at(EPISODE_MILLIS - 30_000)).isEqualTo(Overlay.Card(secondsLeft = 15, counting = true))
        assertThat(askedHosts).isEmpty()
    }

    @Test
    fun `with no network the card comes up 30 seconds before the end, with nothing else shown`() {
        theIntroDb = "!"
        val vm = startSeries()
        vm.arrived()
        assertThat(askedHosts).contains("api.theintrodb.org")

        assertThat(vm.at(CREDITS_MILLIS + 3_000)).isEqualTo(Overlay.None)
        assertThat(vm.at(EPISODE_MILLIS - 30_000)).isEqualTo(Overlay.Card(secondsLeft = 15, counting = true))
    }

    private val show = Series(
        sourceId = 1,
        seriesId = "s",
        name = "The Show",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        tmdbId = "1396",
    )

    private companion object {
        /** Where TheIntroDB times the credits: 38:00 into the 40-minute episode. */
        const val CREDITS_MILLIS = 2_280_000L
    }
}
