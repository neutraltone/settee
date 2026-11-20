/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.diagnostics

import android.util.Log
import app.opentv.core.ViewerError
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * An error a player showed, as Diagnostics keeps it: already redacted. [count] is how many times
 * it came up in a row, with [atUtcMillis] the last of them.
 */
@Serializable
data class LoggedError(val atUtcMillis: Long, val sentence: String, val detail: String, val count: Int = 1)

/**
 * What an open was. Not inferred from the key's type alone: a Stremio add-on stream can be an
 * episode as well as a film, so it gets a kind of its own rather than being called a film.
 */
enum class OpenKind { Film, Episode, AddonStream }

/**
 * Asking to play a film or episode, through to its first frame or its failure, [millis] later.
 * [failure] is the error's detail line, redacted; null when it played.
 */
@Serializable
data class LoggedOpen(val atUtcMillis: Long, val kind: OpenKind, val millis: Long, val failure: String? = null)

/**
 * The last few opens and errors, for Diagnostics, in JSON files of their own under [dir] rather than
 * in Room: they're wanted most when the database is what's wrong, and a table would need a migration
 * for what is only ever a short, capped list.
 *
 * Recording never waits: the players call it from Main, so the write is handed to [io] on [scope].
 */
class DiagnosticsLog(
    private val dir: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : PlaybackDiagnostics {
    private val lock = Mutex()

    override fun errorShown(error: ViewerError) {
        val at = clock()
        scope.launch(io) {
            lock.withLock {
                val logged = LoggedError(at, redact(error.sentence), redact(error.detail))
                write(ERRORS, errorList, collapse(readOrEmpty(ERRORS, errorList), logged).takeLast(KEPT))
            }
        }
    }

    override fun opened(kind: OpenKind, millis: Long, failure: ViewerError?) {
        val at = clock()
        scope.launch(io) {
            lock.withLock {
                val logged = LoggedOpen(at, kind, millis, failure?.detail?.let(::redact))
                write(OPENS, openList, (readOrEmpty(OPENS, openList) + logged).takeLast(KEPT))
            }
        }
    }

    suspend fun opens(): List<LoggedOpen> = withContext(io) { lock.withLock { read(OPENS, openList) } }

    suspend fun errors(): List<LoggedError> = withContext(io) { lock.withLock { readErrors() } }

    /**
     * A failing stream is restarted and retried, and each try fails the same way: kept apart, one
     * bad channel would fill every slot and push out what came before.
     */
    private fun collapse(errors: List<LoggedError>, logged: LoggedError): List<LoggedError> {
        val last = errors.lastOrNull()
        val repeat = last != null &&
            last.sentence == logged.sentence &&
            last.detail == logged.detail &&
            logged.atUtcMillis - last.atUtcMillis <= REPEAT_WINDOW_MILLIS
        return if (repeat) {
            errors.dropLast(1) + last.copy(atUtcMillis = logged.atUtcMillis, count = last.count + 1)
        } else {
            errors + logged
        }
    }

    private fun readErrors(): List<LoggedError> = read(ERRORS, errorList)

    private fun <T> read(name: String, serializer: KSerializer<List<T>>): List<T> {
        val file = File(dir, name)
        if (!file.exists()) return emptyList()
        return json.decodeFromString(serializer, file.readText())
    }

    /**
     * For adding to: a file that can't be read is started afresh. Recording runs on its own, from a
     * player's callback, so throwing here would crash the app over a list that only exists to help.
     * Reading for the screen still throws, so the report says the list is damaged, not empty.
     */
    private fun <T> readOrEmpty(name: String, serializer: KSerializer<List<T>>): List<T> = try {
        read(name, serializer)
    } catch (e: SerializationException) {
        Log.w(DIAGNOSTICS_TAG, "Starting $name afresh", e)
        emptyList()
    } catch (e: IOException) {
        Log.w(DIAGNOSTICS_TAG, "Starting $name afresh", e)
        emptyList()
    }

    /**
     * Written beside and renamed over, so the app being killed mid-write (the Stick does that when
     * it's short of memory, the very time this is wanted) leaves the last list, not half of one.
     * A write that fails is given up on: losing one entry is better than crashing the player.
     */
    private fun <T> write(name: String, serializer: KSerializer<List<T>>, items: List<T>) {
        try {
            dir.mkdirs()
            val temp = File(dir, "$name.tmp")
            temp.writeText(json.encodeToString(serializer, items))
            if (!temp.renameTo(File(dir, name))) throw IOException("Couldn't replace $name")
        } catch (e: IOException) {
            Log.w(DIAGNOSTICS_TAG, "Couldn't write $name", e)
        }
    }

    private companion object {
        const val ERRORS = "errors.json"
        const val OPENS = "opens.json"

        /** Enough to see a pattern over an evening, and small enough to read on a TV. */
        const val KEPT = 20

        /** Restarts and retries come within seconds; the same failure an evening later is news. */
        const val REPEAT_WINDOW_MILLIS = 5 * 60_000L
        val errorList = ListSerializer(LoggedError.serializer())
        val openList = ListSerializer(LoggedOpen.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }
}
