/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertWithMessage

/*
 * Whether text fits where it's drawn (#507), for a translation or a provider's name longer than the
 * English the screen was laid out with. Find the node with useUnmergedTree = true: the text's own node
 * is the one that answers GetTextLayoutResult.
 */

/** The text's layout as last drawn. */
fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val layouts = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    return layouts.single()
}

/**
 * Asserts every letter is drawn: no line runs past the width it was given or below its height, and
 * none ends in an ellipsis. Line ends, not [TextLayoutResult.hasVisualOverflow]: that compares with
 * the width the paragraph was laid out at, which is the whole space offered, not what the text needs.
 */
fun SemanticsNodeInteraction.assertShownWhole(): SemanticsNodeInteraction = apply {
    val layout = textLayout()
    val text = layout.layoutInput.text
    val tooWide = (0 until layout.lineCount).any { layout.getLineRight(it) > layout.size.width + ROUNDING }
    assertWithMessage("\"$text\" runs past its width: ${layout.describe()}").that(tooWide).isFalse()
    val ellipsised = (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
    assertWithMessage("\"$text\" ends in an ellipsis: ${layout.describe()}").that(ellipsised).isFalse()
    assertNoLineCut()
}

/**
 * Asserts no line drawn is cut at the bottom by the space it was given. It may end in an ellipsis
 * where it has a limit of lines.
 */
fun SemanticsNodeInteraction.assertNoLineCut(): SemanticsNodeInteraction = apply {
    val layout = textLayout()
    val text = layout.layoutInput.text
    assertWithMessage("\"$text\" has its last line cut")
        .that(layout.getLineBottom(layout.lineCount - 1)).isAtMost(layout.size.height.toFloat())
}

/** Asserts the text is on one line, drawn to its full height; it may end in an ellipsis. */
fun SemanticsNodeInteraction.assertOnOneLine(): SemanticsNodeInteraction = apply {
    val text = textLayout().layoutInput.text
    assertWithMessage("\"$text\" wraps").that(textLayout().lineCount).isEqualTo(1)
    assertNoLineCut()
}

/** [content] with the system's font size at [scale], as Settings' font size sets it on a TV. */
@Composable
fun AtFontScale(scale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, scale), content = content)
}

// Text measures in fractions of a pixel and is given whole ones.
private const val ROUNDING = 1f

/** What a failure needs to say about a layout: the size it got against the size its text needed. */
fun TextLayoutResult.describe(): String =
    "drawn ${size.width}x${size.height}, lines ${(0 until lineCount).map { getLineRight(it) }} wide, " +
        "$lineCount lines, ellipsised ${(0 until lineCount).any { isLineEllipsized(it) }}, " +
        "over the height $didOverflowHeight"
