/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.player.torrent.TorrentStatus
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import kotlinx.coroutines.flow.StateFlow

/**
 * Under the player's waiting TV while a torrent starts (#670): looking for people sharing it, then how
 * many it has found, then how many it's fetching from. A torrent takes far longer to start than a link,
 * and a count that moves says it's getting somewhere. Nothing when the stream isn't a torrent.
 *
 * Once the film's length is known ([durationMillis], the player's), a torrent arriving slower than it
 * plays also says so: otherwise it stops every few minutes with no reason given, and Try again or
 * waiting longer can't help, where a smaller copy can.
 */
@Composable
internal fun TorrentProgress(status: StateFlow<TorrentStatus>, durationMillis: () -> Long) {
    val torrent by status.collectAsState()
    val line = when (val now = torrent) {
        is TorrentStatus.Connecting -> if (now.peers == 0) {
            stringResource(R.string.player_torrent_connecting)
        } else {
            pluralStringResource(R.plurals.player_torrent_found, now.peers, now.peers)
        }
        is TorrentStatus.Ready ->
            pluralStringResource(R.plurals.player_torrent_fetching, now.peers, now.peers)
        else -> null
    } ?: return
    Spacer(Modifier.height(8.dp))
    Text(line, SetteeType.Body, color = SetteeColors.QuietText, modifier = Modifier.padding(horizontal = 24.dp))
    // Asked again each time the status changes, which is every second while it's fetching.
    val now = torrent
    if (now is TorrentStatus.Ready && now.slowerThanPlays(durationMillis())) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.player_torrent_too_slow),
            SetteeType.Body,
            color = SetteeColors.QuietText,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}
