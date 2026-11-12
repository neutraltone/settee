/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import app.opentv.ui.design.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.SeekBar
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.formatPlaybackTime

/** One of the less-used actions that come up under the player's row as pills. */
@Immutable
internal class PlayerPill(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * The player's controls (docs/LOOK.md, Player): the title and one row of a play or pause icon, the
 * seek bar, then Subtitles and Audio, with close at the top right. The Highlight starts on the bar.
 *
 * The routes are set by hand, since the row's shape defeats Compose's search: Down from the bar
 * goes sideways to Subtitles (the nearest thing below would be a pill under the bar's middle), and
 * Up from anywhere on the row goes to close, which is nowhere near above it.
 *
 * [pills] come up as the Highlight leaves the bar for Subtitles, into space kept for them, so the
 * controls don't jump as they appear.
 *
 * A stream that can't seek ([seekable] false: most live TV) has no bar, so the play icon takes the
 * Highlight in its place, or Subtitles where [showPlayPause] is false because there's nothing to
 * pause. [startFocus] is wherever it starts.
 */
@Composable
internal fun PlayerControlBar(
    title: String,
    paused: Boolean,
    positionMs: Long,
    durationMs: Long,
    onScrub: (toMs: Long) -> Unit,
    onScrubEnd: () -> Unit,
    onPlayPause: () -> Unit,
    onSubtitles: () -> Unit,
    onAudio: () -> Unit,
    onClose: () -> Unit,
    pills: List<PlayerPill>,
    startFocus: FocusRequester,
    modifier: Modifier = Modifier,
    seekable: Boolean = true,
    showPlayPause: Boolean = true,
    barInteractions: MutableInteractionSource? = null,
    onBarFocusChanged: (Boolean) -> Unit = {},
    badge: (@Composable () -> Unit)? = null,
) {
    val close = remember { FocusRequester() }
    // Whatever starts the row (the bar, or the play icon where there's no bar) has startFocus.
    val rowStarts = seekable || showPlayPause
    val ownSubtitles = remember { FocusRequester() }
    val subtitles = if (rowStarts) ownSubtitles else startFocus
    val audio = remember { FocusRequester() }
    val firstPill = remember { FocusRequester() }
    var pillsUp by remember { mutableStateOf(false) }
    val lower = Modifier.onFocusChanged { if (it.hasFocus) pillsUp = true }
    val toPills = if (pillsUp && pills.isNotEmpty()) firstPill else FocusRequester.Cancel

    Box(modifier.fillMaxSize()) {
        IconAction(
            icon = Icons.Filled.Close,
            label = stringResource(R.string.player_close),
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                .padding(horizontal = SetteeSpace.ScreenSide, vertical = SetteeSpace.ScreenTopBottom)
                .focusRequester(close)
                .focusProperties { down = startFocus }
                .onFocusChanged { if (it.hasFocus) pillsUp = false },
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim)))
                // Clear of a phone's navigation bar and cutout; zero on a TV.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                )
                .padding(horizontal = SetteeSpace.ScreenSide, vertical = SetteeSpace.ScreenTopBottom),
        ) {
            badge?.invoke()
            Text(title, SetteeType.Hero, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showPlayPause) {
                    PlayPause(
                        paused,
                        onPlayPause,
                        takesHighlight = !seekable,
                        modifier = lower
                            .focusRequester(startFocus)
                            .focusProperties {
                                up = close
                                down = toPills
                                left = FocusRequester.Cancel
                                right = subtitles
                            },
                    )
                }
                if (seekable) {
                    SeekBar(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        onScrub = onScrub,
                        onScrubEnd = onScrubEnd,
                        onPlayPause = onPlayPause,
                        interactionSource = barInteractions,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(startFocus)
                            .focusProperties {
                                up = close
                                down = subtitles
                            }
                            .onFocusChanged {
                                if (it.hasFocus) pillsUp = false
                                onBarFocusChanged(it.hasFocus)
                            },
                    )
                    Spacer(Modifier.width(12.dp))
                    PlaybackTimes(positionMs, durationMs)
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(20.dp))
                IconAction(
                    icon = Icons.Filled.ClosedCaption,
                    label = stringResource(R.string.player_subtitles),
                    onClick = onSubtitles,
                    modifier = lower
                        .focusRequester(subtitles)
                        .focusProperties {
                            up = close
                            down = toPills
                            left = if (rowStarts) startFocus else FocusRequester.Cancel
                            right = audio
                        },
                )
                Spacer(Modifier.width(10.dp))
                IconAction(
                    icon = Icons.Filled.Audiotrack,
                    label = stringResource(R.string.player_audio),
                    onClick = onAudio,
                    modifier = lower
                        .focusRequester(audio)
                        .focusProperties {
                            up = close
                            down = toPills
                            left = subtitles
                            right = FocusRequester.Cancel
                        },
                )
            }
            Spacer(Modifier.height(16.dp))
            // Kept at the pills' height while they're away, so the row above doesn't move as they come.
            Box(Modifier.height(PillRowHeight)) {
                if (pillsUp) PillRow(pills, firstPill = firstPill, up = subtitles, modifier = lower)
            }
        }
    }
}

@Composable
private fun PlaybackTimes(positionMs: Long, durationMs: Long) {
    Text(
        "${formatPlaybackTime(positionMs)} / ${formatPlaybackTime(durationMs)}",
        SetteeType.Body,
        color = SetteeColors.QuietText,
    )
}

/** The pills, with Up back to the row above ([up]) and the remote kept inside the row's ends. */
@Composable
private fun PillRow(pills: List<PlayerPill>, firstPill: FocusRequester, up: FocusRequester, modifier: Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        pills.forEachIndexed { index, pill ->
            Pill(
                pill,
                modifier = modifier
                    .then(if (index == 0) Modifier.focusRequester(firstPill) else Modifier)
                    .focusProperties {
                        this.up = up
                        down = FocusRequester.Cancel
                        if (index == 0) left = FocusRequester.Cancel
                        if (index == pills.lastIndex) right = FocusRequester.Cancel
                    },
            )
        }
    }
}

/**
 * Play or pause at the start of the row. With no bar beside it ([takesHighlight]) it's a button that
 * starts with the Highlight, and [modifier] carries its routes; beside the bar it's only an icon.
 */
@Composable
private fun PlayPause(paused: Boolean, onPlayPause: () -> Unit, takesHighlight: Boolean, modifier: Modifier) {
    if (takesHighlight) {
        IconAction(
            icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            label = stringResource(if (paused) R.string.common_play else R.string.player_pause),
            onClick = onPlayPause,
            modifier = modifier,
        )
    } else {
        PlayPauseIcon(paused, onPlayPause)
        Spacer(Modifier.width(12.dp))
    }
}

/**
 * Shows whether the video is playing, beside the bar. It can't take the Highlight, since Left and
 * Right on the bar next to it seek, so it's only a target for touch; OK on the bar does the same.
 */
@Composable
private fun PlayPauseIcon(paused: Boolean, onPlayPause: () -> Unit) {
    Icon(
        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
        contentDescription = stringResource(if (paused) R.string.common_play else R.string.player_pause),
        tint = SetteeColors.Text,
        modifier = Modifier
            .focusProperties { canFocus = false }
            .clickable(role = Role.Button, onClick = onPlayPause)
            .size(36.dp),
    )
}

@Composable
private fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    HighlightSurface(onClick = onClick, style = HighlightStyle.Button, modifier = modifier) { highlighted ->
        Icon(
            icon,
            contentDescription = label,
            tint = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text,
            modifier = Modifier.padding(10.dp).size(26.dp),
        )
    }
}

@Composable
private fun Pill(pill: PlayerPill, modifier: Modifier = Modifier) {
    HighlightSurface(onClick = pill.onClick, style = HighlightStyle.Button, modifier = modifier) { highlighted ->
        val colour = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text
        Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(pill.icon, contentDescription = null, tint = colour, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(pill.label, SetteeType.Body, color = colour)
        }
    }
}

/** One row of a [PlayerChoicePanel]. */
@Immutable
internal class PlayerChoice(val label: String, val selected: Boolean, val onClick: () -> Unit)

/**
 * A picker over the player (subtitles, audio, quality, aspect ratio): a panel down the right of the
 * picture with the current choice ticked, where the Highlight starts. Back closes it through
 * [onClose].
 */
@Composable
internal fun PlayerChoicePanel(
    title: String,
    choices: List<PlayerChoice>,
    startFocus: FocusRequester,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val start = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
    Column(
        modifier
            // Left to itself, Compose spends the first Back on taking focus off the row, so the
            // panel would stay up with nothing highlighted.
            .onPreviewKeyEvent { event ->
                val isBack = event.key == Key.Back || event.key == Key.Escape
                if (isBack && event.type == KeyEventType.KeyUp) onClose()
                isBack
            }
            .fillMaxHeight()
            .width(ChoicePanelWidth)
            .background(SetteeColors.Surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
            .padding(horizontal = 20.dp, vertical = SetteeSpace.ScreenTopBottom),
    ) {
        Text(title, SetteeType.Section)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (choices.isEmpty()) {
                Text(
                    stringResource(R.string.player_none_available),
                    SetteeType.Body,
                    color = SetteeColors.QuietText,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            choices.forEachIndexed { index, choice ->
                ChoiceRow(choice, if (index == start) Modifier.focusRequester(startFocus) else Modifier)
            }
        }
    }
}

@Composable
private fun ChoiceRow(choice: PlayerChoice, modifier: Modifier) {
    HighlightSurface(
        onClick = choice.onClick,
        style = HighlightStyle.Row,
        modifier = modifier.fillMaxWidth(),
    ) { highlighted ->
        val colour = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(choice.label, SetteeType.RowTitle, color = colour, modifier = Modifier.weight(1f))
            if (choice.selected) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_selected), tint = colour)
            }
        }
    }
}

private val ChoicePanelWidth = 360.dp
private val PillRowHeight = 42.dp
private val Scrim = SetteeColors.Background.copy(alpha = 0.92f)
