/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Programme
import app.opentv.data.repo.ChannelLineup
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.LineupChannel
import app.opentv.ui.channels.formatTime
import app.opentv.ui.design.ProgressBar
import app.opentv.ui.design.QuietLabel
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.ensureActive

/**
 * Which Channel is on, in the corner over the picture (docs/LOOK.md, Live TV): its logo and name,
 * what's on, how far through it is and what's next. Nothing here can take the Highlight, so the
 * remote carries on doing what it would with the picture alone.
 */
@Composable
internal fun ChannelCard(channel: BannerChannel, nowAndNext: suspend (Long) -> NowAndNext?) {
    val guide = rememberGuideFor(channel.key, nowAndNext)
    Row(
        Modifier
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .padding(horizontal = SetteeSpace.ScreenSide, vertical = SetteeSpace.ScreenTopBottom)
            .width(CARD_WIDTH)
            .background(SetteeColors.Surface, SetteeShapes.Card)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (channel.logoUrl != null) {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = null,
                modifier = Modifier.size(LOGO_SIZE).clip(SetteeShapes.Thumbnail),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                channel.number?.let { Text(it.toString(), SetteeType.RowTitle, color = SetteeColors.QuietText) }
                Text(channel.name, SetteeType.RowTitle, maxLines = 1)
            }
            guide?.now?.let { now ->
                Spacer(Modifier.height(4.dp))
                Text(now.title, SetteeType.Body, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                ProgressBar(now.progressAt(System.currentTimeMillis()), Modifier.fillMaxWidth())
            }
            guide?.next?.let { next ->
                Spacer(Modifier.height(6.dp))
                QuietLabel(stringResource(R.string.guide_next_prefix, formatTime(next.startUtcMillis), next.title))
            }
        }
    }
}

private fun Programme.progressAt(nowMillis: Long): Float =
    if (durationMillis <= 0) 0f else (nowMillis - startUtcMillis).toFloat() / durationMillis

private val CARD_WIDTH = 420.dp
private val LOGO_SIZE = 64.dp

/**
 * What the guide says for the Channel [key] names, looked up when the card appears and when its
 * Channel changes. Remembered per Channel rather than cleared by the effect, so no frame draws the
 * last Channel's programme under the new name; changing Channel also cancels a lookup still running,
 * so a slow one can't land on the Channel zapped to since.
 */
@Composable
internal fun <T : Any> rememberGuideFor(key: String, lookup: suspend (Long) -> T?): T? {
    var found by remember(key) { mutableStateOf<T?>(null) }
    val currentLookup by rememberUpdatedState(lookup)
    LaunchedEffect(key) {
        // A guide that can't be read costs the card a line, not the viewer the channel.
        val result = runCatching { currentLookup(System.currentTimeMillis()) }
        ensureActive()
        found = result.getOrNull()
    }
    return found
}

/** What a Channel's card says: the programme [now] on, if any, and the one [next]. */
data class NowAndNext(val now: Programme?, val next: Programme?)

/** What's on [channel] at [nowMillis] and what follows, from the guide it uses; null with no guide. */
internal suspend fun nowAndNext(
    lineup: ChannelLineup,
    guide: EpgRepository,
    channel: LineupChannel,
    nowMillis: Long,
): NowAndNext? {
    val guideId = lineup.guideId(channel) ?: return null
    val (first, second) = guide.upcoming(guideId, nowMillis, limit = 2).let { it.getOrNull(0) to it.getOrNull(1) }
    val onNow = first?.takeIf { it.startUtcMillis <= nowMillis }
    return NowAndNext(now = onNow, next = if (onNow != null) second else first)
}
