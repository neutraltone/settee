/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.HomeArrangement
import app.opentv.data.repo.HomeRowKind
import app.opentv.ui.HomeState
import app.opentv.ui.HomeViewModel
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
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

/**
 * Which of Home's rows hold their place as skeletons before their cards are built (#581): worked out
 * from quick checks of the Profile's own small tables, so a skeleton row never disappears and moves
 * the rows below it. Every read of the catalogue is held here, the way a big one is slow on a Stick.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeSkeletonShapeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val catalogue = HeldCatalogue()
    private val graph = ServiceLocator.Graph(
        app,
        settingsFile = "home-skeleton-shape-test",
        openDatabase = {
            // On a file with WAL, as the app's is: in memory there is one connection, so a held read
            // would hold up the quick checks too, which on the device read alongside it.
            Room.databaseBuilder(app, OpenTvDatabase::class.java, DATABASE)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                // Threads to spare: a held read keeps its thread, where a slow one only borrows it.
                .setQueryExecutor(Executors.newCachedThreadPool())
                .setQueryCallback({ sql, _ -> catalogue.onStatement(sql) }, Runnable::run)
                .build()
        },
    )
    private val db = graph.database
    private val viewModels = ViewModelsInTest()

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
        db.movies().upsertAll(listOf(superbad))
        db.series().upsertAll(listOf(severance))
    }

    @After
    fun tearDown() {
        catalogue.release()
        runBlocking { viewModels.stopAll() }
        db.close()
        app.deleteDatabase(DATABASE)
        Dispatchers.resetMain()
    }

    @Test
    fun `a Profile with something played and a Favourite show gets places for them and Recently Added`() = runTest {
        graph.viewerLibrary.recordProgress("movie:1", positionMillis = FILM_MILLIS / 2, durationMillis = FILM_MILLIS)
        graph.viewerLibrary.favourites.set(severance, true)
        catalogue.hold()

        val first = firstState()

        assertThat(first.loading).containsExactly(
            HomeRowKind.CONTINUE_WATCHING,
            HomeRowKind.FAVOURITE_SHOWS,
            HomeRowKind.RECENT_SHOWS,
            HomeRowKind.RECENT_MOVIES,
        )
    }

    @Test
    fun `a Profile that has played nothing gets no place for Continue Watching`() = runTest {
        catalogue.hold()

        val first = firstState()

        assertThat(first.loading).containsExactly(HomeRowKind.RECENT_SHOWS, HomeRowKind.RECENT_MOVIES)
    }

    @Test
    fun `a row the Profile hid never gets a place`() = runTest {
        graph.viewerLibrary.recordProgress("movie:1", positionMillis = FILM_MILLIS / 2, durationMillis = FILM_MILLIS)
        graph.profileSettings.arrangeHome(HomeArrangement.USUAL.withShown(HomeRowKind.CONTINUE_WATCHING, false))
        catalogue.hold()

        val first = firstState()

        assertThat(first.loading).containsExactly(HomeRowKind.RECENT_SHOWS, HomeRowKind.RECENT_MOVIES)
    }

    @Test
    fun `each place goes as its row's cards come, Continue Watching's with the film in it`() = runTest {
        graph.viewerLibrary.recordProgress("movie:1", positionMillis = FILM_MILLIS / 2, durationMillis = FILM_MILLIS)
        catalogue.hold()
        val home = viewModels.track(HomeViewModel(app, graph))
        withContext(Dispatchers.Default) { withTimeout(5_000) { home.state.filterNotNull().first() } }

        catalogue.release()
        val settled = withContext(Dispatchers.Default) {
            withTimeout(5_000) { home.state.filterNotNull().first { it.loading.isEmpty() } }
        }

        assertThat(settled.continueWatching.map { it.name }).containsExactly("Superbad")
    }

    /** Home's first state, waiting in real time: the quick checks run on Room's own threads. */
    private suspend fun firstState(): HomeState {
        val home = viewModels.track(HomeViewModel(app, graph))
        return withContext(Dispatchers.Default) { withTimeout(5_000) { home.state.filterNotNull().first() } }
    }

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
    )

    private val severance = Series(
        id = 2,
        sourceId = 1,
        seriesId = "2",
        name = "Severance",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
    )

    private companion object {
        const val FILM_MILLIS = 6_000_000L
        const val DATABASE = "home-skeleton-shape-test.db"
    }
}

/**
 * Holds every read of the catalogue's tables, on the thread making it, until [release]: Home's cards
 * are built from them, its quick checks aren't.
 */
private class HeldCatalogue {
    @Volatile
    private var holding = false
    private val released = CountDownLatch(1)

    fun hold() {
        holding = true
    }

    fun release() = released.countDown()

    fun onStatement(sql: String) {
        if (holding && sql.trimStart().startsWith("SELECT", ignoreCase = true) && CATALOGUE.containsMatchIn(sql)) {
            released.await(10, TimeUnit.SECONDS)
        }
    }

    private companion object {
        val CATALOGUE = Regex("""\b(movies|series|episodes|channels)\b""", RegexOption.IGNORE_CASE)
    }
}
