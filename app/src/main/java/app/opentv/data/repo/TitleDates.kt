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
 * failing on just these titles every sync would keep the older ones undated, which beats that wait.
 */
private const val GIVE_UP_AFTER = 5

/**
 * Looks up a date for each of [titles], one at a time: [ask] the provider, which gives null if it
 * can't answer, then work out the [date] from its answer. One title's failure doesn't cost the
 * others theirs; it is left undated until the next sync. What's found goes to [save] in batches.
 */
internal suspend fun <T, A : Any> lookUpDates(
    titles: List<T>,
    tag: String,
    ask: suspend (T) -> A?,
    date: suspend (T, A) -> Long?,
    save: suspend (List<Pair<T, Long>>) -> Unit,
) {
    val found = mutableListOf<Pair<T, Long>>()
    var unanswered = 0
    for (title in titles) {
        val answer = attempt(tag) { ask(title) }
        unanswered = if (answer == null) unanswered + 1 else 0
        if (unanswered == GIVE_UP_AFTER) {
            Log.w(tag, "Provider stopped answering; trying again next sync")
            break
        }
        if (answer != null) attempt(tag) { date(title, answer) }?.let { found += title to it }
        if (found.size == SAVE_EVERY) {
            save(found.toList())
            found.clear()
        }
    }
    if (found.isNotEmpty()) save(found)
}

private suspend fun <R : Any> attempt(tag: String, block: suspend () -> R?): R? = runCatching { block() }
    .onFailure { if (it is CancellationException) throw it }
    .onFailure { Log.w(tag, "Date lookup failed", it) }
    .getOrNull()
