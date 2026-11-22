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
import app.opentv.ui.BecauseYouWatched
import app.opentv.ui.Cover
import app.opentv.ui.HomeState
import app.opentv.ui.HomeViewModel
import app.opentv.ui.LiveNow
import app.opentv.ui.SourcesViewModel
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
        // Drawn throughout, as Home is: between two looks with nothing collecting, its rows could stop
        // and the saved Home go with them, while the test's clock skipped ahead.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { home.state.collect {} }

        // Once the quick checks are back: Recently Added shows, which nothing saved, holds its place.
        val first = home.whenDrawn { HomeRowKind.RECENT_SHOWS in it.loading }
        assertThat(first.recentMovies.names()).containsExactly("Heat")
        // In place of its skeleton, not as well as one.
        assertThat(first.loading).doesNotContain(HomeRowKind.RECENT_MOVIES)

        catalogue.release()
        // For the fresh movies themselves, not for nothing loading (a row drawn from what was saved
        // isn't loading, so that's true as soon as the others come, with Heat still showing) nor for
        // Heat going (a restarted Home has nothing saved to draw, so its row is empty until they come).
        val fresh = home.whenDrawn { "Superbad" in it.recentMovies.names() }
        assertThat(fresh.recentMovies.names()).containsExactly("Superbad")
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
        // Drawn throughout, as Home is: between two looks with nothing collecting, its rows could stop
        // and the saved Home go with them, while the test's clock skipped ahead.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { home.state.collect {} }

        // Once the quick checks are back, which hold Recently Added's place: the saved tile, not a skeleton.
        val first = home.whenDrawn { HomeRowKind.RECENT_MOVIES in it.loading }
        assertThat(first.liveLoading).isFalse()
        assertThat(first.live?.preview?.name).isEqualTo("BBC One")
        assertThat(first.liveStill).isTrue()

        catalogue.release()
        val fresh = home.whenDrawn { !it.liveStill && it.live != null }
        assertThat(fresh.live?.preview?.channel?.streamUrl).isEqualTo(bbcOne.streamUrl)
    }

    @Test
    fun `back on Home after its rows stopped, it waits for fresh ones rather than drawing saved ones again`() =
        runTest {
            graph.homeSnapshots.save(Profiles.BASE_PROFILE_ID, lastSeen(heat))
            val home = viewModels.track(HomeViewModel(app, graph))
            val shown = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { home.state.collect {} }
            home.whenDrawn { it.recentMovies.names() == listOf("Superbad") }
            // Into a player, long enough for Home's rows to stop: what's saved could be a card about to go.
            shown.cancel()
            advanceTimeBy(15_000)
            catalogue.hold()

            // The first Home drawn again, whatever it is: waiting for a particular one could miss it, as
            // the state keeps only the latest, and a later one would only show what came after.
            val again = home.whenDrawn { true }

            assertThat(again.loading).contains(HomeRowKind.RECENT_MOVIES)
            assertThat(again.recentMovies).isEmpty()
        }

    @Test
    fun `the first Home drawn holds a place for a row the saved one didn't have`() = runTest {
        graph.viewerLibrary.recordProgress("movie:1", positionMillis = FILM_MILLIS / 2, durationMillis = FILM_MILLIS)
        graph.homeSnapshots.save(Profiles.BASE_PROFILE_ID, lastSeen(heat))
        catalogue.hold()
        val home = viewModels.track(HomeViewModel(app, graph))

        // The very first: Continue Watching coming in above it a moment later would move every row.
        val first = home.whenDrawn { true }

        assertThat(first.loading).contains(HomeRowKind.CONTINUE_WATCHING)
        assertThat(first.recentMovies.names()).containsExactly("Heat")
    }

    @Test
    fun `while Because you watched is worked out, the saved one holds its place`() = runTest {
        graph.viewerLibrary.recordProgress("movie:1", positionMillis = FILM_MILLIS, durationMillis = FILM_MILLIS)
        val because = BecauseYouWatched("Superbad", listOf(Cover(VodTitle.OfMovie(heat))))
        graph.homeSnapshots.save(Profiles.BASE_PROFILE_ID, lastSeen().copy(becauseYouWatched = because))
        catalogue.hold()
        val home = viewModels.track(HomeViewModel(app, graph))

        val first = home.whenDrawn { HomeRowKind.RECENT_SHOWS in it.loading }

        assertThat(first.becauseYouWatched?.basedOn).isEqualTo("Superbad")
        assertThat(first.becauseYouWatched?.covers.orEmpty().names()).containsExactly("Heat")
    }

    @Test
    fun `removing a Profile forgets its saved Home, so a new Profile given its id never draws it`() = runTest {
        db.profiles().insert(Profile(id = 2, name = "Second", createdAtMillis = 1))
        graph.homeSnapshots.save(2, lastSeen(heat))

        graph.profiles.remove(2)

        assertThat(graph.homeSnapshots.read(2)).isNull()
    }

    @Test
    fun `removing a provider forgets every saved Home, as another provider can be given its row ids`() = runTest {
        graph.homeSnapshots.save(Profiles.BASE_PROFILE_ID, lastSeen(heat))
        val providers = viewModels.track(SourcesViewModel(app, graph))

        providers.delete(db.sources().all().single())

        val forgotten = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (graph.homeSnapshots.read(Profiles.BASE_PROFILE_ID) != null) delay(10)
                true
            }
        }
        assertThat(forgotten).isTrue()
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

    private companion object {
        const val FILM_MILLIS = 6_000_000L
    }

    // Only in what was saved: the provider has since dropped it.
    private val heat = superbad.copy(id = 2, streamId = "2", name = "Heat", streamUrl = "")
}
