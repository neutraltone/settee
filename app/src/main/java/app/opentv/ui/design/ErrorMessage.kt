/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 *
 * [actions] are the error's own buttons, such as Try again, drawn under the detail line. With
 * [sendReport], a failure that could be Settee's offers Send report under them (ADR 0010), with a
 * line saying what's sent: below the main action, so Try again keeps the Highlight it starts with and
 * Send report is Down from it. Once pressed it says Sent for as long as this error is shown, and
 * stays where the Highlight is rather than vanishing from under it.
 */
@Composable
fun ErrorMessage(
    sentence: String,
    detail: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
    /** For a small space such as the Live Preview tile; the detail line always keeps to one line. */
    maxLines: Int = Int.MAX_VALUE,
    sendReport: ReportOffer? = null,
    actions: (@Composable () -> Unit)? = null,
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
        actions?.let {
            Spacer(Modifier.height(ACTIONS_GAP))
            it()
        }
        sendReport?.let { OfferedReport(it, textAlign) }
    }
}

/**
 * Send report as an error offers it: the [note] saying what's sent, the button's [sendLabel], and
 * [sentLabel] once it's been pressed, which calls [onSend]. The words come from the feature that
 * offers it: what a report holds isn't the design system's to say.
 */
@Immutable
class ReportOffer(val note: String, val sendLabel: String, val sentLabel: String, val onSend: () -> Unit)

/**
 * The [offer]'s note, and its button, which says Sent once pressed for as long as this [offer] is
 * shown. Drawn by [ErrorMessage]; on its own only where the error's main action isn't the error's,
 * such as a form whose buttons sit under the error: there it goes after them, so it's Down from them
 * and they never move.
 */
@Composable
fun OfferedReport(offer: ReportOffer, textAlign: TextAlign = TextAlign.Start) {
    var sent by remember(offer) { mutableStateOf(false) }
    Spacer(Modifier.height(ACTIONS_GAP))
    Text(offer.note, SetteeType.Body, color = SetteeColors.QuietText, textAlign = textAlign)
    ActionButton(
        if (sent) offer.sentLabel else offer.sendLabel,
        onClick = {
            if (!sent) {
                sent = true
                offer.onSend()
            }
        },
    )
}

/** Close enough to read as one message, apart enough that the quiet line stays quiet. */
private val DETAIL_GAP = 6.dp

/** With the column's own gap, the space Message leaves between an error and its buttons. */
private val ACTIONS_GAP = 10.dp
