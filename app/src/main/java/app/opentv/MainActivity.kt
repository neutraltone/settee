/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.MediaKey
import app.opentv.ui.FirstScreen
import app.opentv.ui.MainScreen
import app.opentv.ui.ProfilesViewModel
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.VodViewModel
import app.opentv.ui.WhosWatchingAsked
import app.opentv.ui.WhosWatchingScreen
import app.opentv.ui.channels.ChannelManagerScreen
import app.opentv.ui.channels.SearchScreen
import app.opentv.ui.firstScreen
import app.opentv.ui.onboarding.AddSourceScreen
import app.opentv.ui.options.ItemOptionsHost
import app.opentv.ui.player.PlayerScreen
import app.opentv.ui.settings.AboutScreen
import app.opentv.ui.settings.AppSettingsScreen
import app.opentv.ui.settings.EpgSettingsScreen
import app.opentv.ui.settings.ParentalControlsScreen
import app.opentv.ui.settings.ProfilesScreen
import app.opentv.ui.settings.ProvidersScreen
import app.opentv.ui.settings.RecordingSettingsScreen
import app.opentv.ui.settings.SettingsHubScreen
import app.opentv.ui.settings.StremioAddonsScreen
import app.opentv.ui.settings.SyncScreen
import app.opentv.ui.settings.WebManagerScreen
import app.opentv.ui.theme.OpenTvTheme
import app.opentv.ui.vod.MovieDetailScreen
import app.opentv.ui.vod.PersonScreen
import app.opentv.ui.vod.SeriesDetailScreen
import app.opentv.ui.vod.VodPlayerScreen
import app.opentv.update.UpdateGate
import app.opentv.update.WhatsNewGate
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // Apply the chosen UI language before any view or resource is resolved. A language change in
    // settings calls recreate(), which re-runs this with the new tag.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(app.opentv.core.LocaleUtils.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handlePlayIntent(intent)
        val isTelevision = isRunningOnTelevision(this)
        val toPlay = launchedToPlay(intent, restored = savedInstanceState != null)

        setContent {
            val settings = remember { ServiceLocator.get(this).settings }
            val themeMode by settings.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                AppSettings.ThemeMode.DARK -> true
                AppSettings.ThemeMode.LIGHT -> false
                // A living-room screen defaults to dark; a phone/tablet follows the system.
                AppSettings.ThemeMode.SYSTEM -> isTelevision || isSystemInDarkTheme()
            }
            OpenTvTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    OpenTvApp(isTelevision = isTelevision, launchedToPlay = toPlay)
                }
            }
        }
    }

    // singleTask: a reminder tapped while the app is already running arrives here, not a fresh
    // onCreate. Either way the requested channel is handed to the nav graph via [PlayRequests].
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePlayIntent(intent)
    }

    private fun handlePlayIntent(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_PLAY_CHANNEL, 0L) ?: 0L
        if (id != 0L) app.opentv.core.PlayRequests.request(id)
        val recId = intent?.getLongExtra(EXTRA_WATCH_RECORDING, 0L) ?: 0L
        if (recId != 0L) {
            app.opentv.core.RecordingSignals.requestWatch(recId, System.currentTimeMillis())
        }
    }

    // ---- Picture-in-picture ------------------------------------------------------------------

    /** Home pressed while a programme is playing → shrink to a floating window instead of stopping. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        maybeEnterPip()
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
    private fun supportsPip(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun maybeEnterPip() {
        if (!app.opentv.core.PipState.eligible || !app.opentv.core.PipState.isPlaying) return
        enterPipNow()
    }

    /** Explicit request from the player's PiP button — no eligibility guard, the user asked. */
    fun enterPipNow() {
        if (!supportsPip()) return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build(),
            )
        }
    }

    fun pipSupported(): Boolean = supportsPip()

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        app.opentv.core.PipState.setInPip(isInPictureInPictureMode)
    }

    companion object {
        /** A reminder notification carries the channel to tune to in this extra. */
        const val EXTRA_PLAY_CHANNEL = "opentv.play_channel"

        /** A recording auto-switch notification carries the recording to watch in this extra. */
        const val EXTRA_WATCH_RECORDING = "opentv.watch_recording"
    }
}

object Routes {
    const val MAIN = "main"
    const val ADD_SOURCE = "add-source"
    const val PLAYER = "player/{channelId}"
    const val SEARCH = "search"
    const val EPG_SETTINGS = "epg-settings"
    const val APP_SETTINGS = "app-settings"
    const val SETTINGS_HUB = "settings"
    const val PROVIDERS = "providers"
    const val ADDONS = "addons"
    const val CHANNELS = "channels"
    const val WEB_MANAGER = "web-manager"
    const val PROFILES = "profiles"
    const val PARENTAL = "parental"
    const val SYNC = "sync"
    const val REC_SETTINGS = "recording-settings"
    const val ABOUT = "about"
    const val SERIES_DETAIL = "series/{seriesId}"
    const val MOVIE_DETAIL = "movie/{movieId}"
    const val EDIT_SOURCE = "edit-source/{sourceId}"

    // A person's name goes in a query arg, URL-encoded, so spaces and punctuation survive the round
    // trip — the same inline-encode/decode approach as the VOD player below.
    const val PERSON = "person?name={name}"

    // Only the key: the player asks PlaybackSources for the stream, so no URL (an Xtream one holds
    // the account password), title or user agent sits in the back stack. An add-on's stream isn't in
    // the catalogue, so its URL is the one thing carried besides.
    const val VOD_PLAYER = "vod?key={key}&addon={addon}"

    fun player(channelId: Long) = "player/$channelId"
    fun seriesDetail(seriesId: Long) = "series/$seriesId"
    fun movieDetail(movieId: Long) = "movie/$movieId"
    fun editSource(sourceId: Long) = "edit-source/$sourceId"
    fun person(name: String) = "person?name=${java.net.URLEncoder.encode(name, "UTF-8")}"
    fun vodPlayer(key: MediaKey): String {
        fun e(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        val addon = (key as? MediaKey.AddonStream)?.url.orEmpty()
        return "vod?key=${e(key.stored)}&addon=${e(addon)}"
    }

    /** The key [vodPlayer] put in a route, from its `key` and `addon` arguments. */
    fun vodPlayerKey(stored: String, addon: String): MediaKey? {
        val key = MediaKey.parse(stored)
        return if (key is MediaKey.Movie && addon.isNotEmpty()) MediaKey.AddonStream(key.movieId, addon) else key
    }
}

/**
 * Whether this launch asks to play something: a tapped reminder, or a recording switching the
 * screen to itself. Read from the intent that created the activity, not from [app.opentv.core.PlayRequests] or
 * [app.opentv.core.RecordingSignals]: a recording alarm sets those with no launch at all, sometimes long before the
 * viewer next opens the app, and that mustn't stop them being asked who's watching. A restore
 * redelivers the old intent, so it never counts.
 */
internal fun launchedToPlay(intent: Intent?, restored: Boolean): Boolean {
    if (restored || intent == null) return false
    return intent.getLongExtra(MainActivity.EXTRA_PLAY_CHANNEL, 0L) != 0L ||
        intent.getLongExtra(MainActivity.EXTRA_WATCH_RECORDING, 0L) != 0L
}

@Composable
private fun OpenTvApp(isTelevision: Boolean, launchedToPlay: Boolean) {
    // Answering Who's watching? starts a new navigation state rather than the one Android restored
    // after killing the process, so choosing opens Home, not whatever screen was open before. A
    // rotation keeps the same count, and with it the back stack.
    var navGeneration by rememberSaveable { mutableIntStateOf(0) }
    val navController = key(navGeneration) { rememberNavController() }
    val sourcesViewModel: SourcesViewModel = viewModel()
    val vodViewModel: VodViewModel = viewModel()
    val profilesViewModel: ProfilesViewModel = viewModel()
    val sourcesUi by sourcesViewModel.ui.collectAsState()
    val profiles by profilesViewModel.profiles.collectAsState()
    val activeProfileId by profilesViewModel.activeProfileId.collectAsState()
    val activeProfileName = profiles.firstOrNull { it.id == activeProfileId }?.name ?: "Me"
    val loadedProfiles by profilesViewModel.loadedProfiles.collectAsState()

    // Until the saved sources have loaded from the database, we cannot tell a first run from a
    // returning user — and guessing "first run" drops a returning user on the setup screen and
    // asks for their provider again. NavHost locks in its start destination on first
    // composition, so wait for that first load before building it. The Profiles are waited for
    // too, or a household would see Home flash up before being asked who's watching.
    val knownProfiles = loadedProfiles
    if (!sourcesUi.loaded || knownProfiles == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val first = firstScreen(
        hasProvider = sourcesUi.sources.isNotEmpty(),
        profileCount = knownProfiles.size,
        askedThisProcess = WhosWatchingAsked.settled,
        launchedToPlay = launchedToPlay,
    )
    // Nothing is built behind Who's watching?, so the boot-to-last-channel jump and any reminder or
    // recording that arrives meanwhile wait, and then run as the chosen Profile.
    if (first == FirstScreen.WHOS_WATCHING) {
        WhosWatchingScreen(profiles = knownProfiles, lastActiveId = activeProfileId) { id ->
            profilesViewModel.select(id)
            navGeneration++
            WhosWatchingAsked.settled = true
        }
        return
    }
    // Settled for this process whichever screen it opened on, so adding a second Profile later
    // doesn't throw the viewer out to Who's watching? mid-session.
    SideEffect { WhosWatchingAsked.settled = true }

    // First run goes straight to setup — an empty channel list with no explanation is the
    // worst possible first impression.
    val start = if (first == FirstScreen.ADD_SOURCE) Routes.ADD_SOURCE else Routes.MAIN

    val bootContext = androidx.compose.ui.platform.LocalContext.current

    // A tapped reminder notification asks for a specific channel. Consume it so it fires once and
    // never re-triggers on a later launch.
    val playRequest by app.opentv.core.PlayRequests.channelId.collectAsState()
    LaunchedEffect(playRequest) {
        val id = playRequest
        if (id != null && id != 0L) {
            app.opentv.core.PlayRequests.consume()
            navController.openLivePlayerOverGuide(id)
        }
    }

    // Auto-switch: a scheduled recording started, so move the live view onto the recording file
    // (single-connection safe). Ignore a stale request a long-backgrounded app only now sees.
    val watchRequest by app.opentv.core.RecordingSignals.watch.collectAsState()
    LaunchedEffect(watchRequest) {
        val req = watchRequest ?: return@LaunchedEffect
        app.opentv.core.RecordingSignals.consumeWatch()
        val fresh = System.currentTimeMillis() - req.requestedAtMillis < 10 * 60_000L
        if (req.recordingId != 0L && fresh) {
            val rec = runCatching {
                ServiceLocator.get(bootContext).recordingRepository.byId(req.recordingId)
            }.getOrNull() ?: return@LaunchedEffect
            runCatching {
                navController.switchToRecording(Routes.vodPlayer(MediaKey.Recording(rec.id)))
            }
        }
    }

    ItemOptionsHost {
        Box(Modifier.fillMaxSize()) {
            NavHost(navController = navController, startDestination = start) {
                composable(Routes.ADD_SOURCE) {
                    AddSourceScreen(
                        viewModel = sourcesViewModel,
                        onFinished = {
                            navController.navigate(Routes.MAIN) {
                                popUpTo(Routes.ADD_SOURCE) { inclusive = true }
                            }
                        },
                    )
                }

                composable(Routes.EDIT_SOURCE) { entry ->
                    val sourceId = entry.arguments?.getString("sourceId")?.toLongOrNull() ?: return@composable
                    AddSourceScreen(
                        viewModel = sourcesViewModel,
                        editingSourceId = sourceId,
                        onFinished = { navController.popBackStack() },
                    )
                }

                composable(Routes.MAIN) { entry ->
                    val tab by entry.shellTab()
                    MainScreen(
                        isTelevision = isTelevision,
                        hasSources = sourcesUi.sources.isNotEmpty(),
                        isSyncing = sourcesUi.syncing,
                        onPlayChannel = { channel -> navController.navigate(Routes.player(channel.id)) },
                        onOpenMovie = { movie ->
                            navController.navigate(Routes.movieDetail(movie.id))
                        },
                        onOpenSeries = { series ->
                            navController.navigate(Routes.seriesDetail(series.id))
                        },
                        onResume = { key -> navController.navigate(Routes.vodPlayer(key)) },
                        onAddSource = { navController.navigate(Routes.ADD_SOURCE) },
                        onRefresh = sourcesViewModel::refreshAll,
                        onOpenSearch = { navController.navigate(Routes.SEARCH) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS_HUB) },
                        onOpenProfiles = { navController.navigate(Routes.PROFILES) },
                        // The player works out where the recording is stored, and follows one still capturing.
                        onPlayRecording = { rec ->
                            navController.navigate(Routes.vodPlayer(MediaKey.Recording(rec.id)))
                        },
                        // Catch-up is a seekable archive stream — plays through the VOD player.
                        onPlayCatchup = { key -> navController.navigate(Routes.vodPlayer(key)) },
                        activeProfileName = activeProfileName,
                        tab = tab,
                        onSelectTab = { navController.selectShellTab(it) },
                    )
                }

                composable(Routes.PROFILES) {
                    ProfilesScreen(
                        viewModel = profilesViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.SEARCH) {
                    SearchScreen(
                        onPlayChannel = { channel -> navController.navigate(Routes.player(channel.id)) },
                        onPlayMovie = { movie -> navController.navigate(Routes.vodPlayer(MediaKey.Movie(movie.id))) },
                        onOpenSeries = { series -> navController.navigate(Routes.seriesDetail(series.id)) },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.SETTINGS_HUB) {
                    SettingsHubScreen(
                        onOpenProviders = { navController.navigate(Routes.PROVIDERS) },
                        onOpenAddons = { navController.navigate(Routes.ADDONS) },
                        onOpenGuide = { navController.navigate(Routes.EPG_SETTINGS) },
                        onOpenChannels = { navController.navigate(Routes.CHANNELS) },
                        onOpenWebManager = { navController.navigate(Routes.WEB_MANAGER) },
                        onOpenDisplay = { navController.navigate(Routes.APP_SETTINGS) },
                        onOpenParental = { navController.navigate(Routes.PARENTAL) },
                        onOpenSync = { navController.navigate(Routes.SYNC) },
                        onOpenRecordings = { navController.navigate(Routes.REC_SETTINGS) },
                        onOpenAbout = { navController.navigate(Routes.ABOUT) },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.SYNC) {
                    SyncScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.REC_SETTINGS) {
                    RecordingSettingsScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.CHANNELS) {
                    ChannelManagerScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.WEB_MANAGER) {
                    WebManagerScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.PARENTAL) {
                    ParentalControlsScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.PROVIDERS) {
                    ProvidersScreen(
                        viewModel = sourcesViewModel,
                        onAddSource = { navController.navigate(Routes.ADD_SOURCE) },
                        onEditSource = { src -> navController.navigate(Routes.editSource(src.id)) },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.ADDONS) {
                    StremioAddonsScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.ABOUT) {
                    AboutScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.EPG_SETTINGS) {
                    EpgSettingsScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.APP_SETTINGS) {
                    AppSettingsScreen(onBack = { navController.popBackStack() })
                }

                composable(Routes.PLAYER) { entry ->
                    val channelId = entry.arguments?.getString("channelId")?.toLongOrNull()
                    PlayerScreen(
                        channelId = channelId,
                        onBack = { navController.leaveLivePlayer(entry) },
                    )
                }

                composable(Routes.SERIES_DETAIL) { entry ->
                    val seriesId = entry.arguments?.getString("seriesId")?.toLongOrNull() ?: return@composable
                    SeriesDetailScreen(
                        seriesId = seriesId,
                        viewModel = vodViewModel,
                        onPlayEpisode = { key -> navController.navigate(Routes.vodPlayer(key)) },
                        onOpenSeries = { series -> navController.navigate(Routes.seriesDetail(series.id)) },
                        onOpenPerson = { name -> navController.navigate(Routes.person(name)) },
                    )
                }

                composable(Routes.MOVIE_DETAIL) { entry ->
                    val movieId = entry.arguments?.getString("movieId")?.toLongOrNull() ?: return@composable
                    MovieDetailScreen(
                        movieId = movieId,
                        viewModel = vodViewModel,
                        onPlay = { movie -> navController.navigate(Routes.vodPlayer(MediaKey.Movie(movie.id))) },
                        onPlayAddonStream = { key -> navController.navigate(Routes.vodPlayer(key)) },
                        onOpenMovie = { movie -> navController.navigate(Routes.movieDetail(movie.id)) },
                        onOpenPerson = { name -> navController.navigate(Routes.person(name)) },
                    )
                }

                composable(Routes.PERSON) { entry ->
                    val name = entry.arguments?.getString("name")
                        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    PersonScreen(
                        name = name,
                        viewModel = vodViewModel,
                        onOpenMovie = { movie -> navController.navigate(Routes.movieDetail(movie.id)) },
                        onOpenSeries = { series -> navController.navigate(Routes.seriesDetail(series.id)) },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.VOD_PLAYER) { entry ->
                    fun arg(name: String) = entry.arguments?.getString(name)
                        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    val mediaKey = Routes.vodPlayerKey(arg("key"), arg("addon")) ?: return@composable
                    val leaveScope = rememberCoroutineScope()
                    VodPlayerScreen(
                        mediaKey = mediaKey,
                        // The page is looked up when Back is pressed rather than as the player opens,
                        // so an early press can't miss it and fall back to plain Back.
                        onBack = {
                            leaveScope.launch {
                                val catalog = ServiceLocator.get(bootContext).catalogRepository
                                val titlePage = titlePageFor(mediaKey) { episodeId ->
                                    catalog.episode(episodeId)?.let { catalog.seriesOf(it) }?.id
                                }
                                navController.leaveVodPlayer(entry, titlePage)
                            }
                        },
                    )
                }
            }

            // Sits above the whole nav graph so a found update can prompt from any screen.
            UpdateGate()

            // Only over the main screen: never over first-run setup, and never over playback when the
            // app boots straight into the last channel; it waits until the viewer backs out to Home.
            val route by navController.currentBackStackEntryAsState()
            WhatsNewGate(canShow = route?.destination?.route == Routes.MAIN)

            // "About to switch to a recording" banner — shows 30s before an auto-switch fires.
            RecordingSwitchBanner()
        }
    }
}

/**
 * A slim banner shown 30 seconds before a scheduled recording takes over the screen (single-
 * connection auto-switch). It gives the viewer a heads-up and a way out: "Keep watching" suppresses
 * the switch for that booking. It clears itself once the switch would have happened.
 */
@Composable
private fun RecordingSwitchBanner() {
    val imminent by app.opentv.core.RecordingSignals.imminent.collectAsState()
    val current = imminent ?: return

    // Auto-clear a little after the switch instant, in case the switch itself never lands.
    LaunchedEffect(current.recordingId) {
        val wait = (current.startAtMillis + 5_000L) - System.currentTimeMillis()
        kotlinx.coroutines.delay(wait.coerceIn(5_000L, 60_000L))
        if (app.opentv.core.RecordingSignals.imminent.value?.recordingId == current.recordingId) {
            app.opentv.core.RecordingSignals.clearImminent()
        }
    }

    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.FiberManualRecord,
                contentDescription = null,
                tint = Color(0xFFE53935),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.rec_switch_banner, current.title, current.channelName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(14.dp))
            var focused by remember { mutableStateOf(false) }
            Text(
                stringResource(R.string.rec_switch_keep_watching),
                style = MaterialTheme.typography.labelLarge,
                color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (focused) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .clickable { app.opentv.core.RecordingSignals.suppress(current.recordingId) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Detects a ten-foot device.
 *
 * Checked at runtime rather than by shipping a separate leanback build: one APK for phone,
 * tablet, Android TV and Fire TV means one thing to release and one thing to test.
 */
fun isRunningOnTelevision(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val packageManager = context.packageManager
    return packageManager.hasSystemFeature("android.software.leanback") ||
        packageManager.hasSystemFeature("android.hardware.type.television")
}
