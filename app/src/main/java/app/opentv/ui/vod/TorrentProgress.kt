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
 */
@Composable
internal fun TorrentProgress(status: StateFlow<TorrentStatus>) {
    val now by status.collectAsState()
    val line = when (val torrent = now) {
        is TorrentStatus.Connecting -> if (torrent.peers == 0) {
            stringResource(R.string.player_torrent_connecting)
        } else {
            pluralStringResource(R.plurals.player_torrent_found, torrent.peers, torrent.peers)
        }
        is TorrentStatus.Ready ->
            pluralStringResource(R.plurals.player_torrent_fetching, torrent.peers, torrent.peers)
        else -> null
    } ?: return
    Spacer(Modifier.height(8.dp))
    Text(line, SetteeType.Body, color = SetteeColors.QuietText, modifier = Modifier.padding(horizontal = 24.dp))
}
