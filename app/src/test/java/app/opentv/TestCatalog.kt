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
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.data.repo.ChannelLineup
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.ViewerLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
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

    val settings: AppSettings = AppSettings.get(context)

    val guide = EpgRepository(
        programmeDao = db.programmes(),
        feedDao = db.epgFeeds(),
        aliasDao = db.epgAliases(),
        channelDao = db.channels(),
        sourceDao = db.sources(),
        api = XtreamApi(panel.http),
        http = panel.http,
    )

    /** Where [lineup] keeps its folds; cancelled with the database. */
    private val sharing = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The catalogue's Channels, with no Parental Lock set. */
    val lineup =
        ChannelLineup(
            db,
            viewerLibrary.favourites,
            MutableStateFlow(emptySet()),
            MutableStateFlow(false),
            ANY_SIZE_DECODERS,
            sharing,
        )

    private val refreshing = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A [CatalogueRefresh] over this catalogue, at [clock]; [awaitRefresh] waits for its background work. */
    fun refresh(rescanSeriesRules: suspend () -> Unit = {}) = CatalogueRefresh(
        catalog = catalog,
        guide = guide,
        settings = settings,
        rescanSeriesRules = rescanSeriesRules,
        clock = clock,
        scope = refreshing,
    )

    suspend fun awaitRefresh() {
        // Again until none are left: what's running can launch more.
        do {
            val running = refreshing.coroutineContext.job.children.toList()
            running.joinAll()
        } while (running.isNotEmpty())
    }

    /** Closes the database once the catalogue's background work is done with it. */
    fun close() = runBlocking {
        awaitRefresh()
        sharing.cancel()
        db.close()
    }

    /** Saves [PANEL_SOURCE] and syncs its movies and shows from the panel's current replies. */
    suspend fun syncVod() {
        db.sources().insert(PANEL_SOURCE)
        catalog.detectNewEpisodes(PANEL_SOURCE, catalog.fetchVod(PANEL_SOURCE, nowUtcMillis = 0).updated, 0)
    }
}
