/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.opentv.R
import java.util.Locale

/**
 * The two bundled typefaces, as published (assets/licences/FONTS.md). Young Serif has one weight,
 * so asking it for any other gets that one rather than a faked bold.
 */
object SetteeFonts {
    val YoungSerif = FontFamily(Font(R.font.young_serif_regular, FontWeight.Normal))
    val AlegreyaSans = FontFamily(
        Font(R.font.alegreya_sans_medium, FontWeight.Medium),
        Font(R.font.alegreya_sans_bold, FontWeight.Bold),
    )
}

/**
 * Settee's type roles (docs/LOOK.md, Type). Nothing goes under 16 sp, the size that still reads
 * from the sofa, and every role draws lining, tabular figures, so times and counts line up.
 */
object SetteeType {
    val Display = role(SetteeFonts.YoungSerif, FontWeight.Normal, 54.sp, 62.sp)
    val Hero = role(SetteeFonts.YoungSerif, FontWeight.Normal, 32.sp, 39.sp)
    val ScreenTitle = role(SetteeFonts.YoungSerif, FontWeight.Normal, 28.sp, 34.sp)
    val Section = role(SetteeFonts.YoungSerif, FontWeight.Normal, 18.sp, 23.sp)
    val Synopsis = role(SetteeFonts.AlegreyaSans, FontWeight.Medium, 18.sp, 23.sp)

    /** A title on a card, where the art leaves less room than a row does. */
    val CardTitle = role(SetteeFonts.AlegreyaSans, FontWeight.Bold, 16.sp, 19.sp)

    /** A title on a list row: an episode, a channel, a recording. */
    val RowTitle = role(SetteeFonts.AlegreyaSans, FontWeight.Bold, 17.sp, 21.sp)

    /** Body copy and labels. */
    val Body = role(SetteeFonts.AlegreyaSans, FontWeight.Medium, 16.sp, 21.sp)

    private fun role(family: FontFamily, weight: FontWeight, size: TextUnit, lineHeight: TextUnit) = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size,
        lineHeight = lineHeight,
        fontFeatureSettings = "lnum, tnum",
    )
}

/** Languages Young Serif has no letters for (Cyrillic, Greek, Vietnamese's stacked accents). */
private val TitlesInSans = setOf("ru", "uk", "bg", "el", "vi")

/**
 * This style as it's drawn in [locale]: titles set in Young Serif move to Alegreya Sans Bold where
 * Young Serif can't draw the language. Left alone, Android would fill each missing letter from a
 * system font, so a title would come out in two faces. Scripts neither font covers (Arabic, Hindi,
 * Chinese and so on) still go to the system font, which is all there is for them.
 */
fun TextStyle.forLocale(locale: Locale): TextStyle =
    if (fontFamily == SetteeFonts.YoungSerif && locale.language in TitlesInSans) {
        copy(fontFamily = SetteeFonts.AlegreyaSans, fontWeight = FontWeight.Bold)
    } else {
        this
    }
