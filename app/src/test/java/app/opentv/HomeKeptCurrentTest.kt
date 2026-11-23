/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Profile
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.Profiles
import app.opentv.ui.HomeState
import app.opentv.ui.HomeViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's rows stay current while another screen covers it, so Back draws them at once without
 * drawing any that have since moved or gone (#192).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeKeptCurrentTest {

    @get:Rule
    val testGraph = TestGraph()

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val db = graph.database

    @Before
    fun setUp(): Unit = runBlocking {
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
    }

    private suspend fun row(name: String): Channel = db.channels().everyRow().single { it.name == name }

    private fun HomeState?.channelNames() = this?.favouriteChannels?.map { it.channel.primary.name }

    /**
     * What Home would draw the moment it's shown again, once its work has settled, if [done] holds:
     * read without collecting, as nothing is showing Home while a screen covers it. Null if it doesn't.
     */
    private fun HomeViewModel.whileCovered(done: (HomeState?) -> Boolean): HomeState? {
        testGraph.awaitIdle()
        return state.value.takeIf(done)
    }

    /** Home drawn until [rows] hold, then covered by a screen: Settings when [keptLive], else a player. */
    private fun HomeViewModel.shownThenCovered(keptLive: Boolean, rows: (HomeState?) -> Boolean) {
        testGraph.firstWhenIdle(state, rows)
        graph.homeKeptLive.value = keptLive
    }

    private fun HomeState?.continuing() = this?.continueWatching?.map { it.name }

    @Test
    fun `after a long time in Settings, Home has its rows ready, with a Favourite added meanwhile`() {
        runBlocking { graph.viewerLibrary.favourites.set(row("UK| ITV HD"), true) }
        val home = testGraph.viewModel { HomeViewModel(app, graph) }
        home.shownThenCovered(keptLive = true) { it.channelNames() == listOf("UK| ITV HD") }
        // Longer away than Home once kept its rows.
        testGraph.advanceBy(15_000)

        runBlocking { graph.viewerLibrary.favourites.set(row("UK| BBC One HD"), true) }

        val shown = home.whileCovered { it.channelNames()?.size == 2 }
        assertThat(shown.channelNames()).containsExactly("UK| ITV HD", "UK| BBC One HD")
    }

    @Test
    fun `a film marked watched on its page is already off Continue Watching when Home is back`() {
        startSuperbad()
        val home = testGraph.viewModel { HomeViewModel(app, graph) }
        home.shownThenCovered(keptLive = true) { it.continuing() == listOf(superbad.name) }
        testGraph.advanceBy(15_000)

        runBlocking {
            graph.viewerLibrary.recordProgress("movie:1", positionMillis = FILM_MILLIS, durationMillis = FILM_MILLIS)
        }

        val shown = home.whileCovered { it != null && it.continueWatching.isEmpty() }
        assertThat(shown).isNotNull()
        assertThat(shown!!.continueWatching).isEmpty()
    }

    @Test
    fun `after a long time in a player, Home has dropped its rows, so Back waits for fresh ones`() {
        // The player saves its last position only as it closes, after Home is drawn again: rows kept
        // until then could still show the film it just finished.
        startSuperbad()
        val home = testGraph.viewModel { HomeViewModel(app, graph) }
        home.shownThenCovered(keptLive = false) { it.continuing() == listOf(superbad.name) }

        testGraph.advanceBy(15_000)

        assertThat(home.state.value).isNull()
    }

    @Test
    fun `switching Profile drops the last Profile's rows rather than drawing them`() {
        startSuperbad()
        runBlocking { db.profiles().insert(Profile(id = 2, name = "Second", createdAtMillis = 1)) }
        val home = testGraph.viewModel { HomeViewModel(app, graph) }
        home.shownThenCovered(keptLive = true) { it.continuing() == listOf(superbad.name) }
        val seen = mutableListOf<HomeState?>()
        testGraph.launch { home.state.collect { seen += it } }
        val before = seen.size

        graph.settings.setActiveProfile(2)

        home.whileCovered { it != null && it.continueWatching.isEmpty() }
        assertThat(seen.drop(before).mapNotNull { it.continuing() }.flatten()).doesNotContain(superbad.name)
    }

    private fun startSuperbad() = runBlocking {
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
