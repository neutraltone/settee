/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.Profiles
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Profile
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.HomeState
import app.opentv.ui.HomeViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        // The settings outlive the test; the Profile mustn't leak into the next one.
        graph.settings.setActiveProfile(Profiles.BASE_PROFILE_ID)
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

    /** Home drawn until [rows] hold, then covered by a screen: Settings when [keptLive], else a player. */
    private suspend fun HomeViewModel.shownThenCovered(keptLive: Boolean, rows: (HomeState?) -> Boolean) {
        state.first(rows)
        graph.homeKeptLive.value = keptLive
    }

    private fun HomeState?.continuing() = this?.continueWatching?.map { it.name }

    @Test
    fun `after a long time in Settings, Home has its rows ready, with a Favourite added meanwhile`() = runTest {
        graph.viewerLibrary.favourites.set(row("UK| ITV HD"), true)
        val home = viewModels.track(HomeViewModel(app, graph))
        home.shownThenCovered(keptLive = true) { it.channelNames() == listOf("UK| ITV HD") }
        // Longer away than Home once kept its rows.
        advanceTimeBy(15_000)

        graph.viewerLibrary.favourites.set(row("UK| BBC One HD"), true)

        val shown = home.whileCovered { it.channelNames()?.size == 2 }
        assertThat(shown.channelNames()).containsExactly("UK| ITV HD", "UK| BBC One HD")
    }

    @Test
    fun `a film marked watched on its page is already off Continue Watching when Home is back`() = runTest {
        startSuperbad()
        val home = viewModels.track(HomeViewModel(app, graph))
        home.shownThenCovered(keptLive = true) { it.continuing() == listOf(superbad.name) }
        advanceTimeBy(15_000)

        graph.viewerLibrary.recordProgress("movie:1", positionMillis = FILM_MILLIS, durationMillis = FILM_MILLIS)

        val shown = home.whileCovered { it != null && it.continueWatching.isEmpty() }
        assertThat(shown).isNotNull()
        assertThat(shown!!.continueWatching).isEmpty()
    }

    @Test
    fun `after a long time in a player, Home has dropped its rows, so Back waits for fresh ones`() = runTest {
        // The player saves its last position only as it closes, after Home is drawn again: rows kept
        // until then could still show the film it just finished.
        startSuperbad()
        val home = viewModels.track(HomeViewModel(app, graph))
        home.shownThenCovered(keptLive = false) { it.continuing() == listOf(superbad.name) }

        advanceTimeBy(15_000)

        assertThat(home.state.value).isNull()
    }

    @Test
    fun `switching Profile drops the last Profile's rows rather than drawing them`() = runTest {
        startSuperbad()
        db.profiles().insert(Profile(id = 2, name = "Second", createdAtMillis = 1))
        val home = viewModels.track(HomeViewModel(app, graph))
        home.shownThenCovered(keptLive = true) { it.continuing() == listOf(superbad.name) }
        val seen = mutableListOf<HomeState?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { home.state.collect { seen += it } }
        val before = seen.size

        graph.settings.setActiveProfile(2)

        home.whileCovered { it != null && it.continueWatching.isEmpty() }
        assertThat(seen.drop(before).mapNotNull { it.continuing() }.flatten()).doesNotContain(superbad.name)
    }

    private suspend fun startSuperbad() {
        db.movies().upsertAll(listOf(superbad))
        graph.viewerLibrary.recordProgress("movie:1", positionMillis = FILM_MILLIS / 2, durationMillis = FILM_MILLIS)
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
