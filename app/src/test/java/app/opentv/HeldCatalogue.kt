/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * A [graph] whose reads of the catalogue's tables can be held, on the thread making them, until
 * [release]: the way a big catalogue is slow on a Stick, for screens' skeletons (#581). A screen's
 * cards are built from those tables; its quick checks (a count, an EXISTS, the Profile's own small
 * tables) aren't held.
 * [name] is its settings' and database's, so tests don't share them; [close] removes the database.
 */
class HeldCatalogue(private val app: Application, private val name: String) {
    @Volatile
    private var holding = false
    private val released = CountDownLatch(1)

    val graph = ServiceLocator.Graph(
        app,
        settingsFile = name,
        openDatabase = {
            // On a file with WAL, as the app's is: in memory there is one connection, so a held read
            // would hold up the quick checks too, which on the device read alongside it.
            Room.databaseBuilder(app, OpenTvDatabase::class.java, "$name.db")
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                // Threads to spare: a held read keeps its thread, where a slow one only borrows it.
                .setQueryExecutor(Executors.newCachedThreadPool())
                .setQueryCallback({ sql, _ -> onStatement(sql) }, Runnable::run)
                .build()
        },
    )

    fun hold() {
        holding = true
    }

    fun release() = released.countDown()

    /**
     * Releases anything held, stops the graph's background work, and removes the database. The work
     * outlives the screens that started it by a few seconds, and a read that ran on the closed
     * database threw into whichever test came next.
     */
    fun close() {
        release()
        runBlocking { graph.stopBackgroundWork() }
        graph.database.close()
        app.deleteDatabase("$name.db")
    }

    private fun onStatement(sql: String) {
        if (holding && readsCatalogueRows(sql.trimStart())) released.await(HELD_SECONDS, TimeUnit.SECONDS)
    }

    // A count or an EXISTS is a quick check, however big the table: only reads of rows are held.
    private fun readsCatalogueRows(sql: String): Boolean =
        sql.startsWith("SELECT", ignoreCase = true) && !QUICK.containsMatchIn(sql) && CATALOGUE.containsMatchIn(sql)

    private companion object {
        val QUICK = Regex("""^SELECT\s+(COUNT\(|EXISTS\()""", RegexOption.IGNORE_CASE)
        val CATALOGUE = Regex("""\b(movies|series|episodes|channels)\b""", RegexOption.IGNORE_CASE)

        /** Long enough to outlast a test's waits, short enough that a forgotten release can't hang a run. */
        const val HELD_SECONDS = 10L
    }
}
