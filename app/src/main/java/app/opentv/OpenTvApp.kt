/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import app.opentv.core.AppSettings
import app.opentv.core.JourneyTimer
import app.opentv.core.LocaleUtils
import app.opentv.core.ServiceLocator
import app.opentv.core.carryLivePreviewFromBefore
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.data.work.SyncWorker
import app.opentv.player.torrent.TorrentCache
import app.opentv.player.torrent.TorrentEngineConfig
import app.opentv.recording.RecordingEngine
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class OpenTvApp :
    Application(),
    ImageLoaderFactory {
    // The handler is a backstop: each launch job catches its own failures, but one that slips past
    // would otherwise kill the process, and work started on every launch would then do so on every
    // launch, with no chance for the updater to bring a fix.
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "Launch work failed", t) },
    )
    private val launchWorkStarted = AtomicBoolean(false)

    /**
     * The app-wide Coil loader, tuned for a poster-and-logo heavy UI on a low-end TV box. The
     * default loader keeps a small memory cache and no disk cache, so scrolling back through a
     * shelf — or reopening Movies — re-downloads and re-decodes every image. Here:
     *  - a generous memory cache and a 256 MB disk cache mean art you've already seen paints from
     *    cache, instantly, instead of hitting the network;
     *  - no crossfade — an immediate swap reads as snappier on a d-pad grid than a fade, and skips
     *    a frame of blending per image;
     *  - RGB_565 for opaque art (posters/backdrops) halves the memory per bitmap, so more fits in
     *    cache; Coil keeps ARGB_8888 for anything with transparency, so channel logos are untouched.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .crossfade(false)
        .allowRgb565(true)
        .build()

    // So notifications and any app-context resources use the chosen language too, not just the UI.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleUtils.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        val graph = ServiceLocator.get(this)
        // First, so a crash in the rest of the launch is caught; costs a setting read unless reports are on.
        graph.crashReports.start(this)
        // Timed from the process starting, only when it started to show a screen: not for a sync or an
        // alarm, which could leave it running until the viewer opens Settee hours later.
        if (startedForAScreen()) {
            graph.journeys.start(JourneyTimer.Journey.COLD_START, at = Process.getStartElapsedRealtime())
        }
        SyncWorker.schedule(this)

        // Cold-start reconciliation: a recording still marked "in progress" at process start is an
        // orphan from a killed capture. Done here, once per process — NOT when the Recordings screen
        // opens — so checking on a recording you just started never marks it interrupted. Straight to
        // the repository, not through the engine: an alarm that cold-starts Settee builds the engine
        // before marking its recording started, and this has to have run by then.
        val interrupted = RecordingEngine.Interrupted.stored(this)

        // A torrent's pieces are deleted when its player closes; any still here were left by a crash
        // or a kill (#669). Nothing can be playing a torrent yet in a process just started.
        appScope.launch {
            runCatching { TorrentCache(TorrentCache.dirIn(cacheDir), TorrentEngineConfig()).clearLeftovers() }
        }
        appScope.launch { runCatching { graph.recordingRepository.failInterrupted(interrupted) } }

        // 1.0's device-wide Live Preview switch, moved onto each Profile (#212). Early in the launch,
        // so Home seldom draws a preview the device had turned off before it lands.
        appScope.launch { runCatching { carryLivePreviewFromBefore(graph.settings, graph.profileSettings) } }

        // Re-arm scheduled recordings on every launch, for the same reason as the reminders below: a
        // force-stop drops their exact alarms, and no receiver hears of one (the receivers cover a
        // reboot and an update). Re-setting the same alarm is idempotent, so this quietly keeps
        // bookings alive.
        appScope.launch { runCatching { graph.recordingEngine.rearmScheduled() } }

        // Re-arm programme reminders on every launch. Alarms are lost on a force-stop, which no
        // receiver hears of (they cover a reboot and an update), and re-setting an exact alarm for the
        // same reminder is idempotent — so this quietly keeps bells alive.
        appScope.launch {
            runCatching {
                graph.reminderRepository.deleteEndedBefore(System.currentTimeMillis())
                app.opentv.reminders.ReminderScheduler.rearmUpcoming(this@OpenTvApp, graph.reminderRepository)
            }
        }
    }

    private fun startedForAScreen(): Boolean = runCatching {
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    /**
     * A screen has opened. The first call in the process starts [onFirstOpen]; later ones, from
     * coming back to the app, a rotation or the activity being made again, do nothing.
     */
    fun opened() {
        if (launchWorkStarted.compareAndSet(false, true)) onFirstOpen()
    }

    /**
     * The work a launch does for the viewer: catching up with the NAS, re-cleaning the catalogue when
     * the normaliser has moved on, and freshening the guide. It waits for a screen to open, not
     * the process to start, because alarms, boot and [SyncWorker] start the process with nobody
     * looking, and on a 1 GB Fire TV Stick a guide download and an SMB sync then land on top of
     * the recording that alarm just opened.
     */
    protected open fun onFirstOpen() {
        val graph = ServiceLocator.get(this)
        // Now, so the titles are grouped while the viewer picks a Profile rather than after Home first
        // asks for them (#582). Off the main thread: making the index makes the database.
        appScope.launch { graph.titleIndex.start() }
        val lockCarry = graph.categoryLockCarry
        val catchUpNormaliser = NormaliserCatchUp(
            this,
            renormalise = {
                // New keys need matching to the guide, and the launch's guide pass doesn't know they
                // changed (#582). Matched even when nothing changed: a launch that stopped between the
                // two runs both again.
                graph.catalogRepository.renormalizeAll()
                graph.epgRepository.runMatcher()
            },
            carryLocks = lockCarry::carry,
            rememberLocks = lockCarry::remember,
        )
        val work = FirstOpenWork(
            nasAutoSync = graph.settings.nasAutoSync.value,
            // Free, server-less "cloud" sync through the user's own NAS, if they've opted in. Writes
            // this device's bundle to the shared folder and merges in every other device's — favourites,
            // watch history and NAS recordings. Fire-and-forget and fully guarded: a blank or unreachable
            // NAS returns a result rather than throwing, so a bad launch never costs the user anything.
            // That result is kept, so Your devices says when it keeps failing (#484): NasSync returns
            // its failures rather than throwing them, so otherwise one at launch went nowhere at all.
            syncNas = { app.opentv.sync.NasSync(graph).sync().also(graph.lastNasSync::record) },
            catchUpNormaliser = catchUpNormaliser::invoke,
            launchRefresh = {
                // Runs on every launch. It is cheap when nothing is stale (feeds within their
                // refresh window are skipped), but it is what makes the free regional guide turn
                // itself on and download the first time — without waiting for the user to find
                // the refresh button. ensureFeeds + auto-enable-by-region + matcher all live here.
                graph.catalogueRefresh.refresh(CatalogueRefresh.Trigger.AppLaunch)
            },
            failed = { step, cause -> Log.w(TAG, "First-open $step failed", cause) },
            syncingNas = getString(R.string.status_syncing_devices),
        )
        appScope.launch { work.run() }
    }

    private companion object {
        const val TAG = "OpenTV"
    }
}

/**
 * The SharedPreferences file for the app's own bookkeeping (which normaliser last cleaned the
 * catalogue, when updates were last checked), apart from the viewer's settings in [AppSettings].
 * Installed builds already wrote it under this name.
 */
internal const val APP_PREFS = "opentv"
