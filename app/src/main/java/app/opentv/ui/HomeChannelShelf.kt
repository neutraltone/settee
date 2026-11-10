/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import app.opentv.data.repo.LineupChannel
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.QuietLabel
import app.opentv.ui.design.ScreenFrameScope
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Shelf
import app.opentv.ui.design.Text
import app.opentv.ui.design.rememberShelfMemory
import app.opentv.ui.design.shelfCard
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.openOptionsOn
import app.opentv.ui.options.rememberRowHighlight
import app.opentv.ui.vod.CardFocus
import app.opentv.ui.vod.rememberRowState
import app.opentv.ui.vod.takesFocus
import coil.compose.AsyncImage

/** Home's Favourite channels row; [focus] brings the highlight back to a channel picked before. */
@Composable
internal fun ScreenFrameScope.ChannelShelf(
    channels: List<HomeChannel>,
    onPlayChannel: (Channel) -> Unit,
    focus: CardFocus?,
) {
    val memory = rememberShelfMemory(remember(channels) { channels.map { it.channel.key } })
    val rowState = rememberRowState(focus)
    val highlight = rememberRowHighlight(channels, rowState) { it.channel.key }
    // Lined up by their squares: a tile with nothing on now is a line shorter underneath.
    Shelf(
        stringResource(R.string.home_favourite_channels),
        memory,
        state = rowState,
        verticalAlignment = Alignment.Top,
    ) {
        itemsIndexed(channels, key = { _, channel -> channel.channel.key }) { index, channel ->
            ChannelTile(
                channel,
                Modifier.takesFocus(
                    index,
                    focus,
                ).shelfCard(memory, channel.channel.key).then(highlight.modifier(index, channel.channel.key)),
            ) { onPlayChannel(channel.channel.primary) }
        }
    }
}

/**
 * A Favourite channel: its logo fitted with a margin on a plain square, with no text over it since
 * logos are busy and often white, and its name and what's on now underneath.
 */
@Composable
private fun ChannelTile(channel: HomeChannel, modifier: Modifier, onClick: () -> Unit) {
    val lineupChannel: LineupChannel = channel.channel
    Column(Modifier.width(TILE_SIZE)) {
        val name = lineupChannel.primary.shownName
        HighlightSurface(
            onClick,
            HighlightStyle.Card,
            // The name is drawn under the square but belongs to it, for TalkBack and for tests.
            modifier.semantics { text = AnnotatedString(name) },
            onOptions = openOptionsOn(OptionsSubject.OfChannel(lineupChannel, onWatch = onClick)),
        ) {
            Box(Modifier.size(TILE_SIZE).background(SetteeColors.Surface)) {
                AsyncImage(
                    model = lineupChannel.primary.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(LOGO_MARGIN),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(name, style = SetteeType.CardTitle, modifier = Modifier.clearAndSetSemantics {}, maxLines = 1)
        channel.nowOn?.let { QuietLabel(it) }
    }
}

/** As wide as a cover, and square like Live TV's logos. */
private val TILE_SIZE = 118.dp
private val LOGO_MARGIN = 16.dp
