/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.StatusBus
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.ui.SourcesViewModel
import com.google.common.collect.Range
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
 * What the corner of the main screen says while a Catalogue Refresh runs in the background: each
 * step in the viewer's words, and a bar only where there's real progress, which is the guide's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackgroundWorkStatusTest {

    private val panel = FakePanel(
        "" to """{"user_info": {"status": "Active"}}""",
        "get_live_streams" to """[{"stream_id": 1, "name": "BBC One"}]""",
        "get_vod_streams" to """[{"stream_id": 3, "name": "Drive", "added": "100"}]""",
        "xmltv.php" to """
            <tv><programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="bbc1">
            <title>News</title></programme></tv>
        """.trimIndent(),
    )

    @get:Rule
    val testGraph = TestGraph(name = "background-work-status-test", http = panel.http)

    private val app = testGraph.app
    private val graph = testGraph.graph

    @Before
    fun setUp() {
        StatusBus.set(null)
    }

    @After
    fun tearDown() {
        StatusBus.set(null)
    }

    /** Settles, then checks the refresh is waiting on [hold]. */
    private fun waitingOn(hold: FakePanel.Hold) {
        testGraph.awaitIdle()
        assertThat(hold.reached.count).isEqualTo(0L)
    }

    @Test
    fun `a refresh says what it's refreshing, step by step, with a bar only for the guide`() {
        runBlocking { graph.database.sources().insert(PANEL_SOURCE) }
        testGraph.viewModel { SourcesViewModel(app, graph) }
        val channels = panel.hold("get_live_streams")
        val films = panel.hold("get_vod_streams")
        val guide = panel.hold("xmltv.php")

        val refreshing = testGraph.launch { graph.catalogueRefresh.refresh(CatalogueRefresh.Trigger.Periodic) }
        waitingOn(channels)
        assertThat(StatusBus.message.value).isEqualTo("Refreshing your channels")
        assertThat(StatusBus.progress.value).isNull()
        channels.release()
        waitingOn(films)
        assertThat(StatusBus.message.value).isEqualTo("Refreshing movies and shows")
        assertThat(StatusBus.progress.value).isNull()
        films.release()
        waitingOn(guide)
        assertThat(StatusBus.message.value).isEqualTo("Refreshing the TV guide")
        assertThat(StatusBus.progress.value).isIn(Range.openClosed(0f, 1f))
        guide.release()
        testGraph.awaitIdle()
        assertThat(refreshing.isCompleted).isTrue()

        assertThat(StatusBus.message.value).isNull()
    }

    @Test
    fun `a step that starts while another runs shows, and the other comes back when it ends`() {
        runBlocking { graph.database.sources().insert(PANEL_SOURCE) }
        testGraph.viewModel { SourcesViewModel(app, graph) }
        val films = panel.hold("get_vod_streams")
        val guide = panel.hold("xmltv.php")

        val opening = testGraph.launch { graph.catalogueRefresh.refresh(CatalogueRefresh.Trigger.OpenedMoviesOrShows) }
        waitingOn(films)
        assertThat(StatusBus.message.value).isEqualTo("Refreshing movies and shows")
        // Another provider, since one provider's downloads take turns.
        val second = PANEL_SOURCE.copy(id = 2, name = "Second", username = "u2")
        runBlocking { graph.database.sources().insert(second) }
        testGraph.launch { graph.catalogueRefresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(second)) }
        waitingOn(guide)
        assertThat(StatusBus.message.value).isEqualTo("Refreshing the TV guide")
        guide.release()
        testGraph.awaitIdle()
        assertThat(StatusBus.message.value).isEqualTo("Refreshing movies and shows")
        films.release()
        testGraph.awaitIdle()
        assertThat(opening.isCompleted).isTrue()
        assertThat(StatusBus.message.value).isNull()
    }
}
