/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.core.StatusBus
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.LineupScope
import app.opentv.ui.ChannelsViewModel
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
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
 * The Live guide's first build puts a "Loading N channels" line on the status bar, which the shell
 * draws under every tab. Leaving Live, or covering it with Settings, before that build arrives
 * mustn't leave the line there until Live is opened again (#359).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GuideLoadingLineTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val builds = HeldBuilds()
    private val graph = ServiceLocator.Graph(app, clock = builds::clock)
    private val db = graph.database
    private val viewModels = ViewModelsInTest()

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        StatusBus.set(null)
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
        db.categories().upsertAll(
            listOf(
                Category(id = "10", sourceId = 1, name = "News", kind = StreamKind.LIVE),
                Category(id = "20", sourceId = 1, name = "Sport", kind = StreamKind.LIVE),
            ),
        )
        val rows = listOf("10" to "BBC News", "20" to "Sky Sports").mapIndexed { i, (category, name) ->
            Channel(
                sourceId = 1,
                streamId = "$i",
                name = name,
                categoryId = category,
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
    fun tearDown() {
        // A held build would keep the view model from stopping.
        builds.stopHolding()
        runBlocking { viewModels.stopAll() }
        db.close()
        StatusBus.set(null)
        Dispatchers.resetMain()
    }

    @Test
    fun `the loading line goes when the guide stops being watched before its first build arrives`() = runTest {
        val viewModel = viewModels.track(ChannelsViewModel(app, graph))
        val watching = launch { viewModel.guideRows.collect {} }
        builds.awaitHeld()
        assertThat(StatusBus.message.value).isNotNull()

        watching.cancelAndJoin()
        advanceTimeBy(STOP_WATCHING_MILLIS)
        builds.finish()

        assertThat(lineOnceCleared()).isNull()
        assertThat(StatusBus.progress.value).isNull()
    }

    @Test
    fun `a line another job put up since stays when the guide stops being watched`() = runTest {
        val viewModel = viewModels.track(ChannelsViewModel(app, graph))
        val watching = launch { viewModel.guideRows.collect {} }
        builds.awaitHeld()
        StatusBus.set("Updating the guide", 0.3f)

        watching.cancelAndJoin()
        advanceTimeBy(STOP_WATCHING_MILLIS)
        builds.finish()
        // Waits for the held build to end, and with it everything the guide does when it stops.
        viewModels.stopAll()

        assertThat(StatusBus.message.value).isEqualTo("Updating the guide")
    }

    @Test
    fun `coming back before any build arrived shows the loading line again`() = runTest {
        val viewModel = viewModels.track(ChannelsViewModel(app, graph))
        val watching = launch { viewModel.guideRows.collect {} }
        builds.awaitHeld()
        watching.cancelAndJoin()
        advanceTimeBy(STOP_WATCHING_MILLIS)
        builds.finish()
        assertThat(lineOnceCleared()).isNull()

        launch { viewModel.guideRows.collect {} }
        builds.awaitHeld()

        assertThat(StatusBus.message.value).isNotNull()
    }

    @Test
    fun `the first build clears the loading line, and switching category doesn't bring it back`() = runTest {
        val viewModel = viewModels.track(ChannelsViewModel(app, graph))
        launch { viewModel.guideRows.collect {} }
        builds.awaitHeld()
        // Not held from here: a line put up again by a later build stays up, for the asserts to see.
        builds.stopHolding()
        viewModel.guideRows.first { it.rows.isNotEmpty() }
        assertThat(StatusBus.message.value).isNull()

        val sport = viewModel.visibleCategoryGroups.first { it.isNotEmpty() }.single { it.label == "Sport" }
        viewModel.selectCategory(sport.key)
        val rows = withContext(Dispatchers.IO) {
            withTimeout(10_000) { viewModel.guideRows.first { it.scope == LineupScope.Group(sport.key) } }
        }

        assertThat(rows.rows.map { it.channel.primary.name }).containsExactly("Sky Sports")
        assertThat(StatusBus.message.value).isNull()
    }

    /** The status line once it's cleared, waiting in real time: the build runs on its own thread. */
    private suspend fun lineOnceCleared(): String? = withContext(Dispatchers.IO) {
        withTimeout(10_000) { StatusBus.message.first { it == null } }
    }

    private companion object {
        /** Past `WhileSubscribed(5_000)`, when the guide's rows stop being built. */
        const val STOP_WATCHING_MILLIS = 5_001L
    }
}

/**
 * The guide's clock, holding each guide build that shows the loading line until the test lets it
 * finish. A build reads the time just after putting the line up, and once going it runs to the end
 * however soon it's cancelled, so this is how a test catches one still running. Only a read from
 * inside the guide's build is held: Room and the Profile's library share its threads and clock.
 */
private class HeldBuilds {
    private val now = System.currentTimeMillis()

    @Volatile
    private var holding = true
    private val held = Semaphore(0)
    private val mayFinish = Semaphore(0)

    fun clock(): Long {
        if (holding && StatusBus.message.value != null && inGuideBuild()) {
            held.release()
            mayFinish.acquire()
        }
        return now
    }

    private fun inGuideBuild(): Boolean = Throwable().stackTrace.any { frame ->
        frame.className.startsWith(ChannelsViewModel::class.java.name) &&
            "guideRows" in frame.className + frame.methodName
    }

    /** Waits until a build is held with its line up. */
    suspend fun awaitHeld() = withContext(Dispatchers.IO) {
        check(held.tryAcquire(10, TimeUnit.SECONDS)) { "No guide build put its loading line up" }
    }

    /** Lets the held build finish. */
    fun finish() = mayFinish.release()

    fun stopHolding() {
        holding = false
        mayFinish.release(1_000)
    }
}
