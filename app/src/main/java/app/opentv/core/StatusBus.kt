/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the app is doing in the background (a Catalogue Refresh's steps, device sync), with an
 * optional 0..1 progress fraction where the job really knows how far through it is. The main screen
 * shows the newest line in its corner. Each job shows and clears its own line, so when two overlap
 * the newer shows and the older comes back once the newer ends, rather than one job's finish wiping
 * out the other's news.
 */
object StatusBus {
    private class Line(val message: String, val progress: Float?)

    private val lines = mutableListOf<Line>()

    private val _message = MutableStateFlow<String?>(null)

    /** The newest background activity, or null when nothing noteworthy is happening. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)

    /** 0..1 progress for [message], or null when it can't tell (show the aerial TV, not a bar). */
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    /** Puts [message] up as the newest line, or updates its progress if it's already up. */
    fun show(message: String, progress: Float? = null) = change {
        val at = indexOfFirst { it.message == message }
        if (at >= 0) set(at, Line(message, progress)) else add(Line(message, progress))
    }

    /** Takes [message] down, bar and all, leaving any other job's line up. */
    fun clear(message: String) = change { removeAll { it.message == message } }

    /** Replaces every line with [message], or clears them all with null. For tests, mostly. */
    fun set(message: String?, progress: Float? = null) = change {
        clear()
        if (message != null) add(Line(message, progress))
    }

    /** Announce [message] while [block] runs, then clear it, even if the work throws. */
    suspend fun <T> during(message: String, block: suspend () -> T): T {
        show(message)
        return try {
            block()
        } finally {
            clear(message)
        }
    }

    private fun change(edit: MutableList<Line>.() -> Unit) = synchronized(lines) {
        lines.edit()
        val newest = lines.lastOrNull()
        _message.value = newest?.message
        _progress.value = newest?.progress
    }
}
