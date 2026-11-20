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
import app.opentv.ui.ChannelsViewModel
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Building the guide is a screen being built, which the skeleton rows show (#581); the status line
 * under every tab is for background work only. So the guide's first build never puts a line up, and
 * never takes down one a background job put up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GuideBuildStatusTest {

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
    fun `the guide's first build puts nothing on the status line`() = runTest {
        val viewModel = viewModels.track(ChannelsViewModel(app, graph))
        backgroundScope.launch { viewModel.guideRows.collect {} }
        builds.awaitHeld()

        assertThat(StatusBus.message.value).isNull()
        assertThat(StatusBus.progress.value).isNull()
    }

    @Test
    fun `a background job's line stays up through the guide's first build`() = runTest {
        StatusBus.set("Updating the guide", 0.3f)
        val viewModel = viewModels.track(ChannelsViewModel(app, graph))
        backgroundScope.launch { viewModel.guideRows.collect {} }
        builds.awaitHeld()
        builds.stopHolding()

        viewModel.guideRows.first { it.rows.isNotEmpty() }

        assertThat(StatusBus.message.value).isEqualTo("Updating the guide")
        assertThat(StatusBus.progress.value).isEqualTo(0.3f)
    }
}

/**
 * The guide's clock, holding the guide's build until the test lets it finish, so a test can look at
 * the status line while one is running. Only a read from inside the guide's build is held: Room and
 * the Profile's library share its threads and clock.
 */
private class HeldBuilds {
    private val now = System.currentTimeMillis()

    @Volatile
    private var holding = true
    private val held = Semaphore(0)
    private val mayFinish = Semaphore(0)

    fun clock(): Long {
        if (holding && inGuideBuild()) {
            held.release()
            mayFinish.acquire()
        }
        return now
    }

    private fun inGuideBuild(): Boolean = Throwable().stackTrace.any { frame ->
        frame.className.startsWith(ChannelsViewModel::class.java.name) &&
            "guideRows" in frame.className + frame.methodName
    }

    /** Waits until a build is held. */
    suspend fun awaitHeld() = withContext(Dispatchers.IO) {
        check(held.tryAcquire(10, TimeUnit.SECONDS)) { "No guide build started" }
    }

    /** Lets the held build finish. */
    fun finish() = mayFinish.release()

    fun stopHolding() {
        holding = false
        mayFinish.release(1_000)
    }
}
