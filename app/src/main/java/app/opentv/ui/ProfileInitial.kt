/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.icu.text.BreakIterator
import java.util.Locale

/**
 * The initial drawn for a Profile: the first grapheme of [name], upper-cased for [locale].
 *
 * Not `take(1)`, which splits an emoji's surrogate pair and leaves half a glyph; a character
 * BreakIterator keeps flags and skin-tone emoji whole too. android.icu rather than java.text, whose
 * grapheme rules on older JDKs (and so under Robolectric) break skin tones off. And the locale's
 * upper case, not the root one, so Turkish "ilker" gets its dotted "İ".
 */
fun profileInitial(name: String, locale: Locale): String {
    val graphemes = BreakIterator.getCharacterInstance(locale)
    graphemes.setText(name)
    val end = graphemes.next()
    if (end == BreakIterator.DONE) return ""
    return name.substring(0, end).uppercase(locale)
}
