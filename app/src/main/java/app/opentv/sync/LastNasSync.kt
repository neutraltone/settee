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
 * in memory, so the failure still shows after the app is reopened, until a sync works. Your devices
 * shows it only while automatic NAS sync is on and a NAS is set up: otherwise it's about a sync
 * that's no longer meant to run.
 *
 * Every [NasSync.Result] is worded here, once, so the launch and Sync now say the same thing.
 */
class LastNasSync(
    private val prefs: SharedPreferences,
    private val wording: ErrorWording,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * A sync at [atMillis] that worked ([failure] null) or failed. Kept as what happened, not its
     * sentence, and worded each time [failure] is read, so it follows the app's language.
     */
    class Outcome internal constructor(
        val atMillis: Long,
        private val problem: Problem?,
        private val wording: ErrorWording,
    ) {
        /** The failure as the viewer is told it, in the app's language now. */
        val failure: ViewerError? get() = problem?.let(wording::ofNas)
    }

    /** What went wrong, as stored: only codes, counts and kinds, which are the same in every language. */
    internal sealed interface Problem {
        data class Unreadable(val count: Int, val code: String) : Problem
        data object NotConfigured : Problem
        data class Failed(val detail: String) : Problem
    }

    private val _outcome = MutableStateFlow(read())

    /** Null until a NAS sync has run on this device. */
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    /** Keeps how [result] went, and gives its failure as the viewer sees it, or null when it worked. */
    fun record(result: NasSync.Result): ViewerError? {
        val problem = when (result) {
            // Not a success to the viewer: with no word, an unread lone device read as none (#485).
            is NasSync.Result.Success -> result.unreadableCause?.let {
                Problem.Unreadable(result.unreadable, ErrorWording.codeOf(it))
            }
            NasSync.Result.NotConfigured -> Problem.NotConfigured
            is NasSync.Result.Failed -> Problem.Failed(wording.of(result.cause, R.string.sync_nas_failed).detail)
        }
        val outcome = Outcome(now(), problem, wording)
        prefs.edit()
            .putLong(KEY_AT, outcome.atMillis)
            .putString(KEY_KIND, problem?.let { it::class.simpleName })
            .putString(KEY_CODE, (problem as? Problem.Unreadable)?.code ?: (problem as? Problem.Failed)?.detail)
            .putInt(KEY_COUNT, (problem as? Problem.Unreadable)?.count ?: 0)
            .apply()
        _outcome.value = outcome
        return outcome.failure
    }

    private fun read(): Outcome? {
        if (!prefs.contains(KEY_AT)) return null
        val code = prefs.getString(KEY_CODE, null).orEmpty()
        val problem = when (prefs.getString(KEY_KIND, null)) {
            Problem.Unreadable::class.simpleName -> Problem.Unreadable(prefs.getInt(KEY_COUNT, 0), code)
            Problem.NotConfigured::class.simpleName -> Problem.NotConfigured
            Problem.Failed::class.simpleName -> Problem.Failed(code)
            else -> null
        }
        return Outcome(prefs.getLong(KEY_AT, 0L), problem, wording)
    }

    private companion object {
        const val KEY_AT = "last_nas_sync_at"
        const val KEY_KIND = "last_nas_sync_problem"
        const val KEY_CODE = "last_nas_sync_code"
        const val KEY_COUNT = "last_nas_sync_count"
    }
}

/** A stored NAS sync [problem] in the app's language now. */
private fun ErrorWording.ofNas(problem: LastNasSync.Problem): ViewerError = when (problem) {
    is LastNasSync.Problem.Unreadable -> ofCount(R.plurals.sync_nas_unreadable, problem.count, problem.code)
    LastNasSync.Problem.NotConfigured -> of(R.string.sync_nas_needs_setup)
    is LastNasSync.Problem.Failed -> of(R.string.sync_nas_failed, problem.detail)
}
