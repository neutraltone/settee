/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.opentv.R
import app.opentv.core.ViewerError
import app.opentv.data.repo.RankedStream
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** What a title's Add-on sources picker has to show. */
sealed interface AddonSearch {
    /**
     * The add-ons can't be asked about this title, and [error] says why: it has no IMDb match, which
     * can't change, or TMDB didn't answer, which can, so only then is there a Try again ([canRetry]).
     */
    data class CantAsk(val error: ViewerError, val canRetry: Boolean) : AddonSearch

    /**
     * What the add-ons have offered so far, best first, and whether every one has answered. [failure]
     * says why the first add-on that couldn't be reached couldn't, or is null when every one answered.
     * [torrentsOn] is the Torrents switch, which decides what the line under torrent rows says.
     * [torrentRoom] is the biggest torrent this device has room for, in bytes, or null when none was
     * offered; bigger ones are left out of the list and counted on a line instead.
     */
    data class Results(
        val streams: List<RankedStream>,
        val finished: Boolean,
        val failure: ViewerError? = null,
        val torrentsOn: Boolean = false,
        val torrentRoom: Long? = null,
    ) : AddonSearch
}

/**
 * Whether a title's picker is open, and what its add-ons have said: [ask] opens it on a fresh lookup,
 * and [close] cancels that lookup, so an answer arriving later can't open it again.
 */
@Stable
class AddonPickerState internal constructor(private val scope: CoroutineScope) {
    var open by mutableStateOf(false)
        private set

    /** Null until the first answer. */
    var search by mutableStateOf<AddonSearch?>(null)
        private set

    private var lookup: Job? = null

    fun ask(find: () -> Flow<AddonSearch>) {
        lookup?.cancel()
        open = true
        search = null
        // Collected on the main thread, where the picker's window composes: on a scope whose dispatcher
        // doesn't move it back (Compose's test one), each answer was written from the add-on's io thread,
        // and one landing while the window's first composition read the one before was never redrawn.
        lookup = scope.launch(Dispatchers.Main.immediate) { find().collect { search = it } }
    }

    fun close() {
        lookup?.cancel()
        lookup = null
        open = false
        search = null
    }
}

/** A picker's state for the title [key] names, started afresh when that changes. */
@Composable
fun rememberAddonPickerState(key: Any?): AddonPickerState {
    val scope = rememberCoroutineScope()
    return remember(key) { AddonPickerState(scope) }
}

/**
 * The streams a title's add-ons offer, shown while [state] is open: a line until the first arrive,
 * then one row per stream as each add-on answers. When none came, it says whether nothing was found,
 * an add-on couldn't be reached (with Try again, which calls [find] anew), or the add-ons can't look
 * the title up at all. Picking a row closes it and calls [onPick]; Back closes it and calls [onDismiss].
 */
@Composable
fun AddonStreamPicker(
    state: AddonPickerState,
    find: () -> Flow<AddonSearch>,
    onDismiss: () -> Unit = {},
    onPick: (RankedStream) -> Unit,
) {
    if (!state.open) return
    Dialog(
        onDismissRequest = {
            state.close()
            onDismiss()
        },
    ) {
        Column(
            Modifier
                .width(560.dp)
                .background(SetteeColors.Surface, SetteeShapes.Card)
                .padding(24.dp),
        ) {
            Text(stringResource(R.string.vod_addon_pick_title), style = SetteeType.Section)
            Spacer(Modifier.height(12.dp))
            val askAgain = { state.ask(find) }
            when (val search = state.search) {
                is AddonSearch.CantAsk -> AddonsFailed(search.error, askAgain.takeIf { search.canRetry })
                is AddonSearch.Results -> Results(search, askAgain) {
                    state.close()
                    onPick(it)
                }
                null -> QuietLine(R.string.vod_addon_loading)
            }
        }
    }
}

/**
 * What the add-ons have offered: the streams that can be listed, the lines under them, or, with none,
 * whether they're still being asked, one couldn't be reached (with [askAgain]) or nothing was found.
 */
@Composable
private fun Results(search: AddonSearch.Results, askAgain: () -> Unit, onPick: (RankedStream) -> Unit) {
    // Left out rather than greyed: no setting makes one fit, so a row would only be something to pass
    // over, and the line says what there's room for.
    val (shown, tooBig) = search.streams.partition { it.fits(search.torrentRoom) }
    when {
        search.streams.isNotEmpty() -> {
            if (shown.isNotEmpty()) StreamList(shown, search.torrentsOn, onPick)
            val gap = Modifier.padding(top = if (shown.isEmpty()) 0.dp else 12.dp)
            if (shown.any { !it.pickable(search.torrentsOn) }) QuietLine(R.string.error_torrents_off, gap)
            if (tooBig.isNotEmpty() && search.torrentRoom != null) TooBigLine(tooBig.size, search.torrentRoom, gap)
            if (!search.finished) QuietLine(R.string.vod_addon_still_asking, Modifier.padding(top = 12.dp))
        }
        !search.finished -> QuietLine(R.string.vod_addon_loading)
        search.failure != null -> AddonsFailed(search.failure, askAgain)
        else -> QuietLine(R.string.vod_addon_none)
    }
}

@Composable
internal fun QuietLine(text: Int, modifier: Modifier = Modifier) {
    Text(stringResource(text), style = SetteeType.Body, color = SetteeColors.QuietText, modifier = modifier)
}

/**
 * Gives [requester] the Highlight once the picker's window is up, again whenever [shown] changes.
 * Asked from inside the dialog: its window composes after the page's, and a FocusRequester asked
 * before it is attached throws.
 */
@Composable
private fun FocusOnceShown(requester: FocusRequester, shown: Any) {
    LaunchedEffect(shown) {
        withFrameNanos {}
        runCatching { requester.requestFocus() }
    }
}

/** Why the add-ons gave nothing, and Try again when there's [onRetry], which has the Highlight. */
@Composable
private fun AddonsFailed(error: ViewerError, onRetry: (() -> Unit)?) {
    ErrorMessage(error.sentence, error.detail, textAlign = TextAlign.Start)
    if (onRetry != null) {
        val retry = remember { FocusRequester() }
        FocusOnceShown(retry, error)
        Spacer(Modifier.height(18.dp))
        PillButton(
            label = stringResource(R.string.common_try_again),
            onClick = onRetry,
            modifier = Modifier.focusRequester(retry),
        )
    }
}
