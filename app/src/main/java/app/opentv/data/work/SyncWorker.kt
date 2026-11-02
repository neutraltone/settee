/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.RefreshTrigger
import java.util.concurrent.TimeUnit

/**
 * Runs a Catalogue Refresh in the background. Keeps its old name because WorkManager stores it for
 * the periodic job already scheduled on devices, and renaming it would break that job.
 *
 * Runs on WorkManager rather than a foreground timer so it survives the app being killed,
 * which on a TV box happens constantly. Failures return [Result.retry] with WorkManager's
 * exponential backoff — the app never hammers a provider that is already struggling.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val trigger = if (manual) RefreshTrigger.Manual else RefreshTrigger.Periodic
        val outcome = ServiceLocator.get(applicationContext).catalogueRefresh.refresh(trigger)
        Log.i(TAG, "$trigger refresh: ${outcome.channels} channels, ${outcome.problems} problem(s)")
        // A partial failure is still a retry — but the user keeps everything already on disk. Not for
        // a manual run: the viewer has been told how it went, and one waiting to retry would swallow
        // their next tap, since repeated taps keep the work already queued.
        return if (outcome.problems > 0 && !manual) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "opentv-periodic-sync"
        private const val ONE_SHOT_WORK_NAME = "opentv-manual-sync"
        private const val KEY_MANUAL = "manual"

        /**
         * Kick a single Catalogue Refresh now, off the UI, as one-time work, which means it survives
         * the user navigating away from the screen that triggered it. KEEP so repeated taps coalesce
         * into one run: REPLACE would cancel a download that's part way through to start it again.
         */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.MINUTES,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
