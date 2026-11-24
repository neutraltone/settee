/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import android.content.SharedPreferences
import app.opentv.R
import app.opentv.core.ErrorWording
import app.opentv.core.ViewerError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the last NAS sync went, whether the launch ran it or the viewer pressed Sync now (#484).
 *
 * The launch's sync has nobody watching, so its failure used to be thrown away, and Your devices kept
 * saying Favourites travel between devices while nothing had synced for weeks. Kept on disk, not just
 * in memory, so the failure still shows after the app is reopened, until a sync works.
 *
 * Every [NasSync.Result] is worded here, once, so the launch and Sync now say the same thing.
 */
class LastNasSync(
    private val prefs: SharedPreferences,
    private val wording: ErrorWording,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** A sync at [atMillis] that worked ([failure] null) or failed, as the viewer is told it. */
    data class Outcome(val atMillis: Long, val failure: ViewerError?)

    private val _outcome = MutableStateFlow(read())

    /** Null until a NAS sync has run on this device. */
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    /** Keeps how [result] went, and gives its failure as the viewer sees it, or null when it worked. */
    fun record(result: NasSync.Result): ViewerError? {
        val failure = when (result) {
            is NasSync.Result.Success -> result.unreadableCause?.let { cause ->
                // Not a success to the viewer: with no word, an unread lone device read as none (#485).
                wording.ofCount(R.plurals.sync_nas_unreadable, result.unreadable, ErrorWording.codeOf(cause))
            }
            NasSync.Result.NotConfigured -> wording.of(R.string.sync_nas_needs_setup)
            is NasSync.Result.Failed -> wording.of(result.cause, R.string.sync_nas_failed)
        }
        val outcome = Outcome(now(), failure)
        prefs.edit()
            .putLong(KEY_AT, outcome.atMillis)
            .putString(KEY_FAILURE, failure?.stored())
            .apply()
        _outcome.value = outcome
        return failure
    }

    private fun read(): Outcome? {
        if (!prefs.contains(KEY_AT)) return null
        return Outcome(prefs.getLong(KEY_AT, 0L), prefs.getString(KEY_FAILURE, null)?.let(ViewerError::fromStored))
    }

    private companion object {
        const val KEY_AT = "last_nas_sync_at"
        const val KEY_FAILURE = "last_nas_sync_failure"
    }
}
