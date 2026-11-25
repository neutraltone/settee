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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

/**
 * Settee's text. The [style] is required and should be one of [SetteeType]'s roles: Material's
 * `Text` falls back to a default size, which is how text under 16 sp crept into the app.
 *
 * Titles switch face in languages, and for letters, Young Serif can't draw ([forText]).
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
    // Merged once for the style, not again for every string: a guide or a list scrolling past
    // changes its texts far more often than their styles.
    val inLocale = remember(style, locale, color, textAlign) {
        style.forLocale(locale).merge(TextStyle(color = color, textAlign = textAlign ?: TextAlign.Unspecified))
    }
    val drawn = remember(text, inLocale) { inLocale.forText(text, locale) }
    BasicText(text, modifier, style = drawn, overflow = overflow, maxLines = maxLines)
}

/**
 * How tall [lines] lines of [style] draw at the current font size, for space kept for text whether
 * or not it's there. Measured rather than added up from the style's line height: Android scales
 * large text less than small (non-linear font scaling), and a line's height in pixels rounds.
 * [atUsualSize] measures it at the usual font size, whatever the system's is: the height a layout
 * drawn for that size has room for.
 */
@Composable
fun rememberTextHeight(style: TextStyle, lines: Int, atUsualSize: Boolean = false): Dp {
    val measurer = rememberTextMeasurer()
    val locale = LocalConfiguration.current.locales[0]
    val density = LocalDensity.current.let { if (atUsualSize) Density(it.density) else it }
    return remember(style, lines, locale, density, measurer) {
        val text = List(lines) { " " }.joinToString("\n")
        with(density) { measurer.measure(text, style.forLocale(locale)).size.height.toDp() }
    }
}

/** Lines of synopsis under a highlighted title, in the guide's band and Movies and Shows' backdrop. */
const val HIGHLIGHT_SYNOPSIS_LINES = 3

/**
 * How tall a highlighted title's text draws: [titleLines] of [title], a [SetteeType.Body] line,
 * [gap], then [HIGHLIGHT_SYNOPSIS_LINES] of [SetteeType.Synopsis]; never less than [atLeast]. The
 * text grows with the font size: at a fixed 150 dp, the synopsis lost its last line from about 1.1
 * times the usual size (#507).
 */
@Composable
fun rememberHighlightTextHeight(title: TextStyle, titleLines: Int, gap: Dp, atLeast: Dp): Dp = maxOf(
    atLeast,
    rememberTextHeight(title, titleLines) + rememberTextHeight(SetteeType.Body, 1) + gap +
        rememberTextHeight(SetteeType.Synopsis, HIGHLIGHT_SYNOPSIS_LINES),
)
