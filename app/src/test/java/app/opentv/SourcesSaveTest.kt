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
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.SourcesViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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

/** Saving a provider from the phone, where a failed login is put right and sent again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SourcesSaveTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val panel = FakePanel("" to "#EXTM3U\n")
    private val graph = ServiceLocator.Graph(
        app,
        http = panel.http,
        settingsFile = "sources-save-test",
        openDatabase = {
            Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java).allowMainThreadQueries().build()
        },
    )

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun close() {
        Dispatchers.resetMain()
        graph.database.close()
    }

    @Test
    fun `a later provider sent from the phone whose login fails isn't kept, so trying again doesn't add two`() {
        runBlocking {
            graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1))
            val failure = CompletableDeferred<String?>()

            SourcesViewModel(app, graph).saveAndSync(
                Source(name = "", kind = SourceKind.M3U, url = "http://example.com/list.m3u"),
                keepIfFails = false,
                onChannels = { failure.complete(it) },
            )

            val reason = withContext(Dispatchers.Default) { withTimeout(5_000) { failure.await() } }
            assertThat(reason).contains("There are no channels at that address")
            assertThat(graph.sourceRepository.all().map { it.kind }).containsExactly(SourceKind.XTREAM)
        }
    }

    @Test
    fun `a later provider whose channels fail stays as one copy, which saving it again corrected loads`() {
        runBlocking {
            graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, name = "First", lastCatalogSyncMillis = 1))
            panel.replies[""] = """{"user_info": {"status": "Active"}}"""
            panel.replies["v/get_live_streams"] = """[{"stream_id": 1, "name": "BBC One"}]"""
            val viewModel = SourcesViewModel(app, graph)
            val wrong = PANEL_SOURCE.copy(id = 0, name = "Second", username = "w")

            save(viewModel, wrong)
            val stored = viewModel.ui.value.storedSourceId!!
            save(viewModel, wrong.copy(id = stored))
            val loaded = save(viewModel, wrong.copy(id = stored, username = "v"))

            assertThat(loaded).isTrue()
            val sources = graph.sourceRepository.all()
            assertThat(sources.map { it.name }).containsExactly("First", "Second")
            assertThat(sources.single { it.name == "Second" }.lastCatalogSyncMillis).isGreaterThan(0L)
        }
    }

    @Test
    fun `editing a provider keeps the time zone its panel last gave, which catch-up asks in`() {
        runBlocking {
            val id = graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, serverTimeZone = "Europe/Amsterdam"))
            // The edit screen's draft was read before a refresh heard the panel's zone.
            val draft = graph.sourceRepository.byId(id)!!.copy(name = "Renamed", serverTimeZone = null)

            graph.sourceRepository.save(draft)

            val saved = graph.sourceRepository.byId(id)!!
            assertThat(saved.name).isEqualTo("Renamed")
            assertThat(saved.serverTimeZone).isEqualTo("Europe/Amsterdam")
        }
    }

    @Test
    fun `moving a provider to another address forgets the old panel's time zone`() {
        runBlocking {
            val id = graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, serverTimeZone = "Europe/Amsterdam"))
            val draft = graph.sourceRepository.byId(id)!!.copy(url = "http://other.example.com")

            graph.sourceRepository.save(draft)

            assertThat(graph.sourceRepository.byId(id)!!.serverTimeZone).isNull()
        }
    }

    /** Saves [draft] as the Add provider form's Save does, and waits to hear whether its channels loaded. */
    private suspend fun save(viewModel: SourcesViewModel, draft: Source): Boolean {
        val done = CompletableDeferred<Boolean>()
        viewModel.saveAndSync(draft, onDone = { done.complete(it) })
        return withContext(Dispatchers.Default) { withTimeout(5_000) { done.await() } }
    }
}
