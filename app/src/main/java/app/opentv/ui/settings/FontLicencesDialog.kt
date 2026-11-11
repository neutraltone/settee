/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.opentv.R
import app.opentv.ui.design.ActionButton
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.update.rememberDpadScroll
import app.opentv.update.startsFocused

/** A bundled font, its upstream source, and its OFL.txt in assets (assets/licences/FONTS.md). */
private class BundledFont(val name: String, val source: String, val licence: String)

private val BundledFonts = listOf(
    BundledFont("Young Serif 3.003", "github.com/noirblancrouge/YoungSerif", "licences/young-serif-OFL.txt"),
    BundledFont("Alegreya Sans 2.004", "github.com/huertatipografica/Alegreya-Sans", "licences/alegreya-sans-OFL.txt"),
)

/**
 * The fonts' licences in full. The OFL asks for its text to travel with the fonts, and the fonts
 * ship beside the app rather than under the GPL, so this is where a viewer can read it.
 *
 * Plain text can't take the Highlight, so Up and Down on the close button scroll the text, as in
 * What's new.
 */
@Composable
internal fun FontLicencesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Two 4 KB assets: read as the dialog opens, so it never shows up empty and fills in a moment later.
    val licences = remember { BundledFonts.map { context.readAsset(it.licence) } }
    val scroll = rememberScrollState()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxHeight(0.85f)
                .background(SetteeColors.Surface, SetteeShapes.Card)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.about_fonts_title), style = SetteeType.ScreenTitle)
            Column(
                Modifier.weight(1f).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BundledFonts.forEachIndexed { index, font ->
                    Text(font.name, style = SetteeType.Section, modifier = Modifier.padding(top = 8.dp))
                    Text(font.source, style = SetteeType.Body, color = SetteeColors.QuietText)
                    Text(licences[index], style = SetteeType.Body)
                }
            }
            ActionButton(
                stringResource(R.string.common_done),
                onClick = onDismiss,
                modifier = rememberDpadScroll(scroll).startsFocused(),
            )
        }
    }
}

private fun Context.readAsset(path: String): String =
    runCatching { assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("")
