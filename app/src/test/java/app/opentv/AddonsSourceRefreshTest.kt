/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Movie
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.CatalogueRefresh
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A Provider's Catalogue Refresh never touches the films kept under the Add-ons source (#671). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AddonsSourceRefreshTest {

    @get:Rule
    val testGraph = TestGraph(name = "addons-source-refresh-test")

    private val test = TestCatalog(testGraph.app, testGraph.database, dispatchers = testGraph.graph.dispatchers) {
        1_000_000_000L
    }
    private val db = test.db
    private val panel = test.panel
    private val refresh = test.refresh()

    init {
        panel.replies[""] = """{"user_info": {"status": "Active"}}"""
        panel.replies["get_live_streams"] = """[{"stream_id": 1, "name": "BBC One"}]"""
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "100"}]"""
    }

    @After
    fun close() {
        test.settings.forgetDatabaseRows()
        test.close()
    }

    @Test
    fun `a refresh leaves the Add-ons source's films where they are, and asks it nothing`(): Unit = runBlocking {
        db.sources().insert(PANEL_SOURCE)
        db.sources().insert(ADDONS)
        db.movies().upsertAll(listOf(KEPT))
        val kept = db.movies().byProviderId(ADDONS.id, KEPT.streamId)

        refresh.refresh(CatalogueRefresh.Trigger.Manual)
        test.awaitRefresh()

        assertThat(db.movies().byProviderId(ADDONS.id, KEPT.streamId)).isEqualTo(kept)
        assertThat(db.sources().byId(ADDONS.id)?.lastRefreshFailure).isNull()
        assertThat(db.movies().byProviderId(PANEL_SOURCE.id, "3")).isNotNull()
    }

    private companion object {
        val ADDONS = Source(id = 9, name = Source.ADDONS_NAME, kind = SourceKind.ADDONS, url = "")

        val KEPT = Movie(
            sourceId = 9,
            streamId = "tt0000101",
            name = "Lost Film",
            categoryId = null,
            posterUrl = null,
            rating = null,
            year = 1999,
            plot = null,
            durationSeconds = null,
            containerExtension = null,
            streamUrl = "",
            tmdbId = "101",
        )
    }
}
