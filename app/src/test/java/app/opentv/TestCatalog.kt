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
import app.opentv.data.repo.ChannelLineup
import app.opentv.data.repo.ViewerLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * A [CatalogRepository] over an in-memory database, talking to a [FakePanel]. Close [db] after.
 * [viewerLibrary] records progress at [clock]; [lineup] folds its channels.
 */
class TestCatalog(context: Context, private val clock: () -> Long = { 0L }) {
    val db: OpenTvDatabase = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val panel = FakePanel()
    val viewerLibrary = ViewerLibrary(db, MutableStateFlow(1L), clock = clock)
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
    )

    /** The catalogue's Channels, with no Parental Lock set. */
    val lineup = ChannelLineup(db, viewerLibrary.favourites, MutableStateFlow(emptySet()), MutableStateFlow(false))

    /** Closes the database once the catalogue's background work is done with it. */
    fun close() = runBlocking {
        catalog.awaitBackground()
        db.close()
    }

    /** Saves [PANEL_SOURCE] and syncs its movies and shows from the panel's current replies. */
    suspend fun syncVod() {
        db.sources().insert(PANEL_SOURCE)
        catalog.syncVod(PANEL_SOURCE, nowUtcMillis = 0)
    }
}
