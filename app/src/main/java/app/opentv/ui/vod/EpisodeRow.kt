/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import app.opentv.ui.design.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Episode
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.WatchStatus
import app.opentv.data.repo.playerTitle
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.ProgressBar
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.minutesLeftText
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.openOptionsOn
import coil.compose.AsyncImage

/**
 * One episode on a show's page, full width: its thumbnail, title and "S2 E4", and on the right where
 * the Profile is with it. Plays on OK; Menu or a held OK opens its options.
 */
@Composable
internal fun EpisodeRow(
    ep: Episode,
    status: WatchStatus,
    seriesArt: List<String>,
    onPlay: (MediaKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    HighlightSurface(
        onClick = { onPlay(MediaKey.Episode(ep.id)) },
        style = HighlightStyle.Row,
        modifier = modifier.fillMaxWidth(),
        onOptions = openOptionsOn(OptionsSubject.OfEpisode(ep, ep.playerTitle)),
    ) { highlighted -> EpisodeRowBody(ep, status, seriesArt, highlighted) }
}

/**
 * The next season's first episode under a season's last, fading out downwards: it says Down carries
 * on into that season, without being somewhere the Highlight can stop. Hidden from the semantics
 * tree but for its test tag, as it repeats the row Down leads to.
 */
@Composable
internal fun EpisodePeek(ep: Episode, status: WatchStatus, seriesArt: List<String>) {
    Box(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { testTag = NEXT_SEASON_PEEK }
            // Offscreen, so the fade masks the row's own pixels rather than darkening what's behind.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(PeekFade, blendMode = BlendMode.DstIn)
            },
    ) {
        EpisodeRowBody(ep, status, seriesArt, highlighted = false)
    }
}

private val PeekFade = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent)

@Composable
private fun EpisodeRowBody(ep: Episode, status: WatchStatus, seriesArt: List<String>, highlighted: Boolean) {
    // Watched steps back by colour, never alpha (docs/LOOK.md, Colour).
    val main = when {
        highlighted -> SetteeColors.OnAccent
        status == WatchStatus.Watched -> SetteeColors.QuietText
        else -> SetteeColors.Text
    }
    val quiet = if (highlighted) SetteeColors.OnAccent else SetteeColors.QuietText
    Row(
        Modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EpisodeThumbnail(ep, status, seriesArt)
        Column(Modifier.weight(1f)) {
            Text(ep.title, style = SetteeType.RowTitle, color = main, maxLines = 1)
            Text(
                stringResource(R.string.home_cover_episode, ep.season, ep.episodeNumber),
                style = SetteeType.Body,
                color = quiet,
                maxLines = 1,
            )
        }
        EpisodeStatus(status, quiet)
    }
}

@Composable
private fun EpisodeStatus(status: WatchStatus, colour: Color) {
    when (status) {
        WatchStatus.Unwatched -> Unit
        WatchStatus.Watched -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = colour, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.watch_badge_watched), style = SetteeType.Body, color = colour, maxLines = 1)
        }
        is WatchStatus.InProgress -> Text(
            status.remainingMillis?.let { minutesLeftText(it) } ?: stringResource(R.string.watch_badge_in_progress),
            style = SetteeType.Body,
            color = colour,
            maxLines = 1,
        )
    }
}

/**
 * The provider's still for an episode, or else the show's artwork ([seriesArt], best first). Each
 * one that fails to load moves on to the next: Coil would otherwise leave a blank box, which reads as
 * a missing row, and providers do ship dead backdrop links beside good posters.
 */
@Composable
private fun EpisodeThumbnail(ep: Episode, status: WatchStatus, seriesArt: List<String>) {
    val candidates = remember(ep.stillUrl, seriesArt) { (listOfNotNull(ep.stillUrl) + seriesArt).distinct() }
    var index by remember(candidates) { mutableIntStateOf(0) }
    Box(Modifier.size(width = 128.dp, height = 72.dp).clip(SetteeShapes.Thumbnail).background(SetteeColors.Raised)) {
        AsyncImage(
            model = candidates.getOrNull(index),
            contentDescription = stringResource(R.string.vod_episode_thumbnail, ep.episodeNumber),
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
            onError = { if (index < candidates.lastIndex) index++ },
        )
        if (status is WatchStatus.InProgress) {
            ProgressBar(status.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
    }
}
