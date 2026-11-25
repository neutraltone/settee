/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.Icon
import app.opentv.ui.design.SeekBar
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.design.forText
import app.opentv.ui.design.rememberTextHeight
import app.opentv.ui.formatPlaybackTime

/**
 * The player's controls (docs/LOOK.md, Player): the title and one row of Previous episode, play or
 * pause, Next episode, the seek bar, then Subtitles and Audio, with close at the top right. The
 * Highlight starts on the bar. Only an episode has the episode buttons ([onPreviousEpisode] and
 * [onNextEpisode]), and only where there is one that way: a button that does nothing is a dead end
 * on a remote.
 *
 * Left and Right go along the row, one after another, the bar included: it's picked up with OK
 * ([seekPickedUp], [onPickUp]) before they move it, and then the rest of the row dims. The routes are
 * set by hand all the same, since the row's shape defeats Compose's search: Down from the bar goes
 * sideways to Subtitles (the nearest thing below would be a pill under the bar's middle), and Up from
 * anywhere on the row goes to close, which is nowhere near above it.
 *
 * [pills] come up as the Highlight leaves the bar for a button, into space kept for them, so the
 * controls don't jump as they appear.
 *
 * A stream that can't seek ([seekable] false: most live TV) has no bar, so the play button takes the
 * Highlight in its place, or Subtitles where [showPlayPause] is false because there's nothing to
 * pause. [startFocus] is wherever it starts.
 *
 * [returnFocus] follows the Highlight round the controls, so a panel opened from Subtitles, Audio or a
 * pill can hand it back to that button when it closes. A FocusRequester on the bar's group with a
 * focus restorer was the obvious way, but the player asks for the bar by name ([startFocus]) when it
 * first opens, which goes straight to the start and never through the restorer.
 *
 * [aboveRow] sits at the right above the row, level with the title: the episode player's Skip intro
 * while the controls are up. Up from the row goes to it ([aboveRowFocus]) rather than to close.
 */
@Composable
internal fun PlayerControlBar(
    title: String,
    paused: Boolean,
    positionMs: Long,
    durationMs: Long,
    seekPickedUp: Boolean,
    onPickUp: () -> Unit,
    onScrub: (toMs: Long) -> Unit,
    onPutDown: (jump: Boolean) -> Unit,
    onPlayPause: () -> Unit,
    onSubtitles: () -> Unit,
    onAudio: () -> Unit,
    onClose: () -> Unit,
    pills: List<PlayerPill>,
    startFocus: FocusRequester,
    modifier: Modifier = Modifier,
    seekable: Boolean = true,
    showPlayPause: Boolean = true,
    onPreviousEpisode: (() -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    barInteractions: MutableInteractionSource? = null,
    onBarFocusChanged: (Boolean) -> Unit = {},
    badge: (@Composable () -> Unit)? = null,
    returnFocus: FocusRequester? = null,
    aboveRow: (@Composable () -> Unit)? = null,
    aboveRowFocus: FocusRequester? = null,
) {
    val close = remember { FocusRequester() }
    val firstPill = remember { FocusRequester() }
    val hasPrevious = onPreviousEpisode != null
    val hasNext = onNextEpisode != null
    val requesters = remember { RowRequesters() }
    val focus = RowFocus(requesters, startFocus, seekable, showPlayPause, hasPrevious, hasNext)
    val previous = requesters.previous
    val next = requesters.next
    val playPause = focus.playPause
    val subtitles = focus.subtitles
    val audio = requesters.audio
    var pillsUp by remember { mutableStateOf(false) }
    val lower = Modifier.onFocusChanged { if (it.hasFocus) pillsUp = true }
    val toPills = if (pillsUp && pills.isNotEmpty()) firstPill else FocusRequester.Cancel
    fun FocusProperties.along(spot: FocusRequester) {
        up = aboveRowFocus ?: close
        left = focus.leftOf(spot)
        right = focus.rightOf(spot)
    }
    val dimmed = Modifier.alpha(if (seekPickedUp) DIMMED_ALPHA else 1f)
    val lastHighlight = remember { LastHighlight() }
    val returnTo = lastHighlight.returnTo(pillsUp, pills, hasPrevious, hasNext)
    fun Modifier.remembered(spot: BarSpot) = then(lastHighlight.modifier(spot, returnTo, returnFocus))

    // The episode moved to can have nothing further that way, and its button goes from under the
    // Highlight; play/pause beside it is where the viewer was looking.
    LaunchedEffect(hasPrevious, hasNext) {
        if (lastHighlight.lostWith(hasPrevious, hasNext)) runCatching { playPause.requestFocus() }
    }

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
                .remembered(BarSpot.Close)
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
            TitleLine(title, aboveRow)
            // Room for the picked-up bar's time bubble, which hangs above the row.
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowButton(
                    icon = Icons.Filled.SkipPrevious,
                    label = stringResource(R.string.player_previous_episode),
                    onClick = onPreviousEpisode,
                    modifier = lower
                        .then(dimmed)
                        .focusRequester(previous)
                        .remembered(BarSpot.Previous)
                        .focusProperties {
                            along(previous)
                            down = toPills
                        },
                )
                val (playIcon, playLabel) =
                    if (paused) {
                        Icons.Filled.PlayArrow to R.string.common_play
                    } else {
                        Icons.Filled.Pause to R.string.player_pause
                    }
                RowButton(
                    icon = playIcon,
                    label = stringResource(playLabel),
                    onClick = onPlayPause.takeIf { showPlayPause },
                    modifier = lower
                        .then(dimmed)
                        .focusRequester(playPause)
                        .remembered(focus.playSpot)
                        .focusProperties {
                            along(playPause)
                            down = toPills
                        },
                )
                RowButton(
                    icon = Icons.Filled.SkipNext,
                    label = stringResource(R.string.player_next_episode),
                    onClick = onNextEpisode,
                    modifier = lower
                        .then(dimmed)
                        .focusRequester(next)
                        .remembered(BarSpot.Next)
                        .focusProperties {
                            along(next)
                            down = toPills
                        },
                )
                if (seekable) {
                    Spacer(Modifier.width(8.dp))
                    SeekBar(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        pickedUp = seekPickedUp,
                        onPickUp = onPickUp,
                        onScrub = onScrub,
                        onPutDown = onPutDown,
                        timeLabel = ::formatPlaybackTime,
                        interactionSource = barInteractions,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(startFocus)
                            .remembered(BarSpot.Start)
                            .focusProperties {
                                along(startFocus)
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
                        .then(dimmed)
                        .focusRequester(subtitles)
                        .remembered(focus.subtitlesSpot)
                        .focusProperties {
                            along(subtitles)
                            down = toPills
                        },
                )
                Spacer(Modifier.width(10.dp))
                IconAction(
                    icon = Icons.Filled.Audiotrack,
                    label = stringResource(R.string.player_audio),
                    onClick = onAudio,
                    modifier = lower
                        .then(dimmed)
                        .focusRequester(audio)
                        .remembered(BarSpot.Audio)
                        .focusProperties {
                            along(audio)
                            down = toPills
                        },
                )
            }
            Spacer(Modifier.height(16.dp))
            // Kept at the pills' height while they're away, so the row above doesn't move as they come.
            // Their label's line grows with the font size, so the space for them does too.
            val pillRowHeight = PillRowHeight - rememberTextHeight(SetteeType.Body, 1, atUsualSize = true) +
                rememberTextHeight(SetteeType.Body, 1)
            Box(Modifier.height(pillRowHeight)) {
                if (pillsUp) {
                    PillRow(pills, firstPill = firstPill, up = subtitles) { pill ->
                        lower.remembered(BarSpot.Pill(pill.label))
                    }
                }
            }
        }
    }
}

/** The title, with [aboveRow] at the right of it when there is one. */
@Composable
private fun TitleLine(title: String, aboveRow: (@Composable () -> Unit)?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, SetteeType.Hero, Modifier.weight(1f), maxLines = 2)
        if (aboveRow != null) {
            Spacer(Modifier.width(24.dp))
            aboveRow()
        }
    }
}

/** The requesters for the row's buttons, kept across recompositions as the buttons come and go. */
private class RowRequesters {
    val previous = FocusRequester()
    val playPause = FocusRequester()
    val next = FocusRequester()
    val subtitles = FocusRequester()
    val audio = FocusRequester()
}

/**
 * The row's order, left to right, for this composition: whatever starts it (the bar, or else play or
 * pause, or else Subtitles) has [startFocus] in place of its own requester.
 */
private class RowFocus(
    requesters: RowRequesters,
    startFocus: FocusRequester,
    seekable: Boolean,
    showPlayPause: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
) {
    val playPause = if (seekable) requesters.playPause else startFocus
    val playSpot: BarSpot = if (seekable) BarSpot.PlayPause else BarSpot.Start
    private val rowStarts = seekable || showPlayPause
    val subtitles = if (rowStarts) requesters.subtitles else startFocus
    val subtitlesSpot: BarSpot = if (rowStarts) BarSpot.Subtitles else BarSpot.Start
    private val row = listOfNotNull(
        requesters.previous.takeIf { hasPrevious },
        playPause.takeIf { showPlayPause },
        requesters.next.takeIf { hasNext },
        startFocus.takeIf { seekable },
        subtitles,
        requesters.audio,
    )

    fun leftOf(spot: FocusRequester) = row.getOrNull(row.indexOf(spot) - 1) ?: FocusRequester.Cancel

    fun rightOf(spot: FocusRequester) = row.getOrNull(row.indexOf(spot) + 1) ?: FocusRequester.Cancel
}

@Composable
private fun PlaybackTimes(positionMs: Long, durationMs: Long) {
    Text(
        "${formatPlaybackTime(positionMs)} / ${formatPlaybackTime(durationMs)}",
        SetteeType.Body,
        color = SetteeColors.QuietText,
    )
}

/**
 * A place on the controls the Highlight can be. Pills go by label, which stays put
 * as others come and go.
 */
private sealed interface BarSpot {
    data object Start : BarSpot

    data object Close : BarSpot

    data object Previous : BarSpot

    data object PlayPause : BarSpot

    data object Next : BarSpot

    data object Subtitles : BarSpot

    data object Audio : BarSpot

    data class Pill(val label: String) : BarSpot
}

/** Where the Highlight was last on the controls. */
@Stable
private class LastHighlight {
    private var last by mutableStateOf<BarSpot>(BarSpot.Start)

    // Every element keeps a requester, so the one the Highlight is on isn't rebuilt (and dropped) as
    // returnFocus moves; the others get one nothing asks for.
    private val unasked = FocusRequester()

    /**
     * Where a panel closing should give the Highlight back: the start, if the pill or episode button
     * it was on has gone.
     */
    fun returnTo(pillsUp: Boolean, pills: List<PlayerPill>, hasPrevious: Boolean, hasNext: Boolean): BarSpot {
        val pill = last as? BarSpot.Pill
        return when {
            lostWith(hasPrevious, hasNext) -> BarSpot.Start
            pill == null -> last
            pillsUp && pills.any { it.label == pill.label } -> pill
            else -> BarSpot.Start
        }
    }

    /** Whether the Highlight was last on an episode button that has gone. */
    fun lostWith(hasPrevious: Boolean, hasNext: Boolean) =
        (last == BarSpot.Previous && !hasPrevious) || (last == BarSpot.Next && !hasNext)

    fun modifier(spot: BarSpot, returnTo: BarSpot, returnFocus: FocusRequester?): Modifier = Modifier
        .onFocusChanged { if (it.hasFocus) last = spot }
        .focusRequester(returnFocus?.takeIf { returnTo == spot } ?: unasked)
}

/**
 * The pills, with Up back to the row above ([up]) and the remote kept inside the row's ends. [each]
 * is a pill's own modifier.
 *
 * One row, never scrolled or wrapped (#507): where the labels don't all fit, as in German or Russian
 * at a larger font size, the pills share the width, the shortest keeping their whole label and the
 * longest cut to an ellipsis, and a pill whose label can't keep a recognisable start shows its icon
 * alone. Measured here rather than left to the Row, which gave the first pills all they wanted and
 * squeezed the last until their labels wrapped and were clipped.
 */
@Composable
private fun PillRow(
    pills: List<PlayerPill>,
    firstPill: FocusRequester,
    up: FocusRequester,
    each: (PlayerPill) -> Modifier,
) {
    val measurer = rememberTextMeasurer()
    val locale = LocalConfiguration.current.locales[0]
    val labels = pills.map { it.label }
    // In the face Text will draw each in, so these are the widths the Row will find.
    val natural = remember(labels, locale, measurer) {
        labels.map { label ->
            measurer.measure(label, SetteeType.Body.forText(label, locale), maxLines = 1, softWrap = false).size.width
        }
    }
    BoxWithConstraints {
        val density = LocalDensity.current
        val fits = with(density) {
            fitLabels(
                natural,
                room = constraints.maxWidth - PillGap.roundToPx() * (pills.size - 1),
                labelled = (PillPadding * 2 + PillIcon + PillIconGap).roundToPx(),
                iconOnly = (PillPadding * 2 + PillIcon).roundToPx(),
                shortest = MinPillLabel.roundToPx(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PillGap)) {
            pills.forEachIndexed { index, pill ->
                val fit = fits[index]
                val label = when {
                    fit == null -> PillLabel.IconOnly
                    fit < natural[index] -> PillLabel.Cut(with(density) { fit.toDp() })
                    else -> PillLabel.Whole
                }
                Pill(
                    pill,
                    label = label,
                    modifier = each(pill)
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
}

/** How a pill shows its label. */
private sealed interface PillLabel {
    /** No label: the icon alone, which then names the pill. */
    data object IconOnly : PillLabel

    data object Whole : PillLabel

    /** Cut to [width] with an ellipsis. */
    data class Cut(val width: Dp) : PillLabel
}

/**
 * How wide each pill's label may be, in pixels, given the labels' [natural] widths and the [room]
 * the pills have between their gaps; null for a pill shown as its icon alone. A pill takes
 * [labelled] around its label, or [iconOnly] without one. Labels share what's left evenly, any
 * shorter than an even share keeping their own width; when that share is under [shortest], the
 * longest label goes, its pill to its icon, and the rest share again.
 */
internal fun fitLabels(natural: List<Int>, room: Int, labelled: Int, iconOnly: Int, shortest: Int): List<Int?> {
    // A pixel each in hand, so rounding between this sum and the Row's never squeezes the last pill.
    val usable = room - natural.size
    val shown = natural.indices.toMutableList()
    var share = 0
    while (shown.isNotEmpty()) {
        val budget = usable - labelled * shown.size - iconOnly * (natural.size - shown.size)
        share = evenShare(shown.map { natural[it] }, budget)
        if (share >= shortest) break
        shown.remove(shown.maxBy { natural[it] })
    }
    return natural.indices.map { if (it in shown) minOf(natural[it], share) else null }
}

/**
 * The widest each of [wanted] may be for them to add up to [budget], those under it keeping their
 * own; as wide as the widest when they all fit.
 */
private fun evenShare(wanted: List<Int>, budget: Int): Int {
    var left = budget
    wanted.sorted().forEachIndexed { index, width ->
        val share = left / (wanted.size - index)
        if (width > share) return share
        left -= width
    }
    return wanted.maxOrNull() ?: 0
}

/**
 * Previous, play or pause, or next: a bare icon, cream at rest and coral with the Highlight, with the
 * gap after it, or nothing where there's no [onClick]. Bare rather than a filled circle like the
 * buttons around it, so the transport reads as one group, apart from Subtitles and Audio.
 */
@Composable
private fun RowButton(icon: ImageVector, label: String, onClick: (() -> Unit)?, modifier: Modifier) {
    onClick ?: return
    HighlightSurface(onClick = onClick, style = HighlightStyle.Glyph, modifier = modifier) { highlighted ->
        Icon(
            icon,
            contentDescription = label,
            tint = if (highlighted) SetteeColors.Accent else SetteeColors.Text,
            modifier = Modifier.padding(7.dp).size(32.dp),
        )
    }
    Spacer(Modifier.width(4.dp))
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

/** A pill: its icon and its label, shown as [label] says. */
@Composable
private fun Pill(pill: PlayerPill, label: PillLabel, modifier: Modifier = Modifier) {
    HighlightSurface(onClick = pill.onClick, style = HighlightStyle.Button, modifier = modifier) { highlighted ->
        val colour = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text
        Row(
            Modifier.padding(horizontal = PillPadding, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                pill.icon,
                contentDescription = pill.label.takeIf { label == PillLabel.IconOnly },
                tint = colour,
                modifier = Modifier.size(PillIcon),
            )
            if (label != PillLabel.IconOnly) {
                Spacer(Modifier.width(PillIconGap))
                Text(
                    pill.label,
                    SetteeType.Body,
                    color = colour,
                    maxLines = 1,
                    modifier = if (label is PillLabel.Cut) Modifier.widthIn(max = label.width) else Modifier,
                )
            }
        }
    }
}

private val PillPadding = 16.dp
private val PillIcon = 20.dp
private val PillIconGap = 8.dp
private val PillGap = 10.dp

/** The least of a label worth showing, about four letters and an ellipsis; any less and it's the icon alone. */
private val MinPillLabel = 44.dp

/** The pills' row at the usual font size; it grows by as much as their label's line does. */
private val PillRowHeight = 42.dp

/** The rest of the row while the bar is picked up, so the eye stays on the thumb. */
private const val DIMMED_ALPHA = 0.35f
private val Scrim = SetteeColors.Background.copy(alpha = 0.92f)
