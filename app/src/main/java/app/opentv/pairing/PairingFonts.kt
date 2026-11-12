/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import android.content.Context
import app.opentv.R

/**
 * Settee's two fonts, as the phone's setup page asks for them. The TV serves them itself, because
 * the phone may have no route to the internet while it's on the TV's network, so a font from a
 * CDN would never arrive.
 */
class PairingFonts(private val context: Context) {

    /** The bytes of the font file [name], or null for a name that isn't one of Settee's fonts. */
    fun open(name: String): ByteArray? =
        FILES[name]?.let { id -> context.resources.openRawResource(id).use { it.readBytes() } }

    companion object {
        /** The names the page asks for, and the bundled files behind them. */
        val FILES = mapOf(
            "young-serif.ttf" to R.font.young_serif_regular,
            "alegreya-sans-medium.ttf" to R.font.alegreya_sans_medium,
            "alegreya-sans-bold.ttf" to R.font.alegreya_sans_bold,
        )
    }
}
