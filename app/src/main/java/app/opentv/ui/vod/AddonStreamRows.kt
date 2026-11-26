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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.StreamLink
import app.opentv.data.repo.RankedStream
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import java.text.NumberFormat

/** How many torrents were left out for being too big ([count]), and how big a one there's [room] for. */
@Composable
internal fun TooBigLine(count: Int, room: Long, modifier: Modifier = Modifier) {
    Text(
        pluralStringResource(R.plurals.vod_addon_too_big, count, count, sizeText(room)),
        style = SetteeType.Body,
        color = SetteeColors.QuietText,
        modifier = modifier,
    )
}

/**
 * Whether a row can be picked: every link, and torrents while the Torrents switch is on ([torrentsOn]).
 * Off, torrents are still listed, greyed, so the viewer sees an add-on found something and how to play it.
 */
internal fun RankedStream.pickable(torrentsOn: Boolean): Boolean = torrentsOn || stream.link !is StreamLink.Torrent

/**
 * False for a torrent bigger than the [room] this device has for one: the whole file is written to the
 * cache as it plays (ADR 0012), so it would stop part-way. One whose size nobody said is given a go.
 */
internal fun RankedStream.fits(room: Long?): Boolean =
    stream.link !is StreamLink.Torrent || room == null || (sizeBytes ?: 0) <= room

/**
 * One row per stream, greyed and passed over by the Highlight for one that can't be picked. Until the
 * viewer moves, the Highlight stays on the top row that can be picked as better streams arrive above
 * it; once they have, a stream arriving leaves it where they put it.
 */
@Composable
internal fun StreamList(streams: List<RankedStream>, torrentsOn: Boolean, onPick: (RankedStream) -> Unit) {
    val first = remember { FocusRequester() }
    val firstPickable = streams.indexOfFirst { it.pickable(torrentsOn) }
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
                enabled = stream.pickable(torrentsOn),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (index == firstPickable) Modifier.focusRequester(first) else Modifier)
                    .onFocusChanged { if (it.isFocused && index != firstPickable) moved = true },
            ) { highlighted ->
                val titleColour = when {
                    highlighted -> SetteeColors.OnAccent
                    stream.pickable(torrentsOn) -> SetteeColors.Text
                    else -> SetteeColors.QuietText
                }
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stream.stream.title,
                            style = SetteeType.RowTitle,
                            color = titleColour,
                            maxLines = 2,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // Only torrents carry one: they're the streams that work differently (they
                        // need room, depend on people sharing and share back), so the mark stands out.
                        if (stream.stream.link is StreamLink.Torrent) {
                            Spacer(Modifier.width(10.dp))
                            TorrentBadge(highlighted)
                        }
                    }
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

/**
 * The quiet pill after a torrent's title: Mushroom on Cushion, never the accent, which on a TV reads as
 * something to press. On the Highlight it turns plum-ink on cream, like the rest of the row.
 */
@Composable
private fun TorrentBadge(highlighted: Boolean) {
    Text(
        stringResource(R.string.vod_addon_torrent),
        style = SetteeType.Body,
        color = if (highlighted) SetteeColors.OnAccent else SetteeColors.QuietText,
        maxLines = 1,
        modifier = Modifier
            .background(if (highlighted) SetteeColors.Text else SetteeColors.Raised, SetteeShapes.Pill)
            .padding(horizontal = 10.dp),
    )
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
