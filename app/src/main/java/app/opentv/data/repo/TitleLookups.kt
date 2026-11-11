/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.util.Log
import kotlinx.coroutines.CancellationException

/** Saved together: one write per title would reload the whole catalogue on any screen showing it. */
private const val SAVE_EVERY = 10

/**
 * Consecutive titles the provider doesn't answer about before giving up until the next sync. A
 * stalled panel can take a minute per title, and the next lookup waits behind this one. A provider
 * failing on just these titles every sync would keep the older ones unfilled, which beats that wait.
 */
private const val GIVE_UP_AFTER = 5

/**
 * Looks something up (a date, a genre) for each of [titles], one at a time: [ask] the provider, which
 * gives null if it can't answer, then work out what was [found] from its answer. One title's failure
 * doesn't cost the others theirs; it is left as it was until the next sync. What's found goes to
 * [save] in batches. False if the provider stopped answering, so the caller asks it nothing more
 * this sync.
 */
internal suspend fun <T, A : Any, V : Any> lookUpEach(
    titles: List<T>,
    tag: String,
    ask: suspend (T) -> A?,
    found: suspend (T, A) -> V?,
    save: suspend (List<Pair<T, V>>) -> Unit,
): Boolean {
    var answering = true
    val results = mutableListOf<Pair<T, V>>()
    var unanswered = 0
    for (title in titles) {
        val answer = attempt(tag) { ask(title) }
        unanswered = if (answer == null) unanswered + 1 else 0
        if (unanswered == GIVE_UP_AFTER) {
            Log.w(tag, "Provider stopped answering; trying again next sync")
            answering = false
            break
        }
        if (answer != null) attempt(tag) { found(title, answer) }?.let { results += title to it }
        if (results.size == SAVE_EVERY) {
            save(results.toList())
            results.clear()
        }
    }
    if (results.isNotEmpty()) save(results)
    return answering
}

private suspend fun <R : Any> attempt(tag: String, block: suspend () -> R?): R? = runCatching { block() }
    .onFailure { if (it is CancellationException) throw it }
    .onFailure { Log.w(tag, "Lookup failed", it) }
    .getOrNull()
