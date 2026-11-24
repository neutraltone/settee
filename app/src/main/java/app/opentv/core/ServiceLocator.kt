/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import app.opentv.BuildConfig
import app.opentv.data.db.DATABASE_VERSION
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Episode
import app.opentv.data.model.Series
import app.opentv.data.remote.IntroDbClient
import app.opentv.data.remote.OpenSubtitlesClient
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.StremioClient
import app.opentv.data.remote.TheIntroDbClient
import app.opentv.data.remote.TmdbClient
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.data.repo.ChannelLineup
import app.opentv.data.repo.DownloadedSubtitles
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.EpisodeTimingsRepository
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
import app.opentv.data.repo.ViewerLibrary
import app.opentv.data.repo.VodQualities
import app.opentv.data.repo.WaitingProgress
import app.opentv.diagnostics.DiagnosticsExport
import app.opentv.diagnostics.DiagnosticsLog
import app.opentv.diagnostics.DiagnosticsReporter
import app.opentv.isRunningOnTelevision
import app.opentv.pairing.PairingFonts
import app.opentv.pairing.PairingServer
import app.opentv.player.DecoderList
import app.opentv.player.DeviceDecoders
import app.opentv.recording.BookingSpace
import app.opentv.recording.FreeSpace
import app.opentv.recording.RecordingEngine
import app.opentv.recording.RecordingStorage
import app.opentv.recording.StreamCapture
import app.opentv.ui.HomeSnapshots
import app.opentv.update.UpdateChecker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
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
     * Puts [graph] in the app's place and returns the one it replaced, for a test of a screen that
     * builds its own view models: otherwise it shares the process's graph with every test class
     * before it, whose leftover locks and work a provider given the same id then waits on for ever.
     */
    @VisibleForTesting
    fun swap(graph: Graph?): Graph? = synchronized(this) { instance.also { instance = graph } }

    /**
     * [openDatabase] replaces the on-disk database, so a test can stand up two devices in one
     * process: every Graph built from the same context would otherwise share one database file.
     * [http] replaces the client that talks to providers, so such a device can reach a fake panel.
     * [settingsFile] gives it settings of its own, which are otherwise one per process, and [clock]
     * lets a test say which of two devices changed something last. [openRecordingFile] replaces where
     * a recording's bytes go, so a test can fill the storage, and [captureReconnectMillis] how long a
     * recording waits before connecting again, so one whose stream has gone ends without waiting.
     * [dispatchers] are the threads repositories and view models run their background work on, so a
     * test can tell when that work has finished. [crashReportsTo] is where Crash Reports go: Settee's
     * Sentry project by default (none, in a build without its DSN), or a test's own outbox.
     */
    @Suppress("LongParameterList") // All but context are only passed by tests.
    class Graph(
        context: Context,
        http: OkHttpClient? = null,
        settingsFile: String? = null,
        val clock: () -> Long = System::currentTimeMillis,
        val dispatchers: AppDispatchers = AppDispatchers(),
        val decoders: DecoderList = DeviceDecoders(),
        private val openRecordingFile: ((String) -> RecordingStorage.Sink)? = null,
        val captureReconnectMillis: Long = StreamCapture.RECONNECT_BACKOFF_MILLIS,
        private val crashReportsTo: CrashReports.Destination = CrashReports.Destination(BuildConfig.SENTRY_DSN),
        // Last, so a test can pass it as a trailing lambda.
        openDatabase: (() -> OpenTvDatabase)? = null,
    ) {
        private val appContext = context.applicationContext

        val settings: AppSettings by lazy {
            settingsFile?.let { AppSettings.separate(appContext, it) } ?: AppSettings.get(appContext)
        }

        /**
         * Whether Home's rows keep updating while Home isn't drawn: true while a screen other than a
         * player covers it with the app in front. Set by [app.opentv.KeepHomeLive] (#192).
         */
        val homeKeptLive = MutableStateFlow(false)

        /** Each Profile's last finished Home, drawn at once on a cold start (#595). */
        val homeSnapshots: HomeSnapshots by lazy {
            HomeSnapshots(java.io.File(appContext.filesDir, "home"), DATABASE_VERSION, clock, dispatchers)
        }

        val database: OpenTvDatabase by lazy {
            openDatabase?.invoke() ?: OpenTvDatabase.build(context, onWiped = settings::forgetDatabaseRows)
        }

        // Every client is built from this one, so each marks its calls as network wait for the journeys.
        val httpClient: OkHttpClient by lazy {
            (
                http ?: OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    // Generous: catalogue endpoints on a busy panel can take a long time to
                    // produce 40,000 rows, and timing out mid-list is worse than waiting.
                    .readTimeout(60, TimeUnit.SECONDS)
                    .callTimeout(5, TimeUnit.MINUTES)
                    .retryOnConnectionFailure(true)
                    .followRedirects(true)
                    .build()
                ).newBuilder()
                .eventListener(journeys.network)
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

        val xtreamApi: XtreamApi by lazy {
            XtreamApi(
                httpClient,
                downloads = appContext.cacheDir,
                dispatchers = dispatchers,
                reportParseFailure = { crashReports.report(CrashReports.Handled.XTREAM_PARSE, it) },
            )
        }

        /** Stalker / Ministra portal client (MAC handshake + create_link). */
        val stalkerApi: StalkerApi by lazy { StalkerApi(httpClient, dispatchers = dispatchers) }

        /** Neutral Stremio add-on protocol client. Talks only to user-added manifest URLs. */
        val stremioClient: StremioClient by lazy { StremioClient(httpClient) }

        val sourceRepository: SourceRepository by lazy {
            SourceRepository(database.sources(), xtreamApi, stalkerApi, httpClient, dispatchers)
        }

        val catalogRepository: CatalogRepository by lazy {
            CatalogRepository(
                database = database,
                viewerLibrary = viewerLibrary,
                guide = epgRepository,
                api = xtreamApi,
                stalkerApi = stalkerApi,
                http = httpClient,
                settings = settings,
                tmdb = tmdb,
                wording = errorWording,
                dispatchers = dispatchers,
            ).apply { staging = appContext.cacheDir }
        }

        /** TMDB, with Settee's own key: unconfigured in a build without one, so never asked. */
        private val tmdb: TmdbClient by lazy {
            TmdbClient(httpClient, tmdbKey, reportFailure = reportsFailuresOf(CrashReports.Handled.TMDB))
        }

        /** Where episodes' intros and credits fall, for the Next Episode card (#517). */
        val episodeTimings: EpisodeTimingsRepository by lazy {
            EpisodeTimingsRepository(
                dao = database.episodeTimings(),
                theIntroDb = TheIntroDbClient(httpClient, reportsFailuresOf(CrashReports.Handled.INTRODB)),
                introDb = IntroDbClient(httpClient, reportsFailuresOf(CrashReports.Handled.INTRODB)),
                tmdb = tmdb,
                shows = object : EpisodeTimingsRepository.Shows {
                    override suspend fun seriesOf(episode: Episode) = catalogRepository.seriesOf(episode)

                    override suspend fun episodesOf(series: Series) =
                        database.episodes().forSeries(series.sourceId, series.seriesId)
                },
                clock = clock,
                dispatchers = dispatchers,
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
                dispatchers = dispatchers,
                sharing = lineupSharing,
            )
        }

        // Held here rather than inside the lineup so stopBackgroundWork can end its shared reads, which
        // outlive the last screen by ChannelLineup.SHARE_MILLIS.
        private val lineupSharing = CoroutineScope(SupervisorJob() + dispatchers.default)

        /** Every film and show grouped into titles, for lists and covers (ADR 0007). */
        val titleIndex: TitleIndex by lazy { TitleIndex(database, decoders, dispatchers) }

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
                refreshDao = database.guideRefresh(),
                feedDao = database.epgFeeds(),
                aliasDao = database.epgAliases(),
                channelDao = database.channels(),
                sourceDao = database.sources(),
                api = xtreamApi,
                http = httpClient,
                wording = errorWording,
                selfEnabledGuide = object : EpgRepository.SelfEnabledGuide {
                    override var name by settings::selfEnabledGuide
                    override var matchedCatalogueSync by settings::matchedCatalogueSync
                },
                dispatchers = dispatchers,
            )
        }

        /**
         * Outlives every screen, so a refresh or a change keeps going when the viewer moves on. A
         * failure is logged rather than left to crash the app: what's launched here has no screen
         * left to show it, and a rolled-back write leaves nothing to clear up.
         */
        val appScope = CoroutineScope(
            SupervisorJob() + dispatchers.io +
                CoroutineExceptionHandler { _, e -> Log.w("ServiceLocator", "Background work failed", e) },
        )

        /**
         * Stops everything the graph runs in the background, and waits for it, for a test about to
         * close the database: work still reading it then throws into whichever test runs next. Only
         * tests close a graph's database, so anything given its own scope stops here too.
         */
        @VisibleForTesting
        suspend fun stopBackgroundWork() {
            lineupSharing.coroutineContext.job.cancelAndJoin()
            titleIndex.close()
            waitingProgress.close()
            catalogRepository.awaitBackground()
            appScope.coroutineContext.job.cancelAndJoin()
        }

        val refreshMessages: RefreshMessages by lazy { RefreshMessages(appContext) }

        /**
         * Settee's built-in TMDB Key; blank, as in a build without one, leaves TMDB unasked. A test sets
         * its own before [catalogRepository] is first used, to have TMDB whatever the build has.
         */
        internal var tmdbKey: String = BuildConfig.TMDB_API_KEY

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
                        OpenSubtitlesClient(
                            httpClient,
                            key,
                            BuildConfig.VERSION_NAME,
                            reportFailure = reportsFailuresOf(CrashReports.Handled.OPENSUBTITLES),
                        ),
                        KeystoreSealer(),
                        clock,
                        dispatchers,
                    ),
                    store = DownloadedSubtitles(
                        java.io.File(appContext.filesDir, "subtitles"),
                        clock,
                        dispatchers = dispatchers,
                    ),
                    catalog = catalogRepository,
                    sources = sourceRepository,
                )
            }
        }

        /**
         * Times the journeys that matter (ADR 0010). One too slow is a Crash Report; in a debug build,
         * which never sends one, a line in logcat instead.
         */
        val journeys: JourneyTimer by lazy {
            JourneyTimer { slow ->
                if (BuildConfig.DEBUG) {
                    val waited = "${slow.networkMillis} ms on the network"
                    Log.w("SlowJourney", "${slow.journey.tag} took ${slow.ownMillis} ms of Settee's own, and $waited")
                } else {
                    crashReports.report(CrashReports.Handled.SLOW_JOURNEY, CrashReports.TooSlow(slow))
                }
            }
        }

        /** GitHub's latest release, for About's check and the automatic one. */
        val updateChecker: UpdateChecker by lazy {
            UpdateChecker(
                httpClient,
                BuildConfig.VERSION_NAME,
                dispatchers = dispatchers,
                reportFailure = reportsFailuresOf(CrashReports.Handled.UPDATE_CHECK),
            )
        }

        /** How one of Settee's own services, [kind], reports turning down Settee's request (ADR 0010). */
        private fun reportsFailuresOf(kind: CrashReports.Handled): (ServiceFailure) -> Unit =
            { crashReports.report(kind, it) }

        /** Crash Reports (ADR 0009): the only way anything reaches Sentry. */
        val crashReports: CrashReports by lazy {
            CrashReports(
                settings,
                crashReportsTo,
                release = BuildConfig.VERSION_NAME,
                // The owner's .pr test builds report too, told apart from the release here.
                environment = if (BuildConfig.APPLICATION_ID.endsWith(".pr")) "pr" else "production",
            )
        }

        /**
         * The phone's way in, for providers and OpenSubtitles' sign-in. One for the app, so Setup's
         * OpenSubtitles step carries on with the address the phone sent the first provider through.
         */
        val pairingServer: PairingServer by lazy {
            PairingServer(appScope, PairingFonts(appContext), resources = { LocaleUtils.wrap(appContext).resources })
        }

        /** Says "this TV" or "this device" as the app decided at launch, with [isRunningOnTelevision]. */
        val errorWording: ErrorWording by lazy { ErrorWording(appContext, isRunningOnTelevision(appContext)) }

        /** The last opens and errors, in files of their own so they're readable when the database isn't. */
        val diagnosticsLog: DiagnosticsLog by lazy {
            DiagnosticsLog(java.io.File(appContext.filesDir, "diagnostics"), appScope, dispatchers.io)
        }

        val diagnosticsExport: DiagnosticsExport by lazy { DiagnosticsExport(settings, dispatchers) }

        val diagnosticsReporter: DiagnosticsReporter by lazy {
            DiagnosticsReporter(
                appContext,
                errorWording,
                openDatabase = { database.openHelper.readableDatabase },
                diagnosticsLog,
                DiagnosticsReporter.Places(
                    database = appContext.getDatabasePath(OpenTvDatabase.FILE_NAME),
                    cache = appContext.cacheDir,
                    files = appContext.filesDir,
                ),
                dispatchers.io,
            )
        }

        val catalogueRefresh: CatalogueRefresh by lazy {
            CatalogueRefresh(
                catalog = catalogRepository,
                sources = sourceRepository,
                guide = epgRepository,
                settings = settings,
                rescanSeriesRules = { recordingEngine.rescanSeriesRules() },
                clock = clock,
                scope = appScope,
                journeys = journeys,
            )
        }

        val viewerLibrary: ViewerLibrary by lazy {
            ViewerLibrary(database, settings.activeProfileId, clock, titleIndex.versionIds)
        }

        /** Outlives every screen, so progress still arrives after the sync screen has closed. */
        val waitingProgress: WaitingProgress by lazy {
            WaitingProgress(
                viewerLibrary,
                catalogRepository,
                database.sources(),
                database.episodes(),
                settings,
                dispatchers,
            )
        }

        val profileSettings: ProfileSettings by lazy {
            ProfileSettings(database, settings.activeProfileId)
        }

        val profiles: Profiles by lazy {
            Profiles(
                database,
                viewerLibrary,
                profileSettings,
                recordingRepository,
                settings,
                clock,
            ).apply { onRemoved = homeSnapshots::forget }
        }

        val recordingRepository: RecordingRepository by lazy {
            RecordingRepository(database.recordings(), database.seriesRules(), dispatchers, appScope)
        }

        val reminderRepository: ReminderRepository by lazy {
            ReminderRepository(database.reminders(), database.sources())
        }

        /** Which bookings may not fit in what their storage has left (#473). */
        val bookingSpace: BookingSpace by lazy {
            BookingSpace(
                recordingRepository,
                database.channels(),
                FreeSpace(appContext, settings)::of,
                clock,
                dispatchers,
            )
        }

        val recordingEngine: RecordingEngine by lazy {
            RecordingEngine(
                appContext,
                recordingRepository,
                sourceRepository,
                settings,
                epgRepository,
                channelLineup,
                errorWording,
                openFile = openRecordingFile,
                clock = clock,
                dispatchers = dispatchers,
            )
        }
    }
}
