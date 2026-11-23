/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import app.opentv.core.StatusBus
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.ui.ChannelsViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Building the guide is a screen being built, which the skeleton rows show (#581); the status line
 * under every tab is for background work only. So the guide's first build never puts a line up, and
 * never takes down one a background job put up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GuideBuildStatusTest {

    private val builds: HeldBuilds = HeldBuilds { testGraph.awaitIdle() }

    @get:Rule
    val testGraph: TestGraph = TestGraph(clock = builds::clock)

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val db = graph.database

    @Before
    fun setUp(): Unit = runBlocking {
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
        StatusBus.set(null)
    }

    @Test
    fun `the guide's first build puts nothing on the status line`() {
        val viewModel = testGraph.viewModel { ChannelsViewModel(app, graph) }
        testGraph.launch { viewModel.guideRows.collect {} }
        builds.awaitHeld()

        assertThat(StatusBus.message.value).isNull()
        assertThat(StatusBus.progress.value).isNull()
    }

    @Test
    fun `a background job's line stays up through the guide's first build`() {
        StatusBus.set("Refreshing the TV guide", 0.3f)
        val viewModel = testGraph.viewModel { ChannelsViewModel(app, graph) }
        testGraph.launch { viewModel.guideRows.collect {} }
        builds.awaitHeld()
        builds.stopHolding()

        testGraph.firstWhenIdle(viewModel.guideRows) { it.rows.isNotEmpty() }

        assertThat(StatusBus.message.value).isEqualTo("Refreshing the TV guide")
        assertThat(StatusBus.progress.value).isEqualTo(0.3f)
    }
}

/**
 * The guide's clock, holding the guide's build until the test lets it finish, so a test can look at
 * the status line while one is running. Only a read from inside the guide's build is held: Room and
 * the Profile's library share its threads and clock. Held through [TestGraph.Held], so the waiting
 * build isn't counted as work in flight and the test can settle while it waits.
 */
private class HeldBuilds(private val settle: () -> Unit) {
    private val now = System.currentTimeMillis()

    @Volatile
    private var gate: TestGraph.Held? = TestGraph.Held()

    @Volatile
    private var reached = false

    fun clock(): Long {
        val held = gate
        if (held != null && inGuideBuild()) {
            reached = true
            held.await(10)
        }
        return now
    }

    private fun inGuideBuild(): Boolean = Throwable().stackTrace.any { frame ->
        frame.className.startsWith(ChannelsViewModel::class.java.name) &&
            "guideRows" in frame.className + frame.methodName
    }

    /** Settles, then checks a build is held. */
    fun awaitHeld() {
        settle()
        check(reached) { "No guide build started" }
    }

    fun stopHolding() {
        gate?.release()
        gate = null
    }
}
