/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.data.repo.ChannelLineup
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.LineupChannel
import kotlinx.coroutines.ensureActive

/**
 * Which Channel is on, over the picture: nothing here can take focus, so the remote carries on doing
 * what it would with the picture alone. The look comes from the player's other overlays until #83.
 */
@Composable
internal fun ChannelBanner(channel: BannerChannel, programmeOnNow: suspend (Long) -> String?) {
    val programme = rememberProgrammeOnNow(channel.key, programmeOnNow)
    Column(
        Modifier
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .padding(32.dp)
            .widthIn(max = 720.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (channel.number != null) {
                Text(
                    channel.number.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Spacer(Modifier.width(16.dp))
            }
            Text(
                channel.name,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (programme != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                programme,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * What's on now for the Channel [key] names, looked up when the banner appears and when its Channel
 * changes. Remembered per Channel rather than cleared by the effect, so no frame draws the last
 * Channel's programme under the new name; changing Channel also cancels a lookup still running, so
 * a slow one can't land on the Channel zapped to since.
 */
@Composable
internal fun rememberProgrammeOnNow(key: String, lookup: suspend (Long) -> String?): String? {
    var title by remember(key) { mutableStateOf<String?>(null) }
    val currentLookup by rememberUpdatedState(lookup)
    LaunchedEffect(key) {
        // A guide that can't be read costs the banner a line, not the viewer the channel.
        val found = runCatching { currentLookup(System.currentTimeMillis()) }
        ensureActive()
        title = found.getOrNull()
    }
    return title
}

/** The title of what's on [channel] at [nowMillis], from the guide it uses. */
internal suspend fun programmeTitleOnNow(
    lineup: ChannelLineup,
    guide: EpgRepository,
    channel: LineupChannel,
    nowMillis: Long,
): String? = lineup.guideId(channel)?.let { guide.programmeOnNow(it, nowMillis)?.title }
