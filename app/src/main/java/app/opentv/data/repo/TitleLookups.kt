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
 * Works through what [select] picks (given how many to pick, in an order that stays put), up to
 * [count] at a time with [pauseMillis] between, until [select] runs out or [lookUp] says whoever it
 * asks stopped answering. A title already tried this run ([id]) isn't picked again: one whose lookup
 * failed would otherwise come round for ever, as the order puts it first each time.
 *
 * Only the titles [select] still returns stay tried. A title that's no longer due can't come back,
 * and remembering it would make each [select] read rows only to throw them away: asking for [count]
 * more per batch read about 1.1M rows for a 30,000-film source. What [lookUp] saved doesn't settle
 * which titles are no longer due (a film the provider answered about can still be due TMDB), so
 * that's learnt from the next [select]. A tried title still due ranks above every untried one, so the
 * window always holds it. The exception is a catalogue refresh adding newer titles during a run,
 * which can push a failed one out and get it tried once more. Each failure costs the batch after it a
 * title, as the window learns of it only then.
 */
internal suspend fun <T> inBatches(
    count: Int,
    pauseMillis: Long,
    id: (T) -> Long,
    select: suspend (limit: Int) -> List<T>,
    lookUp: suspend (List<T>) -> Boolean,
) {
    val tried = mutableSetOf<Long>()
    var triedStillDue = 0
    while (true) {
        val limit = count + triedStillDue
        val picked = select(limit)
        tried.retainAll(picked.mapTo(HashSet(), id))
        triedStillDue = tried.size
        // More than [count] when a title tried earlier has been filled in elsewhere, by opening it.
        val titles = picked.filterNot { id(it) in tried }.take(count)
        tried += titles.map(id)
        if (titles.isNotEmpty() && !lookUp(titles)) return
        if (picked.size < limit) return
        if (titles.isNotEmpty()) delay(pauseMillis)
    }
}
