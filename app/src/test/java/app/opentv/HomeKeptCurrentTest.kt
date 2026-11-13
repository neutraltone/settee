/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.HomeState
import app.opentv.ui.HomeViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's rows stay current while another screen covers it, so Back draws them at once without
 * drawing any that have since moved or gone (#192).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeKeptCurrentTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)
    private val db = graph.database
    private val viewModels = ViewModelsInTest()

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
        val rows = listOf("UK| ITV HD", "UK| BBC One HD").mapIndexed { i, name ->
            Channel(
                sourceId = 1,
                streamId = "$i",
                name = name,
                categoryId = null,
                logoUrl = null,
                epgChannelId = null,
                number = null,
                streamUrl = "http://example.com/live/u/p/$i.ts",
            )
        }
        db.channels().replaceCatalogue(1, rows, syncStamp = 1)
        graph.catalogRepository.renormalizeAll()
    }

    @After
    fun close() {
        runBlocking { viewModels.stopAll() }
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun row(name: String): Channel = db.channels().everyRow().single { it.name == name }

    private fun HomeState?.channelNames() = this?.favouriteChannels?.map { it.channel.primary.name }

    /**
     * What Home would draw the moment it's shown again, once [done] holds: read without collecting, as
     * nothing is showing Home while a screen covers it. Null if it never does.
     */
    private suspend fun HomeViewModel.whileCovered(done: (HomeState?) -> Boolean): HomeState? =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(5_000) {
                while (!done(state.value)) delay(10)
                state.value
            }
        }

    @Test
    fun `after a long time away, Home has its rows ready, with a Favourite added meanwhile`() = runTest {
        graph.viewerLibrary.favourites.set(row("UK| ITV HD"), true)
        val home = viewModels.track(HomeViewModel(app, graph))
        // Home shown, then covered by Settings for longer than it used to keep its rows.
        home.state.first { it.channelNames() == listOf("UK| ITV HD") }
        advanceTimeBy(15_000)

        graph.viewerLibrary.favourites.set(row("UK| BBC One HD"), true)

        val shown = home.whileCovered { it.channelNames()?.size == 2 }
        assertThat(shown.channelNames()).containsExactly("UK| ITV HD", "UK| BBC One HD")
    }

    @Test
    fun `a film finished while Home was covered is already off Continue Watching when Home is back`() = runTest {
        db.movies().upsertAll(listOf(superbad))
        graph.viewerLibrary.recordProgress("movie:1", positionMillis = FILM_MILLIS / 2, durationMillis = FILM_MILLIS)
        val home = viewModels.track(HomeViewModel(app, graph))
        // Home shown with the film on Continue Watching, then covered by the player for a long time.
        home.state.first { it?.continueWatching?.map { item -> item.name } == listOf(superbad.name) }
        advanceTimeBy(15_000)

        graph.viewerLibrary.recordProgress("movie:1", positionMillis = FILM_MILLIS, durationMillis = FILM_MILLIS)

        val shown = home.whileCovered { it != null && it.continueWatching.isEmpty() }
        assertThat(shown).isNotNull()
        assertThat(shown!!.continueWatching).isEmpty()
    }

    private val superbad = Movie(
        id = 1,
        sourceId = 1,
        streamId = "1",
        name = "EN - Superbad (2007)",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 2007,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/1",
    )

    private companion object {
        const val FILM_MILLIS = 6_000_000L
    }
}
