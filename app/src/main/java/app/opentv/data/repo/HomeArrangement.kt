/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

/**
 * One of Home's rows a viewer can move or hide, by the stable [key] Home's row model uses. Stored and
 * synced by that key, never by position or a string resource, so a release that adds, retires or
 * renames a row still reads an arrangement written by another.
 */
enum class HomeRowKind(val key: String) {
    CONTINUE_WATCHING("cw"),
    RECENT_SHOWS("recentShows"),
    RECENT_MOVIES("recentMovies"),
    FAVOURITE_SHOWS("favouriteShows"),
    FAVOURITE_MOVIES("favouriteMovies"),
    FAVOURITE_CHANNELS("channels"),
    ;

    companion object {
        fun byKey(key: String): HomeRowKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * How a Profile arranged Home: its Home Rows' order, which are hidden, and whether the Live Preview
 * shows. One setting, synced newest-wins as a whole (ADR 0002). A hidden row keeps its place in
 * [rows], so showing it again puts it back where the viewer left it.
 */
data class HomeArrangement(val rows: List<HomeRowKind>, val hidden: Set<HomeRowKind>, val livePreview: Boolean) {

    fun isShown(row: HomeRowKind) = row !in hidden

    fun withShown(row: HomeRowKind, shown: Boolean) = copy(hidden = if (shown) hidden - row else hidden + row)

    /** The row at [from] moved to [to], the rows between sliding aside. */
    fun moved(from: Int, to: Int): HomeArrangement {
        if (from == to || from !in rows.indices || to !in rows.indices) return this
        return copy(rows = rows.toMutableList().apply { add(to, removeAt(from)) })
    }

    internal fun encode(): String = buildString {
        append(PREVIEW).append(if (livePreview) ON else OFF).append(';')
        rows.joinTo(this, ",") { if (it in hidden) HIDDEN + it.key else it.key }
    }

    companion object {
        /** Home as it is for a Profile that never arranged it. */
        val USUAL = HomeArrangement(HomeRowKind.entries, emptySet(), livePreview = true)

        private const val PREVIEW = "preview="
        private const val ON = "on"
        private const val OFF = "off"
        private const val HIDDEN = "-"

        /**
         * Reads a stored arrangement. A row this release knows that the stored one doesn't, added since
         * it was written, goes at the end, shown; a stored row this release doesn't know is dropped.
         * Anything unreadable is the usual arrangement rather than an error on Home.
         */
        internal fun decode(value: String?): HomeArrangement {
            val parts = value?.split(';', limit = 2)?.takeIf { it.size == 2 }
            val livePreview = when (parts?.first()) {
                PREVIEW + ON -> true
                PREVIEW + OFF -> false
                else -> null
            }
            if (parts == null || livePreview == null) return USUAL
            val stored = parts[1].split(',').filter { it.isNotBlank() }.mapNotNull { entry ->
                HomeRowKind.byKey(entry.removePrefix(HIDDEN))?.let { it to entry.startsWith(HIDDEN) }
            }.distinctBy { it.first }
            val rows = stored.map { it.first }
            return HomeArrangement(
                rows = rows + HomeRowKind.entries.filterNot { it in rows },
                hidden = stored.filter { it.second }.map { it.first }.toSet(),
                livePreview = livePreview,
            )
        }
    }
}
