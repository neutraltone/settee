/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import app.opentv.core.AppSettings
import app.opentv.core.ErrorWording
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.Programme
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.data.repo.ChannelLineup
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.SourceRepository
import app.opentv.data.repo.ViewerLibrary
import app.opentv.data.repo.VodTitle
import java.io.File
import java.nio.file.Files
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
class TestCatalog(
    private val context: Context,
    val db: OpenTvDatabase = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build(),
    private val clock: () -> Long = { 0L },
) {
    val panel = FakePanel()

    /** Where the catalogue stages a decoded live list before saving it; empty between syncs. */
    val staging: File = Files.createTempDirectory("staging").toFile()

    val viewerLibrary = ViewerLibrary(db, MutableStateFlow(1L), clock = clock)
    val guide = EpgRepository(
        programmeDao = db.programmes(),
        refreshDao = db.guideRefresh(),
        feedDao = db.epgFeeds(),
        aliasDao = db.epgAliases(),
        channelDao = db.channels(),
        sourceDao = db.sources(),
        api = XtreamApi(panel.http),
        http = panel.http,
        wording = ErrorWording(context, onTelevision = false),
        selfEnabledGuide = EpgRepository.SelfEnabledGuide.InMemory(),
    )

    /** The catalogue as a build with no TMDB Key has it, so TMDB is never asked. */
    val catalog: CatalogRepository = catalogue(tmdbKey = "")

    /** The catalogue as a build with [tmdbKey] built in has it, over the same database and panel. */
    fun catalogWithTmdb(tmdbKey: String = TMDB_KEY): CatalogRepository = catalogue(tmdbKey)

    private fun catalogue(tmdbKey: String) = CatalogRepository(
        database = db,
        viewerLibrary = viewerLibrary,
        guide = guide,
        api = XtreamApi(panel.http),
        stalkerApi = StalkerApi(panel.http),
        http = panel.http,
        settings = AppSettings.get(context),
        tmdbKey = tmdbKey,
        wording = ErrorWording(context, onTelevision = false),
    ).apply {
        genrePauseMillis = 0
        staging = this@TestCatalog.staging
    }

    val settings: AppSettings = AppSettings.get(context)

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
        sources = SourceRepository(db.sources(), XtreamApi(panel.http), StalkerApi(panel.http)),
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

    /** Closes the database once the refreshes [awaitRefresh] waits for are done with it. */
    // Unit, not the Boolean deleteRecursively returns: tests' @After methods are `= test.close()`,
    // and JUnit rejects a class whose @After returns anything.
    fun close(): Unit = runBlocking {
        awaitRefresh()
        catalog.awaitBackground()
        sharing.cancel()
        db.close()
        staging.deleteRecursively()
    }

    /**
     * Saves [PANEL_SOURCE] and syncs its movies and shows from the panel's current replies, then
     * waits for the films' genres the sync looks up afterwards, so what a test asks the panel next
     * isn't mixed up with those lookups.
     */
    suspend fun syncVod(catalog: CatalogRepository = this.catalog) {
        db.sources().insert(PANEL_SOURCE)
        catalog.detectNewEpisodes(PANEL_SOURCE, catalog.fetchVod(PANEL_SOURCE, nowUtcMillis = 0).updated, 0)
        catalog.awaitBackground()
    }

    /** Gives [sourceId]'s provider guide, made by [EpgRepository.ensureFeeds], a programme and alias on [epgId]. */
    suspend fun seedProviderGuide(sourceId: Long, epgId: String) {
        val feed = db.epgFeeds().forProvider(sourceId)!!
        db.programmes().insertOrReplace(
            listOf(
                Programme(
                    feedId = feed.id,
                    epgChannelId = epgId,
                    startUtcMillis = SEEDED_START,
                    endUtcMillis = SEEDED_START + 3_600_000,
                    title = "News",
                ),
            ),
        )
        db.epgAliases().upsertAll(listOf(EpgChannelAlias(feed.id, epgId, "News", "news")))
    }

    /** The names of the titles on [name]'s person page, in its order. */
    suspend fun titlesWith(name: String): List<String> = catalog.titlesWithPerson(name).map {
        when (it) {
            is VodTitle.OfMovie -> it.movie.name
            is VodTitle.OfSeries -> it.series.name
        }
    }
}

/** When [TestCatalog.seedProviderGuide]'s programme starts: in 2033, so nothing prunes it by age. */
const val SEEDED_START = 2_000_000_000_000L

/** A placeholder in the shape of a v3 TMDB Key (32 hex digits), built so gitleaks sees no secret. */
val TMDB_KEY = "c".repeat(32)
