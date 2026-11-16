/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.ui.design.AerialTv
import app.opentv.ui.design.AerialTvSize
import app.opentv.ui.design.HighlightLook
import app.opentv.ui.design.HighlightRing
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.Icon
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text

/**
 * One row of a [PlayerChoicePanel]. [detail] is a quieter second line. [onAdjust], when set, makes
 * the row a control: left and right call it with -1 and +1 instead of leaving the row. At its end:
 * [end] when it's waiting, else a tick when [selected], else [end]'s icon if it has one.
 */
@Immutable
internal class PlayerChoice(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val detail: String? = null,
    val onAdjust: ((Int) -> Unit)? = null,
    val end: RowEnd? = null,
)

/** What a [PlayerChoice] row shows at its end besides the tick. */
internal sealed interface RowEnd {
    /** The small aerial TV, waiting for what the row does; [description] names the wait for TalkBack. */
    data class Waiting(val description: String) : RowEnd

    /** An icon saying something about the row, such as Downloaded; the tick wins over it. */
    data class Mark(val icon: ImageVector) : RowEnd
}

/**
 * A picker over the player (subtitles, audio, quality, aspect ratio): a panel down the right of the
 * picture with the current choice ticked, where the Highlight starts. Back closes it through
 * [onClose].
 *
 * The remote's arrows always stay in the panel. With no rows its message (or, with none, its footer)
 * takes the Highlight, so the Highlight isn't left on the button under it, where Back would only take
 * it off that button.
 */
@Composable
internal fun PlayerChoicePanel(
    title: String,
    choices: List<PlayerChoice>,
    startFocus: FocusRequester,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shown under the rows, such as OpenSubtitles' attribution, or in place of them, such as an error. */
    footer: (@Composable () -> Unit)? = null,
    /** What's said when there are no rows; null for the usual "None available". */
    empty: String? = null,
) {
    val start = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
    val noRows = choices.isEmpty()
    // Whether the empty panel had the Highlight, so rows arriving (audio tracks found after the panel
    // opened) can take it over: their arrival removes what held it, and nothing else asks again.
    var heldWhileEmpty by remember { mutableStateOf(false) }
    LaunchedEffect(noRows) {
        if (!noRows && heldWhileEmpty) {
            heldWhileEmpty = false
            withFrameNanos {}
            runCatching { startFocus.requestFocus() }
        }
    }
    Column(
        modifier
            // Left to itself, Compose spends the first Back on taking focus off the row, so the
            // panel would stay up with nothing highlighted.
            .onPreviewKeyEvent { event ->
                val isBack = event.isBack()
                if (isBack && event.type == KeyEventType.KeyUp) onClose()
                isBack
            }
            // The panel is drawn over the control bar, whose buttons stay composed underneath, so an
            // arrow off its edge would move a Highlight the viewer can't see onto the hidden Close
            // (which quits) or seek bar (#425). So the remote stays in the panel; within it, rows and
            // a footer's buttons are still reachable. With rows, code may still move the Highlight
            // out (Enter and Exit; the rest are keys). Rejected: a Dialog, as SidePanel does, would
            // put a second window over the video and change picture-in-picture and Back; a flag that
            // stops the bar taking focus needs every player to pass it.
            .focusProperties {
                onExit = {
                    val byCode = requestedFocusDirection == FocusDirection.Enter ||
                        requestedFocusDirection == FocusDirection.Exit
                    if (noRows || !byCode) cancelFocusChange()
                }
            }
            .focusGroup()
            .fillMaxHeight()
            .width(ChoicePanelWidth)
            .background(SetteeColors.Surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
            .padding(horizontal = 20.dp, vertical = SetteeSpace.ScreenTopBottom),
    ) {
        Text(title, SetteeType.Section)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val holder = Modifier
                .fillMaxWidth()
                .focusRequester(startFocus)
                .onFocusChanged { if (it.isFocused) heldWhileEmpty = true }
            if (noRows) {
                NoRows(empty ?: stringResource(R.string.player_none_available), footer, holder)
            } else {
                choices.forEachIndexed { index, choice ->
                    ChoiceRow(choice, if (index == start) Modifier.focusRequester(startFocus) else Modifier)
                }
                footer?.let {
                    Spacer(Modifier.height(12.dp))
                    it()
                }
            }
        }
    }
}

/**
 * An empty panel's [message], as a row holding the Highlight so the viewer can see where they are,
 * then its [footer]. With no message the footer holds it, in a ring rather than the coral fill,
 * which would hide an error's own colours. [holder] goes on whichever holds it.
 */
@Composable
private fun NoRows(message: String, footer: (@Composable () -> Unit)?, holder: Modifier) {
    if (message.isNotEmpty()) {
        HighlightSurface(onClick = {}, style = HighlightStyle.Row, modifier = holder) { highlighted ->
            Text(
                message,
                SetteeType.Body,
                color = if (highlighted) SetteeColors.OnAccent else SetteeColors.QuietText,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        footer?.let {
            Spacer(Modifier.height(12.dp))
            it()
        }
    } else {
        Spacer(Modifier.height(12.dp))
        HighlightSurface(onClick = {}, style = FooterHighlight, modifier = holder) { footer?.invoke() }
    }
}

private val FooterHighlight = HighlightStyle(
    resting = HighlightLook(SetteeShapes.Card),
    highlighted = HighlightLook(SetteeShapes.Card, ring = HighlightRing()),
)

@Composable
private fun ChoiceRow(choice: PlayerChoice, modifier: Modifier) {
    val adjust = choice.onAdjust
    val adjusting = if (adjust == null) {
        Modifier
    } else {
        Modifier.onPreviewKeyEvent { event ->
            val step = when (event.key) {
                Key.DirectionLeft -> -1
                Key.DirectionRight -> 1
                else -> 0
            }
            if (step != 0 && event.type == KeyEventType.KeyDown) adjust(step)
            step != 0
        }
    }
    HighlightSurface(
        onClick = choice.onClick,
        style = HighlightStyle.Row,
        modifier = modifier.fillMaxWidth().then(adjusting),
    ) { highlighted ->
        val colour = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(choice.label, SetteeType.RowTitle, color = colour)
                choice.detail?.let {
                    Text(it, SetteeType.Body, color = if (highlighted) colour else SetteeColors.QuietText)
                }
            }
            ChoiceEnd(choice, colour, highlighted)
        }
    }
}

private val ChoicePanelWidth = 360.dp

/** What sits at the end of a [ChoiceRow]: waiting, the tick, or the row's own icon. */
@Composable
private fun ChoiceEnd(choice: PlayerChoice, colour: Color, highlighted: Boolean) {
    val end = choice.end
    when {
        end is RowEnd.Waiting -> AerialTv(end.description, size = AerialTvSize.Small, onHighlight = highlighted)
        choice.selected ->
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_selected), tint = colour)
        end is RowEnd.Mark -> Icon(end.icon, contentDescription = null, tint = colour)
    }
}
