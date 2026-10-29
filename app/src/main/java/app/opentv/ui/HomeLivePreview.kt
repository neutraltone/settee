/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.repo.MediaKey
import app.opentv.player.PlayerController
import app.opentv.ui.channels.PreviewVideo
import app.opentv.ui.vod.CardFocus
import app.opentv.ui.vod.takesFocus
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Home's muted player for the Live Preview: [player] is null while nothing should play. */
internal class LivePreviewPlayer(val player: ExoPlayer?, val error: String?, val stop: () -> Unit)

/**
 * Plays [channel] muted for the Live Preview, the way the guide's preview does: one player, only while
 * Home is the foreground screen, stopped while a recording runs (a second stream on a
 * single-connection line cuts the recording), and none at all with preview video off, which leaves
 * the tile on the channel's logo.
 */
@Composable
internal fun rememberLivePreviewPlayer(channel: Channel?): LivePreviewPlayer {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val previewVideo by graph.settings.guidePreviewVideo.collectAsState()
    val recordings by graph.recordingRepository.observeActive().collectAsState(initial = emptyList())
    val wanted = channel != null && previewVideo
    // Built only while there's something to play: most visits to Home have no Live Preview.
    val controller = remember(wanted) {
        if (!wanted) return@remember null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        PlayerController(
            context,
            scope,
            graph.httpClient,
            resolve = graph.playbackSources::forWatching,
            subtitlesEnabled = false,
            preview = true,
        )
            .also { it.player.volume = 0f } to scope
    }
    DisposableEffect(controller) {
        onDispose {
            controller?.first?.release()
            controller?.second?.cancel()
        }
    }
    val playing = controller?.first
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState().value
        .isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(playing, channel?.id, resumed, recordings.isNotEmpty()) {
        if (playing == null || channel == null) return@LaunchedEffect
        if (!resumed || recordings.isNotEmpty()) {
            playing.stop()
            return@LaunchedEffect
        }
        playing.play(PlayerController.Request(MediaKey.Live(channel.id)), debounce = false)
    }
    val state = playing?.state?.collectAsState()?.value
    return LivePreviewPlayer(
        player = playing?.player,
        error = (state as? PlayerController.State.Error)?.message,
        stop = { playing?.stop() },
    )
}

/**
 * What Home's Live Preview tile shows: [live], its picture from [player] when one is playing it (none
 * when preview video is off or the channel has gone), or [error], the sentence saying why it can't
 * play.
 */
internal data class LiveTile(val live: LiveNow, val player: ExoPlayer?, val error: String?)

/** Select on the tile: its channel full screen, or the Live tab when the channel has gone. */
internal fun LiveTile.open(onWatch: (Channel) -> Unit, onOpenLive: () -> Unit) {
    live.preview.channel?.let(onWatch) ?: onOpenLive()
}

/** Home's lead tile: the Live Preview's picture on the left, its channel and what's on beside it. */
@Composable
internal fun LivePreviewTile(tile: LiveTile, focus: CardFocus?, onClick: () -> Unit) {
    val preview = tile.live.preview
    var focused by remember { mutableStateOf(false) }
    // A row of one, so it highlights itself on coming back rather than through a row's scroll state.
    LaunchedEffect(focus) {
        if (focus == null) return@LaunchedEffect
        withFrameNanos {}
        focus.done(runCatching { focus.requester.requestFocus() }.isSuccess)
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(LIVE_HEIGHT)
            .takesFocus(0, focus)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(Color.Black)
                .then(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = preview.logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(0.6f),
            )
            if (tile.player != null && tile.error == null) PreviewVideo(tile.player, Modifier.fillMaxSize())
            tile.error?.let { error ->
                Text(
                    error,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(16.dp),
                )
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                preview.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (preview.channel == null) {
                Text(
                    stringResource(R.string.home_live_gone),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            tile.live.nowTitle?.let { DetailLine(stringResource(R.string.home_live_now, it)) }
            tile.live.nextTitle?.let { DetailLine(stringResource(R.string.home_live_next, it)) }
        }
    }
}

@Composable
private fun DetailLine(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private val LIVE_HEIGHT = 220.dp
