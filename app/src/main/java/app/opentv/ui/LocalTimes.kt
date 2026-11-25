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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerClock
import java.time.ZoneId

// Screens draw UTC times through these, so every time on screen is in the graph's zone, the app's
// language and the device's clock, and redraws when they change, rather than in whatever a formatter
// captured when its file was loaded.

/** The viewer's time zone, from the graph: the device's, or the one a test set. */
@Composable
fun viewerZone(): ZoneId {
    val context = LocalContext.current
    val zone by remember(context) { ServiceLocator.get(context).zone }.collectAsState()
    return zone
}

/**
 * The [ViewerClock] for this screen. Its language is the configuration's, which is the one
 * [app.opentv.core.LocaleUtils] put on the activity (a language change recreates it), read here
 * rather than wrapping a context again for every cell of the guide.
 */
@Composable
fun viewerClock(): ViewerClock {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = viewerZone()
    return remember(context, locale, zone) {
        ViewerClock({ locale }, { zone }, { ViewerClock.deviceIs24Hour(context) })
    }
}

/** [utcMillis] as the viewer's clock time: see [ViewerClock.formatTime]. */
@Composable
fun clockTime(utcMillis: Long): String = viewerClock().formatTime(utcMillis)

/** [utcMillis]'s day for the viewer: see [ViewerClock.formatDay]. */
@Composable
fun dayLabel(utcMillis: Long): String = viewerClock().formatDay(utcMillis)

/** [utcMillis]'s day and clock time for the viewer: see [ViewerClock.formatDayTime]. */
@Composable
fun dayTime(utcMillis: Long): String = viewerClock().formatDayTime(utcMillis)
