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
    if (fontFamily == SetteeFonts.YoungSerif && locale.language in TitlesInSans) inSans() else this

/**
 * This style for drawing [text] in [locale]: as [forLocale], and also in Alegreya Sans Bold when the
 * text itself has letters Young Serif lacks, so a film or channel named in Cyrillic or Greek on an
 * English interface isn't drawn letter by letter in two faces. The language alone can't decide it:
 * provider names arrive in any script, whatever the interface is in.
 */
fun TextStyle.forText(text: String, locale: Locale): TextStyle {
    val inLocale = forLocale(locale)
    return if (inLocale.fontFamily == SetteeFonts.YoungSerif && needsSans(text)) inSans() else inLocale
}

private fun TextStyle.inSans() = copy(fontFamily = SetteeFonts.AlegreyaSans, fontWeight = FontWeight.Bold)

// Young Serif draws all of Latin-1 and Latin Extended-A, which is nearly every title, so those
// letters skip the lookup: this runs whenever a Text's string changes.
private const val YOUNG_SERIF_FULL_BELOW = 0x0180

/**
 * The letters and accents Young Serif 3.003 draws in the blocks Alegreya Sans adds to it (Latin
 * Extended-B, Greek, Cyrillic, Latin Extended Additional, Greek Extended), as start and end pairs
 * read from res/font/young_serif_regular.ttf's cmap. Outside these blocks neither face helps
 * (Arabic, CJK and so on fall back to the system font either way), so they don't switch face.
 */
private val YoungSerifRanges = intArrayOf(
    0x018F, 0x018F, 0x0192, 0x0192, 0x019D, 0x019D, 0x01E6, 0x01E7, 0x01EA, 0x01EB,
    0x01FA, 0x01FF, 0x0218, 0x021B, 0x0232, 0x0233, 0x0237, 0x0237, 0x0259, 0x0259,
    0x0272, 0x0272, 0x02BB, 0x02BB, 0x02BE, 0x02BF, 0x02C6, 0x02C8, 0x02CA, 0x02CC,
    0x02D8, 0x02DD, 0x0300, 0x0304, 0x0306, 0x0308, 0x030A, 0x030C, 0x0312, 0x0312,
    0x0323, 0x0323, 0x0326, 0x0328, 0x032E, 0x032E, 0x0331, 0x0331, 0x0394, 0x0394,
    0x03A9, 0x03A9, 0x03BC, 0x03BC, 0x03C0, 0x03C0, 0x1E08, 0x1E09, 0x1E0C, 0x1E0F,
    0x1E14, 0x1E17, 0x1E1C, 0x1E1D, 0x1E20, 0x1E21, 0x1E24, 0x1E25, 0x1E2A, 0x1E2B,
    0x1E2E, 0x1E2F, 0x1E36, 0x1E37, 0x1E3A, 0x1E3B, 0x1E42, 0x1E49, 0x1E4C, 0x1E53,
    0x1E5A, 0x1E5B, 0x1E5E, 0x1E69, 0x1E6C, 0x1E6F, 0x1E78, 0x1E7B, 0x1E80, 0x1E85,
    0x1E8E, 0x1E8F, 0x1E92, 0x1E93, 0x1E97, 0x1E97, 0x1E9E, 0x1E9E, 0x1EA0, 0x1EA1,
    0x1EB8, 0x1EB9, 0x1EBC, 0x1EBD, 0x1ECA, 0x1ECD, 0x1EE4, 0x1EE5, 0x1EF2, 0x1EF3,
    0x1EF8, 0x1EF9,
)

private fun needsSans(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val c = text.codePointAt(i)
        if (sansOnly(c)) return true
        i += Character.charCount(c)
    }
    return false
}

// Where Alegreya Sans has letters Young Serif lacks: up to the end of Cyrillic, then Latin
// Extended Additional and Greek Extended.
private const val CYRILLIC_END = 0x0530
private val ExtendedAdditional = 0x1E00..0x1FFF

private fun sansOnly(c: Int): Boolean = mightNeedSans(c) && isLetterOrMark(c) && !youngSerifHas(c)

private fun mightNeedSans(c: Int) = c in YOUNG_SERIF_FULL_BELOW until CYRILLIC_END || c in ExtendedAdditional

private fun isLetterOrMark(c: Int) = Character.isLetter(c) || Character.getType(c) == Character.NON_SPACING_MARK.toInt()

private fun youngSerifHas(c: Int): Boolean {
    var low = 0
    var high = YoungSerifRanges.size / 2 - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        when {
            c < YoungSerifRanges[2 * mid] -> high = mid - 1
            c > YoungSerifRanges[2 * mid + 1] -> low = mid + 1
            else -> return true
        }
    }
    return false
}
