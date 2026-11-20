/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text

/** A bundled font, its upstream source, and its OFL.txt in assets (assets/licences/FONTS.md). */
private class BundledFont(val name: String, val source: String, val licence: String)

private val BundledFonts = listOf(
    BundledFont("Young Serif 3.003", "github.com/noirblancrouge/YoungSerif", "licences/young-serif-OFL.txt"),
    BundledFont("Alegreya Sans 2.004", "github.com/huertatipografica/Alegreya-Sans", "licences/alegreya-sans-OFL.txt"),
)

/**
 * The fonts' licences in full, as a screen of its own opened from About. The OFL asks for its text
 * to travel with the fonts, and the fonts ship beside the app rather than under the GPL, so this is
 * where a viewer can read it. Back returns to About.
 */
@Composable
fun FontLicencesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Two 4 KB assets: read as the screen opens, so it never shows up empty and fills in a moment later.
    val licences = remember { BundledFonts.map { context.readAsset(it.licence) } }
    TextScreen(stringResource(R.string.about_fonts_title), onBack, modifier) {
        BundledFonts.forEachIndexed { index, font ->
            Text(font.name, style = SetteeType.Section, modifier = Modifier.padding(top = 8.dp))
            Text(font.source, style = SetteeType.Body, color = SetteeColors.QuietText)
            Text(licences[index], style = SetteeType.Body)
        }
    }
}

private fun Context.readAsset(path: String): String =
    runCatching { assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("")
