/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.opentv.R
import app.opentv.core.ViewerError
import app.opentv.data.model.StreamLink
import app.opentv.data.repo.RankedStream
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.Icon
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import java.text.NumberFormat
import kotlinx.coroutines.CoroutineScope
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
     */
    data class Results(
        val streams: List<RankedStream>,
        val finished: Boolean,
        val failure: ViewerError? = null,
        val torrentsOn: Boolean = false,
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
        lookup = scope.launch { find().collect { search = it } }
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
                is AddonSearch.Results -> when {
                    search.streams.isNotEmpty() -> {
                        StreamList(search.streams) {
                            state.close()
                            onPick(it)
                        }
                        if (search.streams.any { !it.pickable }) {
                            QuietLine(
                                if (search.torrentsOn) R.string.error_torrents_not_yet else R.string.error_torrents_off,
                                Modifier.padding(top = 12.dp),
                            )
                        }
                        if (!search.finished) {
                            QuietLine(R.string.vod_addon_still_asking, Modifier.padding(top = 12.dp))
                        }
                    }
                    !search.finished -> QuietLine(R.string.vod_addon_loading)
                    search.failure != null -> AddonsFailed(search.failure, askAgain)
                    else -> QuietLine(R.string.vod_addon_none)
                }
                null -> QuietLine(R.string.vod_addon_loading)
            }
        }
    }
}

@Composable
private fun QuietLine(text: Int, modifier: Modifier = Modifier) {
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

/**
 * Whether a row can be picked. Torrents are listed, greyed, so the viewer sees an add-on found
 * something, but nothing here plays them yet, whether or not the Torrents switch is on.
 */
private val RankedStream.pickable: Boolean get() = stream.link !is StreamLink.Torrent

/**
 * One row per stream, greyed and passed over by the Highlight for one that can't be picked. Until the
 * viewer moves, the Highlight stays on the top row that can be picked as better streams arrive above
 * it; once they have, a stream arriving leaves it where they put it.
 */
@Composable
private fun StreamList(streams: List<RankedStream>, onPick: (RankedStream) -> Unit) {
    val first = remember { FocusRequester() }
    val firstPickable = streams.indexOfFirst { it.pickable }
    val list = rememberLazyListState()
    var moved by remember { mutableStateOf(false) }
    LaunchedEffect(streams) {
        if (!moved) {
            withFrameNanos {}
            list.scrollToItem(0)
            runCatching { first.requestFocus() }
        }
    }
    LazyColumn(
        state = list,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.heightIn(max = 360.dp),
    ) {
        itemsIndexed(streams, key = { _, ranked -> ranked.stream.link.toString() }) { index, stream ->
            HighlightSurface(
                onClick = { onPick(stream) },
                style = HighlightStyle.Row,
                enabled = stream.pickable,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (index == firstPickable) Modifier.focusRequester(first) else Modifier)
                    .onFocusChanged { if (it.isFocused && index != firstPickable) moved = true },
            ) { highlighted ->
                val titleColour = when {
                    highlighted -> SetteeColors.OnAccent
                    stream.pickable -> SetteeColors.Text
                    else -> SetteeColors.QuietText
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    // Torrents are told apart at a glance, before their greyed title is even read.
                    if (stream.stream.link is StreamLink.Torrent) {
                        Icon(
                            Icons.Filled.Hub,
                            contentDescription = stringResource(R.string.vod_addon_torrent),
                            tint = titleColour,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column {
                        Text(stream.stream.title, style = SetteeType.RowTitle, color = titleColour, maxLines = 2)
                        Text(
                            streamLabel(stream, sharersText(stream.sharers)),
                            style = SetteeType.Body,
                            color = if (highlighted) SetteeColors.OnAccent else SetteeColors.QuietText,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Which add-on sent [stream], its Quality, its size and, for a torrent, how many are sharing it
 * ([sharers]), where known: `Films · FHD · 2.1 GB · 45 people sharing`.
 */
private fun streamLabel(stream: RankedStream, sharers: String?): String = listOfNotNull(
    stream.stream.addonName,
    stream.qualityLabel.ifEmpty { null },
    stream.sizeBytes?.let(::sizeText),
    sharers,
).joinToString(" · ")

@Composable
private fun sharersText(sharers: Int?): String? =
    sharers?.let { pluralStringResource(R.plurals.vod_addon_sharers, it, it) }

/** A size as add-ons and Stremio write them, in binary gigabytes or megabytes, in the viewer's digits. */
private fun sizeText(bytes: Long): String {
    val format = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    return if (bytes >= GIGABYTE) {
        "${format.format(bytes.toDouble() / GIGABYTE)} GB"
    } else {
        "${format.format(bytes.toDouble() / MEGABYTE)} MB"
    }
}

private const val GIGABYTE = 1L shl 30
private const val MEGABYTE = 1L shl 20
