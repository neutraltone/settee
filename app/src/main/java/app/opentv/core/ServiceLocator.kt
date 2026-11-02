/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.StremioClient
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.data.repo.ChannelLineup
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.PlaybackSources
import app.opentv.data.repo.ProfileSettings
import app.opentv.data.repo.Profiles
import app.opentv.data.repo.RecordingRepository
import app.opentv.data.repo.RefreshMessages
import app.opentv.data.repo.ReminderRepository
import app.opentv.data.repo.SourceRepository
import app.opentv.data.repo.ViewerLibrary
import app.opentv.data.repo.WaitingProgress
import app.opentv.recording.RecordingEngine
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Hand-rolled dependency container.
 *
 * Hilt would be the conventional choice. It is deliberately not used: it adds an annotation
 * processor, a build-time graph, and a class of error message that is genuinely hard to read,
 * in exchange for wiring roughly a dozen objects. For a project whose main hope is drive-by
 * contributions from people fixing their own bugs, "you can read the whole graph in one file"
 * is worth more than the ceremony.
 */
object ServiceLocator {

    @Volatile private var instance: Graph? = null

    fun get(context: Context): Graph = instance ?: synchronized(this) {
        instance ?: Graph(context.applicationContext).also { instance = it }
    }

    /**
     * [openDatabase] replaces the on-disk database, so a test can stand up two devices in one
     * process: every Graph built from the same context would otherwise share one database file.
     * [http] replaces the client that talks to providers, so such a device can reach a fake panel.
     */
    class Graph(context: Context, http: OkHttpClient? = null, openDatabase: (() -> OpenTvDatabase)? = null) {
        private val appContext = context.applicationContext

        val settings: AppSettings by lazy { AppSettings.get(appContext) }

        val database: OpenTvDatabase by lazy {
            openDatabase?.invoke() ?: OpenTvDatabase.build(context, onWiped = settings::forgetDatabaseRows)
        }

        val httpClient: OkHttpClient by lazy {
            http ?: OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // Generous: catalogue endpoints on a busy panel can take a long time to
                // produce 40,000 rows, and timing out mid-list is worse than waiting.
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.MINUTES)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .build()
        }

        /**
         * A separate client for the player's data source. A live stream is an effectively
         * infinite HTTP response body, so the catalogue client's five-minute [callTimeout] would
         * guillotine every live channel at that mark — here there is no overall call timeout, only
         * a read timeout that catches a genuinely dead connection. Cross-protocol redirects are
         * followed explicitly because IPTV stream URLs routinely bounce http→https onto a CDN, and
         * a client that stops at the redirect surfaces as "lost connection to server".
         */
        val streamingHttpClient: OkHttpClient by lazy {
            httpClient.newBuilder()
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }

        val xtreamApi: XtreamApi by lazy { XtreamApi(httpClient) }

        /** Stalker / Ministra portal client (MAC handshake + create_link). */
        val stalkerApi: StalkerApi by lazy { StalkerApi(httpClient) }

        /** Neutral Stremio add-on protocol client. Talks only to user-added manifest URLs. */
        val stremioClient: StremioClient by lazy { StremioClient(httpClient) }

        val sourceRepository: SourceRepository by lazy {
            SourceRepository(database.sources(), xtreamApi, stalkerApi)
        }

        val catalogRepository: CatalogRepository by lazy {
            CatalogRepository(
                sourceDao = database.sources(),
                channelDao = database.channels(),
                categoryDao = database.categories(),
                movieDao = database.movies(),
                seriesDao = database.series(),
                episodeDao = database.episodes(),
                viewerLibrary = viewerLibrary,
                api = xtreamApi,
                stalkerApi = stalkerApi,
                http = httpClient,
                settings = settings,
            )
        }

        /** The one place a title becomes something playable (see [PlaybackSources]). */
        /** The Channels a viewer may see, with the Parental Lock applied; see [ChannelLineup]. */
        val channelLineup: ChannelLineup by lazy {
            ChannelLineup(database, viewerLibrary.favourites, settings.hiddenCategories, settings.hiddenUnlocked)
        }

        val playbackSources: PlaybackSources by lazy { PlaybackSources(database, xtreamApi, stalkerApi, channelLineup) }

        val epgRepository: EpgRepository by lazy {
            EpgRepository(
                programmeDao = database.programmes(),
                feedDao = database.epgFeeds(),
                aliasDao = database.epgAliases(),
                channelDao = database.channels(),
                sourceDao = database.sources(),
                api = xtreamApi,
                http = httpClient,
            )
        }

        /** Outlives every screen, so a refresh keeps going when the viewer moves on. */
        private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val refreshMessages: RefreshMessages by lazy { RefreshMessages(appContext) }

        val catalogueRefresh: CatalogueRefresh by lazy {
            CatalogueRefresh(
                catalog = catalogRepository,
                guide = epgRepository,
                settings = settings,
                rescanSeriesRules = { recordingEngine.rescanSeriesRules() },
                clock = System::currentTimeMillis,
                scope = refreshScope,
            )
        }

        val viewerLibrary: ViewerLibrary by lazy {
            ViewerLibrary(database, settings.activeProfileId)
        }

        /** Outlives every screen, so progress still arrives after the sync screen has closed. */
        val waitingProgress: WaitingProgress by lazy {
            WaitingProgress(viewerLibrary, catalogRepository, database.sources(), database.episodes(), settings)
        }

        val profileSettings: ProfileSettings by lazy {
            ProfileSettings(database.profileSettings(), database.profiles(), settings.activeProfileId)
        }

        val profiles: Profiles by lazy {
            Profiles(database.profiles(), viewerLibrary, profileSettings, recordingRepository, settings)
        }

        val recordingRepository: RecordingRepository by lazy {
            RecordingRepository(database.recordings(), database.seriesRules())
        }

        val reminderRepository: ReminderRepository by lazy {
            ReminderRepository(database.reminders())
        }

        val recordingEngine: RecordingEngine by lazy {
            RecordingEngine(
                appContext,
                recordingRepository,
                sourceRepository,
                settings,
                epgRepository,
                channelLineup,
            )
        }
    }
}
