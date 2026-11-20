/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.core.StatusBus
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.ui.SourcesViewModel
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
 * What the corner of the main screen says while a Catalogue Refresh runs in the background: each
 * step in the viewer's words, and a bar only where there's real progress, which is the guide's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackgroundWorkStatusTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val panel = FakePanel(
        "" to """{"user_info": {"status": "Active"}}""",
        "get_live_streams" to """[{"stream_id": 1, "name": "BBC One"}]""",
        "get_vod_streams" to """[{"stream_id": 3, "name": "Drive", "added": "100"}]""",
        "xmltv.php" to """
            <tv><programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="bbc1">
            <title>News</title></programme></tv>
        """.trimIndent(),
    )
    private val graph = ServiceLocator.Graph(
        app,
        http = panel.http,
        settingsFile = "background-work-status-test",
        openDatabase = {
            Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java).allowMainThreadQueries().build()
        },
    )
    private val viewModels = ViewModelsInTest()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        StatusBus.set(null)
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.stopAll() }
        graph.database.close()
        StatusBus.set(null)
        Dispatchers.resetMain()
    }

    @Test
    fun `a refresh says what it's refreshing, step by step, with a bar only for the guide`() = runBlocking {
        graph.database.sources().insert(PANEL_SOURCE)
        viewModels.track(SourcesViewModel(app, graph))
        val channels = panel.hold("get_live_streams")
        val films = panel.hold("get_vod_streams")
        val guide = panel.hold("xmltv.php")

        withContext(Dispatchers.Default) {
            val refreshing = async { graph.catalogueRefresh.refresh(CatalogueRefresh.Trigger.Periodic) }
            during(channels) {
                assertThat(StatusBus.message.value).isEqualTo("Refreshing your channels")
                assertThat(StatusBus.progress.value).isNull()
            }
            channels.release()
            during(films) {
                assertThat(StatusBus.message.value).isEqualTo("Refreshing movies and shows")
                assertThat(StatusBus.progress.value).isNull()
            }
            films.release()
            during(guide) {
                assertThat(StatusBus.message.value).isEqualTo("Refreshing the TV guide")
                assertThat(StatusBus.progress.value).isIn(Range.openClosed(0f, 1f))
            }
            guide.release()
            refreshing.await()
        }

        assertThat(StatusBus.message.value).isNull()
    }

    @Test
    fun `a step that starts while another runs shows, and the other comes back when it ends`() = runBlocking {
        graph.database.sources().insert(PANEL_SOURCE)
        viewModels.track(SourcesViewModel(app, graph))
        val films = panel.hold("get_vod_streams")
        val guide = panel.hold("xmltv.php")

        withContext(Dispatchers.Default) {
            val opening = async { graph.catalogueRefresh.refresh(CatalogueRefresh.Trigger.OpenedMoviesOrShows) }
            during(films) { assertThat(StatusBus.message.value).isEqualTo("Refreshing movies and shows") }
            // Another provider, since one provider's downloads take turns.
            val second = PANEL_SOURCE.copy(id = 2, name = "Second", username = "u2")
            graph.database.sources().insert(second)
            graph.catalogueRefresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(second))
            during(guide) { assertThat(StatusBus.message.value).isEqualTo("Refreshing the TV guide") }
            guide.release()
            awaitStatus("Refreshing movies and shows")
            films.release()
            opening.await()
            awaitStatus(null)
        }
    }

    private suspend fun awaitStatus(message: String?) = withTimeout(5_000) {
        while (StatusBus.message.value != message) delay(10)
    }
}
