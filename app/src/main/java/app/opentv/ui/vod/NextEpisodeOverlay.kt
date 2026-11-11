/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.player.NextEpisodeCountdown.Overlay
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.ProgressBar
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Reports every key press and touch on the player to [viewModel], so a run of episodes nobody is
 * watching is told apart from someone sitting there. Observes only: the event carries on as usual.
 */
fun Modifier.countsAsInput(viewModel: VodPlayerViewModel): Modifier = this
    .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) viewModel.input()
        false
    }
    .pointerInput(viewModel) {
        awaitPointerEventScope {
            while (true) {
                // Presses only: a mouse or air-remote pointer resting on the picture sends a stream
                // of moves that would otherwise keep resetting the run.
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) viewModel.input()
            }
        }
    }

/**
 * Draws the Next Episode card or "Are you still watching?" over the episode player, whichever
 * [viewModel] says is showing; [onStop] leaves the player.
 */
@Composable
fun NextEpisodeOverlay(viewModel: VodPlayerViewModel, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val overlay by viewModel.overlay.collectAsState()
    val next by viewModel.nextEpisode.collectAsState()
    val scope = rememberCoroutineScope()

    Box(modifier.fillMaxSize()) {
        when (val showing = overlay) {
            is Overlay.Card -> next?.let { card ->
                BackHandler { viewModel.cancel() }
                NextEpisodeCardView(
                    card = card,
                    showing = showing,
                    onPlay = { scope.launch { viewModel.ok() } },
                    onCancel = viewModel::cancel,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(48.dp),
                )
            }
            is Overlay.StillWatching -> {
                BackHandler(onBack = onStop)
                StillWatchingPrompt(onContinue = { scope.launch { viewModel.continueWatching() } }, onStop = onStop)
            }
            Overlay.None -> Unit
        }
    }
}

/**
 * The Next Episode card in the bottom right: a still, the episode, a countdown, and Play now (where
 * the Highlight starts, so OK skips the credits straight away) beside Watch credits.
 */
@Composable
private fun NextEpisodeCardView(
    card: VodPlayerViewModel.NextEpisodeCard,
    showing: Overlay.Card,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier
            .width(CARD_WIDTH)
            .background(SetteeColors.Surface, SetteeShapes.Card)
            .clip(SetteeShapes.Card),
    ) {
        Box(Modifier.fillMaxWidth().height(CARD_WIDTH * 9 / 16).background(SetteeColors.Raised)) {
            card.imageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        if (showing.counting) {
            ProgressBar(showing.secondsLeft / COUNTDOWN_SECONDS, Modifier.fillMaxWidth())
        }
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.player_next_episode_label), SetteeType.Body, color = SetteeColors.Accent)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.player_next_episode_number, card.season, card.episode),
                SetteeType.Body,
                color = SetteeColors.QuietText,
            )
            Text(card.title, SetteeType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(
                if (showing.counting) {
                    stringResource(R.string.player_next_episode_in, showing.secondsLeft)
                } else {
                    stringResource(R.string.player_next_episode_press_ok)
                },
                SetteeType.Body,
                color = SetteeColors.QuietText,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OverlayButton(stringResource(R.string.player_play_now), onPlay, Modifier.focusRequester(focus))
                OverlayButton(stringResource(R.string.player_watch_credits), onCancel)
            }
        }
    }
}

/** "Are you still watching?" centred over a dimmed picture, with Keep watching highlighted. */
@Composable
private fun StillWatchingPrompt(onContinue: () -> Unit, onStop: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Dim), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 560.dp)) {
            Text(stringResource(R.string.player_still_watching), SetteeType.ScreenTitle)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OverlayButton(
                    stringResource(R.string.player_continue_watching),
                    onContinue,
                    Modifier.focusRequester(focus),
                )
                OverlayButton(stringResource(R.string.player_stop), onStop)
            }
        }
    }
}

@Composable
private fun OverlayButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HighlightSurface(onClick = onClick, style = HighlightStyle.Button, modifier = modifier) { highlighted ->
        Text(
            label,
            SetteeType.RowTitle,
            color = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

private val CARD_WIDTH = 340.dp
private const val COUNTDOWN_SECONDS = 15f

// A plain dim over the picture: LOOK.md rules out blur.
private val Dim = SetteeColors.Background.copy(alpha = 0.8f)
