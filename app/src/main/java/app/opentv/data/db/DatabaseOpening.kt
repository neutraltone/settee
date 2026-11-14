/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.db

import android.database.SQLException
import androidx.annotation.WorkerThread

/**
 * Opens the database, running any migration, and says why when it won't (#334): null when it opened.
 * Only Settee schemas from 9 on migrate, so one Room refuses (a migration that doesn't match its
 * schema, or a newer build's database after a downgrade) throws from every query. Whatever starts
 * the process (a screen, an alarm, boot) asks this first and stops short, rather than letting the
 * query throw on a bare coroutine and take the process down.
 *
 * Only opening is caught here, not the queries after it: an IllegalStateException from a query is a
 * bug, and catching those as well would hide it.
 */
@WorkerThread
fun OpenTvDatabase.wontOpen(): RuntimeException? = try {
    openHelper.writableDatabase
    null
} catch (missingMigration: IllegalStateException) {
    // Room's: no migration from the version on disk, or a schema that doesn't match.
    missingMigration
} catch (unreadable: SQLException) {
    // SQLite's: a full disk, or a file it can't open.
    unreadable
}
