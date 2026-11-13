/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import java.util.Locale

/**
 * [code]'s language as the viewer reads it ("French", or "français" in French), or null for no
 * language: blank, or `und`, which streams use for "not said". One for every subtitle and audio list,
 * so a language reads the same as a stream's track and as a Downloaded Subtitle.
 */
internal fun languageName(code: String?): String? = code?.takeIf { it.isNotBlank() && it != "und" }?.let {
    runCatching { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }.getOrDefault(it)
}

/** A stream track's row: its own label, else its language, else its place in the list. */
internal fun trackLabel(label: String?, language: String?, index: Int): String =
    label?.takeIf { it.isNotBlank() } ?: languageName(language) ?: "Track ${index + 1}"
