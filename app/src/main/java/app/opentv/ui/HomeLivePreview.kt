/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.exoplayer.ExoPlayer
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.data.model.Channel
import app.opentv.data.repo.MediaKey
import app.opentv.player.PlayerController
import app.opentv.ui.channels.PreviewVideo
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.QuietLabel
import app.opentv.ui.design.ScreenFrameScope
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.vod.CardFocus
import app.opentv.ui.vod.takesFocus
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient

/**
 * Whether Home's Live Preview may play. Once a card is picked it stays stopped until Home has really
 * left, so a recording or a channel changing in that moment can't restart it beside the new stream.
 */
internal class LivePreviewGate {
    private var leaving = false

    fun leave() {
        leaving = true
    }

    fun allows(resumed: Boolean, recording: Boolean): Boolean {
        if (!resumed) leaving = false
        return resumed && !recording && !leaving
    }
}

/**
 * Home's muted player for the Live Preview: [player] is null while nothing should play. [httpClient] is
 * what its streams are read through, null when there's no player.
 */
internal class LivePreviewPlayer(
    val player: ExoPlayer?,
    val error: ViewerError?,
    val stop: () -> Unit,
    val httpClient: OkHttpClient?,
)

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
            graph.streamingHttpClient,
            resolve = graph.playbackSources::forWatching,
            PlayerController.Options(subtitlesEnabled = false, preview = true),
        ) to scope
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
    val gate = remember { LivePreviewGate() }
    LaunchedEffect(playing, channel?.id, resumed, recordings.isNotEmpty()) {
        if (playing == null || channel == null) return@LaunchedEffect
        if (!gate.allows(resumed, recording = recordings.isNotEmpty())) {
            playing.stop()
            return@LaunchedEffect
        }
        playing.play(PlayerController.Request(MediaKey.Live(channel.id)), debounce = false)
    }
    val state = playing?.state?.collectAsState()?.value
    return LivePreviewPlayer(
        player = playing?.player,
        error = (state as? PlayerController.State.Error)?.reason,
        httpClient = playing?.httpClient,
        stop = {
            gate.leave()
            playing?.stop()
        },
    )
}

/**
 * What Home's Live Preview tile shows: [live], its picture from [player] when one is playing it (none
 * when preview video is off or the channel has gone), or [error], the sentence saying why it can't
 * play.
 */
internal data class LiveTile(val live: LiveNow, val player: ExoPlayer?, val error: ViewerError?)

/** Select on the tile: its channel full screen, or the Live tab when the channel has gone. */
internal fun LiveTile.open(onWatch: (Channel) -> Unit, onOpenLive: () -> Unit) {
    live.preview.channel?.let(onWatch) ?: onOpenLive()
}

/**
 * Home's lead tile: the Live Preview's picture on the left, its channel and what's on beside it. One
 * selectable block, so a single Down leaves it for Continue Watching.
 */
@Composable
internal fun ScreenFrameScope.LivePreviewTile(tile: LiveTile, focus: CardFocus?, onClick: () -> Unit) {
    val preview = tile.live.preview
    // A row of one, so it highlights itself on coming back rather than through a row's scroll state.
    LaunchedEffect(focus) {
        if (focus == null) return@LaunchedEffect
        withFrameNanos {}
        focus.done(runCatching { focus.requester.requestFocus() }.isSuccess)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = LIFT_ROOM).height(LIVE_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The channel's name is drawn beside the picture but belongs to it, for TalkBack and for tests.
        val label = Modifier.semantics { text = AnnotatedString(preview.name) }
        HighlightSurface(onClick, HighlightStyle.Card, Modifier.takesFocus(0, focus).then(label)) {
            Box(
                Modifier.fillMaxHeight().aspectRatio(16f / 9f).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = preview.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(0.6f),
                )
                if (tile.player != null && tile.error == null) {
                    VideoArea(Modifier.fillMaxSize()) { PreviewVideo(tile.player, Modifier.fillMaxSize()) }
                }
                tile.error?.let { error ->
                    ErrorMessage(
                        error.sentence,
                        error.detail,
                        textAlign = TextAlign.Start,
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SetteeColors.Background.copy(alpha = 0.8f))
                            .padding(12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(preview.name, style = SetteeType.Section, modifier = Modifier.clearAndSetSemantics {}, maxLines = 1)
            if (preview.channel == null) DetailLine(stringResource(R.string.home_live_gone))
            tile.live.nowTitle?.let { DetailLine(stringResource(R.string.home_live_now, it)) }
            tile.live.nextTitle?.let { DetailLine(stringResource(R.string.home_live_next, it)) }
        }
    }
}

@Composable
private fun DetailLine(text: String) {
    Spacer(Modifier.height(4.dp))
    QuietLabel(text)
}

/** About 140 dp in all with the room for its lift, as docs/LOOK.md allows the Live Preview. */
private val LIVE_HEIGHT = 116.dp
private val LIFT_ROOM = 12.dp
