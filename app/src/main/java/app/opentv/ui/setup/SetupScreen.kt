/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.repo.CatalogueRefresh.Setup
import app.opentv.data.repo.CatalogueRefresh.StepDone
import app.opentv.data.repo.SetupStep
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.ProgressBar
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.update.startsFocused
import java.text.NumberFormat
import kotlinx.coroutines.delay

/**
 * Setup: the first provider's Catalogue Refresh, shown until the library is ready to browse
 * (docs/LOOK.md, Setting up). [onWatchLive] is null when Live TV is switched off, when there's no
 * way out before setup finishes.
 */
@Composable
fun SetupScreen(setup: Setup, onWatchLive: (() -> Unit)?, onFinish: () -> Unit) {
    val channelsIn = setup.done.any { it.step == SetupStep.CHANNELS }
    // Live TV really works once the channels are in, while movies and shows don't yet, so that's the
    // one way out early. Before then Back does nothing: there's nothing to watch. The Home button
    // still leaves, as Android always allows.
    val watchLive = onWatchLive?.takeIf { channelsIn }
    BackHandler { watchLive?.invoke() }
    // A step just done says what it found for a moment, in place of the next step's name. Not on
    // opening: only a step finishing while the screen is up.
    var justDone by remember { mutableStateOf<StepDone?>(null) }
    val seen = remember { intArrayOf(setup.done.size) }
    LaunchedEffect(setup.done.size) {
        if (setup.done.size <= seen[0]) return@LaunchedEffect
        seen[0] = setup.done.size
        justDone = setup.done.last()
        delay(COUNT_MILLIS)
        justDone = null
    }
    ScreenFrame {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Image(painterResource(R.drawable.ic_settee_mark), contentDescription = null, Modifier.size(96.dp))
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.setup_wordplay), SetteeType.Hero)
                Spacer(Modifier.height(12.dp))
                val line = justDone?.let { countLine(it) } ?: setup.running?.let { stringResource(it.label) }
                if (line != null) Text(line, SetteeType.Synopsis, color = SetteeColors.QuietText)
                Spacer(Modifier.height(20.dp))
                ProgressBar(setup.bar, Modifier.width(480.dp))
                if (watchLive != null) {
                    Spacer(Modifier.height(32.dp))
                    PillButton(stringResource(R.string.setup_watch_live), onClick = watchLive, Modifier.startsFocused())
                }
            }
        }
    }
}

/** How long a step's count stays up once it's done. */
private const val COUNT_MILLIS = 2_000L

@Composable
private fun countLine(done: StepDone): String? {
    if (done.failed) return null
    val count = number(done.count)
    return when (done.step) {
        SetupStep.CHANNELS -> pluralStringResource(R.plurals.setup_done_channels, done.count, count)
        SetupStep.GUIDE -> done.of?.let {
            pluralStringResource(R.plurals.setup_done_guide, it, count, number(it))
        }
        SetupStep.MOVIES -> pluralStringResource(R.plurals.setup_done_movies, done.count, count)
        SetupStep.SHOWS -> pluralStringResource(R.plurals.setup_done_shows, done.count, count)
    }
}

/** With the viewer's thousands separator: "1,204". */
@Composable
private fun number(n: Int): String =
    NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(n)

private val SetupStep.label: Int
    get() = when (this) {
        SetupStep.CHANNELS -> R.string.setup_step_channels
        SetupStep.GUIDE -> R.string.setup_step_guide
        SetupStep.MOVIES -> R.string.setup_step_movies
        SetupStep.SHOWS -> R.string.setup_step_shows
    }
