/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.ViewerLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll

/**
 * A [CatalogRepository] over an in-memory database, talking to a [FakePanel]. Close [db] after.
 * Work it starts after a sync runs on [background], so a test can wait for it with [settle].
 */
class TestCatalog(context: Context, private val clock: () -> Long = { 0L }) {
    val db: OpenTvDatabase = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val panel = FakePanel()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val viewerLibrary = ViewerLibrary(
        db.positions(),
        db.episodes(),
        db.favourites(),
        MutableStateFlow(1L),
        clock = clock,
        episodeFetcher = { series -> catalog.ensureEpisodes(PANEL_SOURCE, series.seriesId) },
    )
    val catalog: CatalogRepository = CatalogRepository(
        sourceDao = db.sources(),
        channelDao = db.channels(),
        categoryDao = db.categories(),
        movieDao = db.movies(),
        seriesDao = db.series(),
        episodeDao = db.episodes(),
        viewerLibrary = viewerLibrary,
        api = XtreamApi(panel.http),
        stalkerApi = StalkerApi(panel.http),
        http = panel.http,
        settings = AppSettings.get(context),
        clock = clock,
        background = background,
    )

    /** Waits for whatever the last sync left running in the background. */
    suspend fun settle() = background.coroutineContext.job.children.toList().joinAll()

    /** Saves [PANEL_SOURCE] and syncs its movies and shows from the panel's current replies. */
    suspend fun syncVod() {
        db.sources().insert(PANEL_SOURCE)
        catalog.syncVod(PANEL_SOURCE, nowUtcMillis = 0)
    }
}
