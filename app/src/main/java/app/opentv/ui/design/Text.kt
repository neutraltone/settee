/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * Settee's text. The [style] is required and should be one of [SetteeType]'s roles: Material's
 * `Text` falls back to a default size, which is how text under 16 sp crept into the app.
 *
 * Titles switch face in languages Young Serif can't draw ([forLocale]).
 */
@Composable
fun Text(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = SetteeColors.Text,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val locale = LocalConfiguration.current.locales[0]
    val drawn = remember(style, locale, color, textAlign) {
        style.forLocale(locale).merge(TextStyle(color = color, textAlign = textAlign ?: TextAlign.Unspecified))
    }
    BasicText(text, modifier, style = drawn, overflow = overflow, maxLines = maxLines)
}
