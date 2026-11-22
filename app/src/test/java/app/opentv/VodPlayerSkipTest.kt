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
import app.opentv.player.SkipCue.Kind
import app.opentv.player.SkipCue.Stretch
import app.opentv.ui.vod.VodPlayerViewModel
import app.opentv.ui.vod.controlsShowing
import app.opentv.ui.vod.skipStretch
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The episode player's Skip intro and Skip recap, from the timings TheIntroDB has (#518). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodPlayerSkipTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** TheIntroDB times a recap, an intro well after it, and credits; IntroDB and TMDB answer with nothing. */
    private val network = FakeJsonHttp { request ->
        if (request.url.host == "api.theintrodb.org") {
            """
            {"recap":[{"start_ms":null,"end_ms":30000}],
             "intro":[{"start_ms":$INTRO_START,"end_ms":$INTRO_END}],
             "credits":[{"start_ms":2280000,"end_ms":null}]}
            """.trimIndent()
        } else {
            "{}"
        }
    }
    private val graph = ServiceLocator.Graph(app, http = network.http).apply { tmdbKey = "key" }
    private val playback = FakePlayback()

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    private val viewModels = ViewModelsInTest()

    @After
    fun close() {
        graph.settings.setSkipIntrosAndCredits(true)
        viewModels.stopAll()
        runBlocking { graph.stopBackgroundWork() }
        graph.database.close()
        Dispatchers.resetMain()
    }

    private suspend fun startSeries(): VodPlayerViewModel {
        graph.database.series().upsertAll(listOf(show))
        graph.database.episodes().upsertAll((1..3).map { testEpisode(1, it) })
        return viewModels.track(VodPlayerViewModel(app, graph)).apply { attach(testEpisode(1, 1).key, playback) }
    }

    private suspend fun VodPlayerViewModel.at(millis: Long): Stretch? {
        playback.positionMillis = millis
        poll()
        return skipButton.value
    }

    /** Waits for the timings, looked up beside playback, and polls at [millis] to put the button up. */
    private suspend fun VodPlayerViewModel.waitForButtonAt(millis: Long) {
        timingsArrived()
        assertThat(at(millis)).isNotNull()
    }

    @Test
    fun `inside a timed intro Skip intro shows, and skipping lands at the intro's end`() = runTest {
        val vm = startSeries()
        vm.waitForButtonAt(INTRO_START + 1_000)

        assertThat(vm.skipButton.value).isEqualTo(Stretch(Kind.INTRO, INTRO_START, INTRO_END))

        vm.skipStretch()

        assertThat(playback.positionMillis).isEqualTo(INTRO_END)
        assertThat(vm.skipButton.value).isNull()
        assertThat(vm.at(INTRO_END)).isNull()
    }

    @Test
    fun `a recap with no start timed shows Skip recap from the beginning`() = runTest {
        val vm = startSeries()
        vm.waitForButtonAt(1_000)

        assertThat(vm.skipButton.value).isEqualTo(Stretch(Kind.RECAP, 0, 30_000))
    }

    @Test
    fun `the button goes after 10 seconds, and comes back with the controls`() = runTest {
        val vm = startSeries()
        vm.waitForButtonAt(INTRO_START)

        assertThat(vm.at(INTRO_START + 10_000)).isNull()

        vm.controlsShowing(true)

        assertThat(vm.skipButton.value).isEqualTo(Stretch(Kind.INTRO, INTRO_START, INTRO_END))

        vm.controlsShowing(false)

        assertThat(vm.skipButton.value).isNull()
    }

    @Test
    fun `nothing is skipped without a press`() = runTest {
        val vm = startSeries()
        vm.waitForButtonAt(INTRO_START)

        vm.at(INTRO_START + 5_000)

        assertThat(playback.positionMillis).isEqualTo(INTRO_START + 5_000)
    }

    @Test
    fun `with Skip intros and credits off there's no button`() = runTest {
        graph.settings.setSkipIntrosAndCredits(false)
        val vm = startSeries()

        assertThat(vm.at(INTRO_START + 1_000)).isNull()
        assertThat(network.hosts()).isEmpty()
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
        const val INTRO_START = 120_000L
        const val INTRO_END = 170_000L
    }
}
