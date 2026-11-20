/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Profile
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.HomeRowKind
import app.opentv.data.repo.LivePreview
import app.opentv.data.repo.Profiles
import app.opentv.data.repo.VodTitle
import app.opentv.ui.Cover
import app.opentv.ui.HomeState
import app.opentv.ui.HomeViewModel
import app.opentv.ui.LiveNow
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

/**
 * After a cold start Home draws the rows the Profile last finished seeing at once, and the fresh rows
 * take their places as they come (#595). Every read of the catalogue is held here, the way a big one is
 * slow on a Stick.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeSnapshotOnStartTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val catalogue = HeldCatalogue(app, "home-snapshot-on-start-test")
    private val graph = catalogue.graph
    private val db = graph.database
    private val viewModels = ViewModelsInTest()

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
        db.movies().upsertAll(listOf(superbad))
    }

    @After
    fun tearDown() {
        catalogue.release()
        graph.settings.setActiveProfile(Profiles.BASE_PROFILE_ID)
        runBlocking { viewModels.stopAll() }
        catalogue.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `while the catalogue is slow, Home draws the Recently Added it last finished, then the fresh one`() = runTest {
        graph.homeSnapshots.save(Profiles.BASE_PROFILE_ID, lastSeen(heat))
        catalogue.hold()
        val home = viewModels.track(HomeViewModel(app, graph))

        // Once the quick checks are back: Recently Added shows, which nothing saved, holds its place.
        val first = home.whenDrawn { HomeRowKind.RECENT_SHOWS in it.loading }
        assertThat(first.recentMovies.names()).containsExactly("Heat")
        // In place of its skeleton, not as well as one.
        assertThat(first.loading).doesNotContain(HomeRowKind.RECENT_MOVIES)

        catalogue.release()
        val fresh = home.whenDrawn { it.recentMovies.names() == listOf("Superbad") }
        assertThat(fresh.loading).isEmpty()
    }

    @Test
    fun `once every row has come, Home is kept for the next cold start`() = runTest {
        val home = viewModels.track(HomeViewModel(app, graph))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { home.state.collect {} }
        home.whenDrawn { it.loading.isEmpty() }

        advanceTimeBy(5_000)

        val kept = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                var read = graph.homeSnapshots.read(Profiles.BASE_PROFILE_ID)
                while (read == null) {
                    delay(10)
                    read = graph.homeSnapshots.read(Profiles.BASE_PROFILE_ID)
                }
                read
            }
        }
        assertThat(kept.recentMovies.names()).containsExactly("Superbad")
    }

    @Test
    fun `rows still coming aren't kept, so the next cold start never brings back half a Home`() = runTest {
        catalogue.hold()
        val home = viewModels.track(HomeViewModel(app, graph))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { home.state.collect {} }
        home.whenDrawn { true }

        advanceTimeBy(5_000)

        assertThat(graph.homeSnapshots.read(Profiles.BASE_PROFILE_ID)).isNull()
    }

    @Test
    fun `another Profile's Home is never drawn after switching`() = runTest {
        db.profiles().insert(Profile(id = 2, name = "Second", createdAtMillis = 1))
        graph.homeSnapshots.save(Profiles.BASE_PROFILE_ID, lastSeen(heat))
        graph.settings.setActiveProfile(2)
        catalogue.hold()
        val home = viewModels.track(HomeViewModel(app, graph))

        val first = home.whenDrawn { true }

        assertThat(first.recentMovies.names()).doesNotContain("Heat")
    }

    @Test
    fun `while the Live Preview is slow, Home draws the saved one as a still tile, then the fresh one`() = runTest {
        db.channels().replaceCatalogue(1, listOf(bbcOne), syncStamp = 1)
        val watched = db.channels().everyRow().single()
        graph.viewerLibrary.live.record(watched)
        val saved = LiveNow(LivePreview.Playable(watched.copy(streamUrl = "")), nowTitle = "News", nextTitle = null)
        graph.homeSnapshots.save(Profiles.BASE_PROFILE_ID, lastSeen().copy(live = saved))
        catalogue.hold()
        val home = viewModels.track(HomeViewModel(app, graph))

        // Once the quick checks are back, which hold Recently Added's place: the saved tile, not a skeleton.
        val first = home.whenDrawn { HomeRowKind.RECENT_MOVIES in it.loading }
        assertThat(first.liveLoading).isFalse()
        assertThat(first.live?.preview?.name).isEqualTo("BBC One")
        assertThat(first.liveStill).isTrue()

        catalogue.release()
        val fresh = home.whenDrawn { !it.liveStill && it.live != null }
        assertThat(fresh.live?.preview?.channel?.streamUrl).isEqualTo(bbcOne.streamUrl)
    }

    /** Home's state once [drawn] holds of it, waiting in real time: the reads run on Room's own threads. */
    private suspend fun HomeViewModel.whenDrawn(drawn: (HomeState) -> Boolean): HomeState =
        withContext(Dispatchers.Default) { withTimeout(5_000) { state.filterNotNull().first(drawn) } }

    private fun List<Cover>.names() = map { it.title.name }

    private fun lastSeen(vararg recent: Movie) = HomeState(
        continueWatching = emptyList(),
        favouriteShows = emptyList(),
        favouriteMovies = emptyList(),
        favouriteChannels = emptyList(),
        recentShows = emptyList(),
        recentMovies = recent.map { Cover(VodTitle.OfMovie(it)) },
    )

    private val superbad = Movie(
        id = 1,
        sourceId = 1,
        streamId = "1",
        name = "Superbad",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 2007,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/1",
        addedMillis = 1,
    )

    private val bbcOne = Channel(
        sourceId = 1,
        streamId = "1",
        name = "BBC One",
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = 1,
        streamUrl = "http://example.com/live/u/p/1.ts",
    )

    // Only in what was saved: the provider has since dropped it.
    private val heat = superbad.copy(id = 2, streamId = "2", name = "Heat", streamUrl = "")
}
