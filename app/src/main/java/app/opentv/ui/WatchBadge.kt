/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opentv.R
import app.opentv.data.repo.WatchStatus

/**
 * A quiet label saying where the Profile is with a title, for drawing on its artwork. Deliberately
 * not a filled icon in the focus colour: on a TV that reads as a button to press.
 */
@Composable
fun WatchBadge(status: WatchStatus, modifier: Modifier = Modifier) {
    when (status) {
        WatchStatus.Watched -> Label(Icons.Outlined.CheckCircle, stringResource(R.string.watch_badge_watched), modifier)
        is WatchStatus.InProgress -> Label(Icons.Outlined.Timelapse, inProgressText(status.remainingMillis), modifier)
        WatchStatus.Unwatched -> Unit
    }
}

@Composable
private fun inProgressText(remainingMillis: Long?): String {
    if (remainingMillis == null) return stringResource(R.string.watch_badge_in_progress)
    val minutes = ((remainingMillis + 30_000) / 60_000).toInt().coerceAtLeast(1)
    return pluralStringResource(R.plurals.watch_badge_minutes_left, minutes, minutes)
}

@Composable
private fun Label(icon: ImageVector, text: String, modifier: Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier
            .semantics(mergeDescendants = true) {}
            .background(Color.Black.copy(alpha = 0.6f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.5f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp))
    }
}
