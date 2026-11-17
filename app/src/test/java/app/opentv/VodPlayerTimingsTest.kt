/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Series
import app.opentv.player.NextEpisodeCountdown.Overlay
import app.opentv.ui.vod.VodPlayerViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The episode player's Next Episode card, placed by the timings TheIntroDB and IntroDB have (#517). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodPlayerTimingsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

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

    private val graph = ServiceLocator.Graph(app, http = http).apply { tmdbKey = "key" }
    private val playback = FakePlayback()

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun close() {
        graph.settings.setSkipIntrosAndCredits(true)
        graph.database.close()
        Dispatchers.resetMain()
    }

    private suspend fun startSeries(): VodPlayerViewModel {
        graph.database.series().upsertAll(listOf(show))
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        return VodPlayerViewModel(app, graph).apply { attach(testEpisode(1, 1).key, playback) }
    }

    private suspend fun VodPlayerViewModel.at(millis: Long): Overlay {
        playback.positionMillis = millis
        poll()
        return overlay.value
    }

    /** Polls at [millis] until the timings, looked up beside playback, have placed the card. */
    private suspend fun VodPlayerViewModel.waitForCardAt(millis: Long) = withContext(Dispatchers.Default) {
        withTimeout(10_000) {
            while (at(millis) == Overlay.None) delay(10)
        }
    }

    @Test
    fun `on a timed episode the card comes up 3 seconds into the credits and counts down from 15`() = runTest {
        val vm = startSeries()

        vm.waitForCardAt(CREDITS_MILLIS + 3_000)

        assertThat(vm.at(CREDITS_MILLIS + 3_000)).isEqualTo(Overlay.Card(secondsLeft = 15, counting = true))
        assertThat(vm.at(CREDITS_MILLIS + 2_000)).isEqualTo(Overlay.None)
        assertThat(askedHosts.first { it != "api.themoviedb.org" }).isEqualTo("api.theintrodb.org")
    }

    @Test
    fun `with Skip intros and credits off nothing is asked and the card comes up 30 seconds before the end`() =
        runTest {
            graph.settings.setSkipIntrosAndCredits(false)
            val vm = startSeries()

            assertThat(vm.at(CREDITS_MILLIS + 3_000)).isEqualTo(Overlay.None)
            assertThat(vm.at(EPISODE_MILLIS - 30_000)).isEqualTo(Overlay.Card(secondsLeft = 15, counting = true))
            assertThat(askedHosts).isEmpty()
        }

    @Test
    fun `with no network the card comes up 30 seconds before the end, with nothing else shown`() = runTest {
        theIntroDb = "!"
        val vm = startSeries()
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { while ("api.theintrodb.org" !in askedHosts) delay(10) }
        }

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
