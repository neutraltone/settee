/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.data.repo.CoverStatus
import app.opentv.ui.coverStatusText
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import coil.compose.AsyncImage
import kotlin.time.Duration.Companion.seconds

/**
 * A details page's artwork across the whole screen, behind the hero: the backdrop, else the poster,
 * else nothing over the plain background, so the hero is never blank.
 *
 * Coil draws nothing for a URL that fails, and providers do ship dead backdrop URLs beside good
 * posters, so the fallback follows load errors rather than trusting a non-null URL.
 */
@Composable
internal fun BoxScope.HeroBackdrop(backdropUrl: String?, posterUrl: String?) {
    val candidates = remember(backdropUrl, posterUrl) { listOfNotNull(backdropUrl, posterUrl).distinct() }
    var index by remember(candidates) { mutableIntStateOf(0) }
    candidates.getOrNull(index)?.let { url ->
        AsyncImage(
            // Pops in: docs/LOOK.md wants a fade only once it's measured within the budget on a Stick,
            // and a full-screen crossfade hasn't been yet.
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
            onError = { if (index < candidates.lastIndex) index++ },
        )
    }
    // Text sits at the left and the episodes below, so the art fades into the background towards both.
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Scrim, 0.65f to Clear)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Clear, 0.85f to SetteeColors.Background)))
}

private val Scrim = SetteeColors.Background.copy(alpha = 0.92f)
private val Clear = SetteeColors.Background.copy(alpha = 0f)

/** A hero's meta line: the parts the provider gave, joined with middle dots. */
internal fun heroMeta(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")

/**
 * Where the Profile is with a title, worded as the covers word it, for the end of the hero's meta
 * line. An Up Next says nothing here: the main button already names the episode.
 */
@Composable
internal fun heroStatusText(status: CoverStatus?): String? = when (status) {
    null, is CoverStatus.UpNext -> null
    else -> coverStatusText(status).joinToString(" · ")
}

/** The first genre a provider lists ("Drama, Crime" is Drama), as the meta line has room for one. */
internal fun firstGenre(genre: String?): String? =
    genre?.split(',', '|', '/')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }

/**
 * Who is in a title and who made it, for the hero: the first [CREDITED_CAST] billed and the director,
 * or null when the provider gave neither.
 */
@Composable
internal fun heroCredits(cast: String?, director: String?): String? {
    val names = cast?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(CREDITED_CAST).orEmpty()
    return heroMeta(
        names.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.vod_credits_cast, it.joinToString(", ")) },
        director?.trim()?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.vod_credits_director, it) },
    ).takeIf { it.isNotEmpty() }
}

private const val CREDITED_CAST = 3

/** A film's running time, as "1h 52m" or "45m". */
@Composable
internal fun runtimeText(seconds: Int): String = seconds.seconds.toComponents { hours, minutes, _, _ ->
    if (hours > 0) {
        stringResource(R.string.vod_runtime_hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.vod_runtime_minutes, minutes)
    }
}

/** A hero's meta line, in Quiet text: see [heroMeta]. */
@Composable
internal fun HeroMetaLine(text: String) {
    Text(text, style = SetteeType.Body, color = SetteeColors.QuietText, maxLines = 1)
}

/**
 * The hero's Favourite toggle. [favourite] is null until the Profile's Favourites have loaded, and a
 * press does nothing till then, so it never acts on a guess.
 */
@Composable
internal fun FavouriteButton(favourite: Boolean?, onSetFavourite: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    PillButton(
        label = stringResource(if (favourite == true) R.string.common_remove_favourite else R.string.common_favourite),
        icon = if (favourite == true) Icons.Filled.Star else Icons.Outlined.StarOutline,
        onClick = { favourite?.let { onSetFavourite(!it) } },
        modifier = modifier,
    )
}

/** A details page while its title loads: one quiet line on the plain background. */
@Composable
internal fun LoadingDetail() {
    ScreenFrame {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.vod_detail_loading), style = SetteeType.Body, color = SetteeColors.QuietText)
        }
    }
}
