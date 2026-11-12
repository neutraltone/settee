/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The detail line's test tag, so tests can find it apart from the sentence. */
const val ERROR_DETAIL_TAG = "error_detail"

/**
 * An error as Voice writes it (docs/LOOK.md): the [sentence] saying what happened and what to do,
 * with the [detail] (code · status · format) in a small quiet line under it for anyone who wants it.
 * Takes plain strings, so it never decides what a failure says: docs/ERRORS.md lists that.
 */
@Composable
fun ErrorMessage(
    sentence: String,
    detail: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
    /** For a small space such as the Live Preview tile; the detail line always keeps to one line. */
    maxLines: Int = Int.MAX_VALUE,
) {
    val horizontal = if (textAlign == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start
    Column(modifier, horizontalAlignment = horizontal, verticalArrangement = Arrangement.spacedBy(DETAIL_GAP)) {
        Text(sentence, style = SetteeType.Synopsis, textAlign = textAlign, maxLines = maxLines)
        // Left out rather than drawn empty, so an error with nothing known keeps its spacing.
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = SetteeType.Body,
                color = SetteeColors.QuietText,
                textAlign = textAlign,
                maxLines = 1,
                modifier = Modifier.testTag(ERROR_DETAIL_TAG),
            )
        }
    }
}

/** Close enough to read as one message, apart enough that the quiet line stays quiet. */
private val DETAIL_GAP = 6.dp
