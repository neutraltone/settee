/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.diagnostics

import app.opentv.core.AppSettings
import app.opentv.recording.RecordingStorage
import app.opentv.recording.SmbClient
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saves a report to the NAS share sync and recordings already use, the one place off the device the
 * viewer has set up: a Stick's release build can't be reached by adb, so this is how a report leaves it.
 */
class DiagnosticsExport(private val settings: AppSettings, private val io: CoroutineDispatcher = Dispatchers.IO) {

    sealed interface Result {
        /** [name] is where it went, within the share. */
        data class Saved(val name: String) : Result

        data object NotConfigured : Result

        /** [cause] is for the detail line; its message is never shown. */
        data class Failed(val cause: Throwable) : Result
    }

    suspend fun save(report: DiagnosticsReport, madeAtUtcMillis: Long): Result = withContext(io) {
        val config = RecordingStorage.smbConfig(settings)
        if (!config.isConfigured) return@withContext Result.NotConfigured
        val name = diagnosticsFileName(settings.syncDeviceId, madeAtUtcMillis)
        runCatching {
            // locator joins the share's folder and the name; writeText makes any folder missing.
            SmbClient.writeText(config, SmbClient.locator(config, name), report.asText(madeAtUtcMillis))
        }.fold(
            onSuccess = { Result.Saved(name) },
            onFailure = { if (it is CancellationException) throw it else Result.Failed(it) },
        )
    }
}
