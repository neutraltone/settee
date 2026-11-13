/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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

/**
 * Works through what [select] picks (given how many to pick), [count] at a time with [pauseMillis]
 * between, until a batch comes up short or [lookUp] says whoever it asks stopped answering. A title
 * already tried this run ([id]) isn't picked again: one whose lookup failed would otherwise come
 * round for ever, as the oldest-first order puts it first each time.
 */
internal suspend fun <T> inBatches(
    count: Int,
    pauseMillis: Long,
    id: (T) -> Long,
    select: suspend (limit: Int) -> List<T>,
    lookUp: suspend (List<T>) -> Boolean,
) {
    val tried = mutableSetOf<Long>()
    while (true) {
        val titles = select(count + tried.size).filterNot { id(it) in tried }.take(count)
        tried += titles.map(id)
        if (!lookUp(titles) || titles.size < count) return
        delay(pauseMillis)
    }
}
