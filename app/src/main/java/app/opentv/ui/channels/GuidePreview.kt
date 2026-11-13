/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.GuideProgramme
import app.opentv.data.model.Programme
import app.opentv.data.model.shownName
import app.opentv.player.PlayerController
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.design.ScreenFrameScope
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The guide's preview player, released with the screen. Its sound follows the guide's sound setting. */
@Composable
internal fun rememberGuidePreviewController(): PlayerController {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val controller = remember {
        PlayerController(
            context,
            scope,
            graph.streamingHttpClient,
            resolve = graph.playbackSources::forWatching,
            PlayerController.Options(subtitlesEnabled = false, preview = true),
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            controller.release()
            scope.cancel()
        }
    }
    val sound by graph.settings.guidePreviewSound.collectAsState()
    LaunchedEffect(sound) { controller.audioEnabled = sound }
    return controller
}

/**
 * The preview band above the guide: the [row]'s channel playing muted, when [previewPlayer] is
 * given, beside [programme]'s title, time and synopsis (what's on now while the Highlight is on the
 * channel itself). Nothing in it can take the Highlight: OK on a channel watches it, and OK on a
 * programme opens its details, so the band only says where the Highlight is.
 *
 * The earlier version of the guide preview that locked up cheap boxes ran a second decoder behind
 * the full-screen one. Here there is only ever this one muted, debounced player, and [LiveScreen]
 * stops it before handing off to full screen and whenever the screen is backgrounded. The logo
 * stays behind the video as its shutter, so a buffering or failed stream still shows something.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun ScreenFrameScope.GuideBand(
    row: ChannelsViewModel.Row?,
    programme: GuideProgramme?,
    previewPlayer: ExoPlayer?,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().height(BAND_HEIGHT)) {
        // Nothing highlighted (no Favourites yet, say): an empty band, not an empty black box.
        if (row == null) return@Row
        VideoArea(Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(SetteeShapes.Cell).background(Color.Black)) {
            AsyncImage(
                model = row.primary.logoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(LOGO_FRACTION).align(Alignment.Center),
            )
            if (previewPlayer != null) PreviewVideo(previewPlayer, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(BAND_GAP))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (programme == null) {
                Text(row.primary.shownName, SetteeType.Section, maxLines = 1)
                Text(stringResource(R.string.guide_no_info_channel), SetteeType.Body, color = SetteeColors.QuietText)
                return@Column
            }
            Text(programme.title, SetteeType.Section, maxLines = 2)
            Text(
                "${formatTime(
                    programme.startUtcMillis,
                )}–${formatTime(programme.endUtcMillis)}  ·  ${row.primary.shownName}",
                SetteeType.Body,
                color = SetteeColors.QuietText,
                maxLines = 1,
            )
            rememberInFull(programme)?.description?.takeIf { it.isNotBlank() }?.let { synopsis ->
                Spacer(Modifier.height(BAND_LINE_GAP))
                Text(synopsis, SetteeType.Synopsis, color = SetteeColors.Text, maxLines = SYNOPSIS_LINES)
            }
        }
    }
}

/**
 * [programme] read in full from the guide, for its synopsis. Null until it loads, and null if a guide
 * refresh has replaced it since the grid was built: keyed on the id, so the Highlight moving on never
 * leaves the last programme's text showing under the new one's title.
 */
@Composable
internal fun rememberInFull(programme: GuideProgramme?): Programme? {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val id = programme?.id
    return produceState<Programme?>(null, id) {
        value = id?.let { graph.epgRepository.programme(it) }
    }.value
}

private val BAND_HEIGHT = 150.dp
private val BAND_GAP = 18.dp
private val BAND_LINE_GAP = 6.dp
private const val SYNOPSIS_LINES = 3
private const val LOGO_FRACTION = 0.6f

/**
 * [player]'s picture with no controls, letterboxed. Its shutter is transparent, so whatever is drawn
 * behind it (a logo) shows until the first frame and whenever the stream drops. Home's Live Preview
 * draws the same view, so a channel looks the same in both.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PreviewVideo(player: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { it.player = player },
    )
}
