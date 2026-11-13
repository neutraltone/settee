/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import android.util.Log
import app.opentv.BuildConfig
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.remote.OpenSubtitlesClient
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.StremioClient
import app.opentv.data.remote.TmdbClient
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.data.repo.ChannelLineup
import app.opentv.data.repo.DownloadedSubtitles
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.KeystoreSealer
import app.opentv.data.repo.OpenSubtitles
import app.opentv.data.repo.OpenSubtitlesAccount
import app.opentv.data.repo.PlaybackSources
import app.opentv.data.repo.ProfileSettings
import app.opentv.data.repo.Profiles
import app.opentv.data.repo.RecordingRepository
import app.opentv.data.repo.RefreshMessages
import app.opentv.data.repo.ReminderRepository
import app.opentv.data.repo.SourceRepository
import app.opentv.data.repo.TitleIndex
import app.opentv.data.repo.TmdbKeys
import app.opentv.data.repo.ViewerLibrary
import app.opentv.data.repo.VodQualities
import app.opentv.data.repo.WaitingProgress
import app.opentv.isRunningOnTelevision
import app.opentv.pairing.PairingFonts
import app.opentv.pairing.PairingServer
import app.opentv.player.DecoderList
import app.opentv.player.DeviceDecoders
import app.opentv.recording.RecordingEngine
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
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
     * [settingsFile] gives it settings of its own, which are otherwise one per process, and [clock]
     * lets a test say which of two devices changed something last.
     */
    class Graph(
        context: Context,
        http: OkHttpClient? = null,
        settingsFile: String? = null,
        val clock: () -> Long = System::currentTimeMillis,
        val decoders: DecoderList = DeviceDecoders(),
        openDatabase: (() -> OpenTvDatabase)? = null,
    ) {
        private val appContext = context.applicationContext

        val settings: AppSettings by lazy {
            settingsFile?.let { AppSettings.separate(appContext, it) } ?: AppSettings.get(appContext)
        }

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

        val xtreamApi: XtreamApi by lazy { XtreamApi(httpClient, downloads = appContext.cacheDir) }

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
                wording = errorWording,
            )
        }

        /** The one place a title becomes something playable (see [PlaybackSources]). */
        /** The Channels a viewer may see, with the Parental Lock applied; see [ChannelLineup]. */
        val channelLineup: ChannelLineup by lazy {
            ChannelLineup(
                database,
                viewerLibrary.favourites,
                settings.hiddenCategories,
                settings.hiddenUnlocked,
                decoders,
            )
        }

        /** Every film and show grouped into titles, for lists and covers (ADR 0007). */
        val titleIndex: TitleIndex by lazy { TitleIndex(database, decoders) }

        val vodQualities: VodQualities by lazy {
            VodQualities(
                database,
                catalogRepository,
                decoders,
                profileSettings,
                versionIds = titleIndex.versionIds,
            )
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
                wording = errorWording,
                selfEnabledGuide = object : EpgRepository.SelfEnabledGuide {
                    override var name by settings::selfEnabledGuide
                },
            )
        }

        /**
         * Outlives every screen, so a refresh or a change keeps going when the viewer moves on. A
         * failure is logged rather than left to crash the app: what's launched here has no screen
         * left to show it, and a rolled-back write leaves nothing to clear up.
         */
        val appScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, e -> Log.w("ServiceLocator", "Background work failed", e) },
        )

        val refreshMessages: RefreshMessages by lazy { RefreshMessages(appContext) }

        /** TMDB, for checking a TMDB Key before it's saved. Holds nothing, so the catalogue's own is no conflict. */
        private val tmdbClient: TmdbClient by lazy { TmdbClient(httpClient, settings) }

        /** Saving and removing the TMDB Key, each key checked with TMDB first, off the main thread. */
        val tmdbKeys: TmdbKeys by lazy {
            TmdbKeys(settings) { key -> withContext(Dispatchers.IO) { tmdbClient.check(key) } }
        }

        /**
         * Settee's OpenSubtitles key; blank, as in a build without one, leaves OpenSubtitles out. A test
         * sets its own before [openSubtitles] is first used, to have OpenSubtitles whatever the build has.
         */
        internal var openSubtitlesKey: String = BuildConfig.OPENSUBTITLES_API_KEY

        /**
         * OpenSubtitles, with Settee's own key, or null in a build without one: then no player,
         * Setup step or Settings section offers it.
         */
        val openSubtitles: OpenSubtitles? by lazy {
            openSubtitlesKey.takeIf { it.isNotBlank() }?.let { key ->
                OpenSubtitles(
                    account = OpenSubtitlesAccount(
                        settings,
                        OpenSubtitlesClient(httpClient, key, BuildConfig.VERSION_NAME),
                        KeystoreSealer(),
                        clock,
                    ),
                    store = DownloadedSubtitles(java.io.File(appContext.filesDir, "subtitles"), clock),
                    catalog = catalogRepository,
                    sources = sourceRepository,
                )
            }
        }

        /**
         * The phone's way in, for providers and the TMDB Key. One for the app, so Onboarding's TMDB step
         * carries on with the address the phone sent the first provider through.
         */
        val pairingServer: PairingServer by lazy {
            PairingServer(appScope, PairingFonts(appContext), appContext::getString)
        }

        /** Says "this TV" or "this device" as the app decided at launch, with [isRunningOnTelevision]. */
        val errorWording: ErrorWording by lazy { ErrorWording(appContext, isRunningOnTelevision(appContext)) }

        val catalogueRefresh: CatalogueRefresh by lazy {
            CatalogueRefresh(
                catalog = catalogRepository,
                guide = epgRepository,
                settings = settings,
                rescanSeriesRules = { recordingEngine.rescanSeriesRules() },
                clock = System::currentTimeMillis,
                scope = appScope,
            )
        }

        val viewerLibrary: ViewerLibrary by lazy {
            ViewerLibrary(database, settings.activeProfileId, clock, titleIndex.versionIds)
        }

        /** Outlives every screen, so progress still arrives after the sync screen has closed. */
        val waitingProgress: WaitingProgress by lazy {
            WaitingProgress(viewerLibrary, catalogRepository, database.sources(), database.episodes(), settings)
        }

        val profileSettings: ProfileSettings by lazy {
            ProfileSettings(database, settings.activeProfileId)
        }

        val profiles: Profiles by lazy {
            Profiles(database, viewerLibrary, profileSettings, recordingRepository, settings, clock)
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
