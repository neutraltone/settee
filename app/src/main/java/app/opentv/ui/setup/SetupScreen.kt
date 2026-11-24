/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.repo.CatalogueRefresh.Setup
import app.opentv.data.repo.CatalogueRefresh.StepDone
import app.opentv.data.repo.SetupStep
import app.opentv.ui.design.AerialTv
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.ProgressBar
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeMotion
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.update.startsFocused
import java.text.NumberFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val watchLive = onWatchLive?.takeIf { channelsIn && !setup.finished }
    BackHandler { if (setup.finished) onFinish() else watchLive?.invoke() }
    // A step just done says what it found for a moment, in place of the next step's name. Not on
    // opening: only a step finishing while the screen is up.
    var justDone by remember { mutableStateOf<StepDone?>(null) }
    var seen by remember { mutableIntStateOf(setup.done.size) }
    // Like the counts: the finish rises only when it happens in front of the viewer, not on opening.
    val openedFinished = remember { setup.finished }
    LaunchedEffect(setup.done.size) {
        if (setup.done.size <= seen) return@LaunchedEffect
        seen = setup.done.size
        justDone = setup.done.last()
        delay(COUNT_MILLIS)
        justDone = null
    }
    ScreenFrame {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                SetupIcon(setup.finished)
                Spacer(Modifier.height(24.dp))
                if (setup.finished) {
                    Finished(setup, rises = !openedFinished)
                } else {
                    Text(stringResource(R.string.setup_wordplay), SetteeType.Hero)
                    Spacer(Modifier.height(12.dp))
                    val line = justDone?.let { countLine(it) } ?: setup.running?.let { runningLine(it, setup.found) }
                    if (line != null) Text(line, SetteeType.Synopsis, color = SetteeColors.QuietText)
                    Spacer(Modifier.height(20.dp))
                    ProgressBar(setup.bar, Modifier.width(480.dp), working = true)
                }
                // One button that changes, not two: on a Stick, the Highlight asked for by a new
                // Take me to Settee was lost as the focused Watch live TV now left the screen.
                val action = if (setup.finished) onFinish else watchLive
                if (action != null) {
                    Spacer(Modifier.height(32.dp))
                    PillButton(
                        stringResource(if (setup.finished) R.string.setup_take_me_in else R.string.setup_watch_live),
                        onClick = action,
                        modifier = Modifier.startsFocused(),
                    )
                }
            }
        }
    }
}

/**
 * The waiting TV while setup works, so the screen is plainly busy, not stuck; the still mark once
 * it's done, as a TV that kept moving would say it was still going. Both sit in the mark's square,
 * so the text under them doesn't jump when setup finishes. The smaller TV sits at the bottom of it:
 * centred, it left a gap under it far wider than the mark's.
 */
@Composable
private fun SetupIcon(finished: Boolean) {
    Box(Modifier.size(96.dp), contentAlignment = Alignment.BottomCenter) {
        if (finished) {
            Image(painterResource(R.drawable.ic_settee_mark), contentDescription = null, Modifier.fillMaxSize())
        } else {
            // Not "Still working": the progress bar already says that, and a screen reader would
            // hear it twice. The step line under it says which part is loading.
            AerialTv(stringResource(R.string.setup_loading))
        }
    }
}

/**
 * "You're all set." and the summary, rising in two beats when setup finishes with the screen up
 * (docs/LOOK.md, Setting up). The button below isn't part of it: it keeps the Highlight and acts at once.
 */
@Composable
private fun Finished(setup: Setup, rises: Boolean) {
    val title = remember { Animatable(if (rises) 0f else 1f) }
    val rest = remember { Animatable(if (rises) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!rises) return@LaunchedEffect
        launch { title.animateTo(1f, tween(FINISH_RISE_MILLIS, easing = SetteeMotion.Arrive)) }
        rest.animateTo(
            1f,
            tween(FINISH_RISE_MILLIS, delayMillis = FINISH_SECOND_BEAT_MILLIS, easing = SetteeMotion.Arrive),
        )
    }
    Text(stringResource(R.string.setup_finished_title), SetteeType.Hero, modifier = Modifier.rising(title))
    Spacer(Modifier.height(12.dp))
    Column(Modifier.rising(rest), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(summary(setup), SetteeType.Synopsis, color = SetteeColors.QuietText, textAlign = TextAlign.Center)
        setup.failed.mapNotNull { it.failure }.forEach { failure ->
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(failure),
                SetteeType.Body,
                color = SetteeColors.QuietText,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/*
 * Moved and faded in the draw phase only: the column around it is centred, and animating any size or
 * offset in layout would re-lay out the whole screen every frame.
 */
private fun Modifier.rising(progress: Animatable<Float, *>) = graphicsLayer {
    alpha = progress.value
    translationY = (1f - progress.value) * FINISH_RISE.toPx()
}

/** "1,204 channels, 6,400 movies and 1,100 shows.", counting only what loaded, and film details if any did. */
@Composable
private fun summary(setup: Setup): String {
    val loaded = setup.done.filterNot { it.failed }
    val parts = loaded.mapNotNull { done ->
        val count = number(done.count)
        when (done.step) {
            SetupStep.CHANNELS -> pluralStringResource(R.plurals.setup_summary_channels, done.count, count)
            SetupStep.MOVIES -> pluralStringResource(R.plurals.setup_summary_movies, done.count, count)
            SetupStep.SHOWS -> pluralStringResource(R.plurals.setup_summary_shows, done.count, count)
            SetupStep.GUIDE -> null
        }
    }
    val sentence = when (parts.size) {
        0 -> ""
        1 -> stringResource(R.string.setup_summary_one, parts[0])
        2 -> stringResource(R.string.setup_summary_two, parts[0], parts[1])
        else -> stringResource(R.string.setup_summary_three, parts[0], parts[1], parts[2])
    }
    // Genres, cast and directors are looked up a film at a time, far too long to wait for.
    val films = loaded.any { it.step == SetupStep.MOVIES && it.count > 0 }
    return if (films) "$sentence ${stringResource(R.string.setup_summary_film_details)}" else sentence
}

/** What the finished screen says when this step didn't load; the channels never get that far. */
private val SetupStep.failure: Int?
    get() = when (this) {
        SetupStep.GUIDE -> R.string.setup_failed_guide
        SetupStep.MOVIES -> R.string.setup_failed_movies
        SetupStep.SHOWS -> R.string.setup_failed_shows
        // A login that fails goes back to the form, so Setup never finishes with it.
        SetupStep.CHANNELS -> null
    }

/** The running step's name, with what it has found so far where it counts as it goes. */
@Composable
private fun runningLine(step: SetupStep, found: Int): String {
    val counting = when (step) {
        SetupStep.GUIDE -> R.plurals.setup_step_guide_count
        SetupStep.MOVIES -> R.plurals.setup_step_movies_count
        SetupStep.SHOWS -> R.plurals.setup_step_shows_count
        // The channels are saved in one go, with nothing to count until they're all in.
        SetupStep.CHANNELS -> null
    }
    return if (counting != null && found > 0) {
        pluralStringResource(counting, found, number(found))
    } else {
        stringResource(step.label)
    }
}

/** How long a step's count stays up once it's done. */
private const val COUNT_MILLIS = 2_000L

// Longer than the usual 300 ms: it's seen once per install. If it misses the frame budget on the
// Stick, these go to 0, never longer or softer.
private const val FINISH_RISE_MILLIS = 400
private const val FINISH_SECOND_BEAT_MILLIS = 80
private val FINISH_RISE = 12.dp

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
private fun number(n: Int): String = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(n)

private val SetupStep.label: Int
    get() = when (this) {
        SetupStep.CHANNELS -> R.string.setup_step_channels
        SetupStep.GUIDE -> R.string.setup_step_guide
        SetupStep.MOVIES -> R.string.setup_step_movies
        SetupStep.SHOWS -> R.string.setup_step_shows
    }
