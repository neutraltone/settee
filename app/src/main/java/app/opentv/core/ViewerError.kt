/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/**
 * A failure as the viewer sees it: one [sentence] saying what happened and what to do, and a quiet
 * [detail] line under it (code · status · format). Both are written for the screen, so neither ever
 * holds an exception's own message, which can carry a provider URL, login or MAC. See docs/ERRORS.md.
 */
data class ViewerError(val sentence: String, val detail: String = "") {
    /** As one string, for a column that holds either this or a plain line of text: see [fromStored]. */
    fun stored(): String = "$sentence$SEPARATOR$detail"

    companion object {
        /** A unit separator: no sentence or detail line ever holds one. */
        private const val SEPARATOR = '\u001F'

        /** What [stored] wrote, or null for text that was stored as it is. */
        fun fromStored(text: String): ViewerError? = if (SEPARATOR in
            text
        ) {
            ViewerError(text.substringBefore(SEPARATOR), text.substringAfter(SEPARATOR))
        } else {
            null
        }

        /** Joins the detail line's parts, leaving out any that aren't known. */
        fun detailOf(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")
    }
}
