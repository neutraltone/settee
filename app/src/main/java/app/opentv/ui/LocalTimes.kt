/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerTime
import java.time.ZoneId

// Screens draw UTC times through these, so every time on screen is in the graph's zone and redraws
// when it changes, rather than in whichever zone a formatter captured when its file was loaded.

/** The viewer's time zone, from the graph: the device's, or the one a test set. */
@Composable
fun viewerZone(): ZoneId {
    val context = LocalContext.current
    val zone by remember(context) { ServiceLocator.get(context).zone }.collectAsState()
    return zone
}

/** [utcMillis] as the viewer's clock time: see [ViewerTime.clockTime]. */
@Composable
fun clockTime(utcMillis: Long): String = ViewerTime.clockTime(utcMillis, viewerZone())

/** [utcMillis]'s day for the viewer: see [ViewerTime.dayLabel]. */
@Composable
fun dayLabel(utcMillis: Long): String = ViewerTime.dayLabel(utcMillis, viewerZone())
