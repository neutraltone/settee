/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.player.NextEpisodeCountdown.Overlay
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
                awaitPointerEvent(PointerEventPass.Initial)
                viewModel.input()
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

@Composable
private fun NextEpisodeCardView(
    card: VodPlayerViewModel.NextEpisodeCard,
    showing: Overlay.Card,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    // The card takes focus as it appears, so OK skips the credits straight away.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        OverlayButton(stringResource(R.string.player_watch_credits), onClick = onCancel)
        Spacer(Modifier.width(16.dp))
        FocusFrame(onClick = onPlay, focusRequester = focus) { focused ->
            Column(Modifier.width(CARD_WIDTH)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(CARD_WIDTH * 9 / 16)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
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
                    LinearProgressIndicator(
                        progress = { showing.secondsLeft / COUNTDOWN_SECONDS },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(Modifier.padding(14.dp)) {
                    Text(
                        stringResource(R.string.player_next_episode_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.player_next_episode_number, card.season, card.episode),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Text(
                        card.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (showing.counting) {
                            stringResource(R.string.player_next_episode_in, showing.secondsLeft)
                        } else {
                            stringResource(R.string.player_next_episode_press_ok)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StillWatchingPrompt(onContinue: () -> Unit, onStop: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 560.dp)) {
            Text(
                stringResource(R.string.player_still_watching),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(28.dp))
            Row {
                OverlayButton(stringResource(R.string.player_continue_watching), onContinue, focus)
                Spacer(Modifier.width(16.dp))
                OverlayButton(stringResource(R.string.player_stop), onStop)
            }
        }
    }
}

@Composable
private fun OverlayButton(label: String, onClick: () -> Unit, focusRequester: FocusRequester? = null) {
    FocusFrame(onClick = onClick, focusRequester = focusRequester) { focused ->
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White,
            modifier = Modifier
                .background(if (focused) MaterialTheme.colorScheme.primary else Color.Transparent)
                .padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
}

/** A clickable surface with the player's focus highlight: an outline in the primary colour. */
@Composable
private fun FocusFrame(
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.85f))
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.2f),
                shape,
            )
            .clickable(onClick = onClick),
    ) {
        content(focused)
    }
}

private val CARD_WIDTH = 320.dp
private const val COUNTDOWN_SECONDS = 15f
