/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ViewerError
import app.opentv.diagnostics.Gathered
import app.opentv.diagnostics.OpenKind
import app.opentv.diagnostics.fold
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text

/* The lines the Diagnostics report is drawn from: a part's heading, a label and value, a failure in words. */

@StringRes
internal fun kindName(kind: OpenKind): Int = when (kind) {
    OpenKind.Film -> R.string.diagnostics_kind_film
    OpenKind.Episode -> R.string.diagnostics_kind_episode
    OpenKind.AddonStream -> R.string.diagnostics_kind_addon
}

/** In the device's own time and format: this screen is read by whoever is in front of the TV. */
internal fun Context.time(utcMillis: Long): String =
    DateFormat.getMediumDateFormat(this).format(utcMillis) + " " + DateFormat.getTimeFormat(this).format(utcMillis)

@Composable
internal fun Part(@StringRes title: Int, body: @Composable () -> Unit) {
    Text(stringResource(title), SetteeType.Section, modifier = Modifier.padding(top = 12.dp))
    body()
}

@Composable
internal fun <T> Gathered(part: Gathered<T>, body: @Composable (T) -> Unit) {
    part.fold(got = { body(it) }, failed = { ErrorMessage(it.sentence, it.detail, textAlign = TextAlign.Start) })
}

@Composable
internal fun Line(@StringRes label: Int, value: String) = Plain(stringResource(label), value)

@Composable
internal fun Plain(label: String, value: String) {
    Text("$label: $value", SetteeType.Body)
}

@Composable
internal fun Failure(label: String, failure: ViewerError?) {
    Text(label, SetteeType.Body)
    failure?.let { ErrorMessage(it.sentence, it.detail, textAlign = TextAlign.Start) }
}

@Composable
internal fun Quiet(text: String) {
    Text(text, SetteeType.Body, color = SetteeColors.QuietText)
}
