/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.displayTitle
import app.opentv.ui.design.ProgressBar
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.options.LeaveDirectly
import app.opentv.ui.options.LeaveScreen
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.openOptionsOn
import app.opentv.ui.options.optionsClickable
import coil.compose.AsyncImage

// ---- Shared shelves ----------------------------------------------------------------------------

/** A titled horizontal shelf of [cards]; a caller that needs the scroll position owns [state]. */
@Composable
internal fun ShelfRow(title: String, state: LazyListState, cards: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title)
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = cards,
        )
    }
}

/** A titled horizontal shelf of movie poster cards. Shared by the home and the detail's "more like this". */
@Composable
internal fun MoviePosterRow(
    title: String,
    movies: List<Movie>,
    onOpenMovie: (Movie) -> Unit,
    focus: CardFocus? = null,
    onLeave: (Movie) -> LeaveScreen = { LeaveDirectly },
) {
    ShelfRow(title, rememberRowState(focus)) {
        itemsIndexed(movies, key = { _, movie -> movie.id }) { index, movie ->
            PosterCard(
                title = movie.displayTitle,
                posterUrl = movie.posterUrl,
                subtitle = movie.year?.toString(),
                rating = movie.rating,
                qualityBadge = rememberCoverBadge(StreamKind.MOVIE, movie.id, movie.name),
                onClick = { onOpenMovie(movie) },
                onOptions = openOptionsOn(OptionsSubject.OfMovie(movie), onLeave(movie)),
                modifier = Modifier.takesFocus(index, focus),
            )
        }
    }
}

/** A titled horizontal shelf of series poster cards. */
@Composable
internal fun SeriesPosterRow(
    title: String,
    series: List<Series>,
    onOpenSeries: (Series) -> Unit,
    focus: CardFocus? = null,
    onLeave: (Series) -> LeaveScreen = { LeaveDirectly },
) {
    ShelfRow(title, rememberRowState(focus)) {
        itemsIndexed(series, key = { _, item -> item.id }) { index, item ->
            PosterCard(
                title = item.displayTitle,
                posterUrl = item.posterUrl,
                subtitle = item.year?.toString(),
                rating = item.rating,
                qualityBadge = rememberCoverBadge(StreamKind.SERIES, item.id, item.name),
                onClick = { onOpenSeries(item) },
                onOptions = openOptionsOn(OptionsSubject.OfSeries(item), onLeave(item)),
                modifier = Modifier.takesFocus(index, focus),
            )
        }
    }
}

/** The titles a shelf draws for now, and the [modifier] around it that tells when it has the highlight. */
internal class HeldRow<T>(val titles: List<T>, val modifier: Modifier)

/**
 * Keeps a shelf as it is while the highlight is on it, then catches up with [titles] once it leaves.
 * A computed row can change just after a tab opens, and taking away the card under the highlight
 * would drop the remote's focus, so the viewer's next press lands somewhere unrelated.
 */
@Composable
internal fun <T> rememberHeldRow(titles: List<T>): HeldRow<T> {
    val latest by rememberUpdatedState(titles)
    val held = remember { HeldTitles(titles) }
    if (!held.highlighted && held.shown !== titles) held.shown = titles
    // One modifier for good, and the highlight kept out of snapshot state: read while composing, it
    // recomposed every For you shelf each time the highlight moved onto or off this one.
    val modifier = remember {
        Modifier.onFocusChanged {
            held.highlighted = it.hasFocus
            if (!it.hasFocus) held.shown = latest
        }
    }
    return HeldRow(held.shown, modifier)
}

/** What [rememberHeldRow] shows, and whether the highlight is on it, which only its focus callback reads. */
private class HeldTitles<T>(titles: List<T>) {
    var highlighted = false
    var shown by mutableStateOf(titles)
}

/**
 * Asks a shelf to bring its card at [index] into view and give it the highlight, as Home does when
 * the viewer comes back to the card they picked; [done] hears whether the card took it.
 */
internal class CardFocus(val index: Int, val done: (focused: Boolean) -> Unit) {
    val requester = FocusRequester()
}

/**
 * The shelf's scroll state, which moves to [focus]'s card and then highlights it. It waits a frame
 * after scrolling because the card is only composed once the row has been laid out there.
 */
@Composable
internal fun rememberRowState(focus: CardFocus?): LazyListState {
    val state = rememberLazyListState()
    LaunchedEffect(focus) {
        if (focus == null) return@LaunchedEffect
        state.scrollToItem(focus.index)
        withFrameNanos {}
        focus.done(runCatching { focus.requester.requestFocus() }.isSuccess)
    }
    return state
}

internal fun Modifier.takesFocus(index: Int, focus: CardFocus?): Modifier =
    if (focus?.index == index) focusRequester(focus.requester) else this

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title,
        style = SetteeType.RowTitle,
        color = SetteeColors.Text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}

/**
 * The focus cue every card on a shelf shares: the whole card scales up and its art gains a primary
 * border. Each card keeps its own sizes and corners, and reads [focused] for whatever else it changes.
 */
@Stable
internal class FocusCue(focused: MutableState<Boolean>, scale: State<Float>, color: State<Color>) {
    var focused by focused
    val scale by scale
    val color by color
}

/** Goes on the whole card. */
internal fun Modifier.focusCueScale(cue: FocusCue): Modifier = graphicsLayer {
    scaleX = cue.scale
    scaleY = cue.scale
}.onFocusChanged { cue.focused = it.isFocused }

/** Goes on the card's art. */
internal fun Modifier.focusCueBorder(cue: FocusCue, shape: Shape): Modifier =
    if (cue.focused) border(3.dp, cue.color, shape) else this

@Composable
internal fun rememberFocusCue(): FocusCue {
    val focused = remember { mutableStateOf(false) }
    val scale = animateFloatAsState(if (focused.value) 1.06f else 1f, label = "cardScale")
    val color = rememberUpdatedState(SetteeColors.Accent)
    return remember(scale) { FocusCue(focused, scale, color) }
}

/**
 * The reusable poster card: art, title and an optional year, with a rating chip, a quality badge and
 * a resume progress bar drawn over the art where the data is there. The focused card scales up and
 * gains a primary border — the app's established focus cue — and, being focusable, the lazy row
 * brings it into view on its own.
 */
@Composable
internal fun PosterCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    rating: Double? = null,
    qualityBadge: String? = null,
    progress: Float? = null,
    onOptions: (() -> Unit)? = null,
) {
    val cue = rememberFocusCue()
    Column(
        modifier
            .width(POSTER_WIDTH)
            .focusCueScale(cue)
            .clip(SetteeShapes.Card)
            .optionsClickable(onClick, onOptions)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(SetteeShapes.Thumbnail)
                .background(SetteeColors.Raised)
                .focusCueBorder(cue, SetteeShapes.Thumbnail),
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            rating?.takeIf { it > 0.0 }?.let {
                Badge(
                    text = "★ ${formatRating(it)}",
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
            qualityBadge?.let {
                Badge(
                    text = it,
                    highlight = true,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
            progress?.let {
                ProgressBar(it, Modifier.fillMaxWidth().align(Alignment.BottomCenter))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = SetteeType.Body,
            color = if (cue.focused) {
                SetteeColors.Text
            } else {
                SetteeColors.QuietText
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(
                it,
                style = SetteeType.Body,
                color = SetteeColors.QuietText,
                maxLines = 1,
            )
        }
    }
}

/** A small rounded chip drawn over poster art — a rating or a quality label. */
@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val bg = if (highlight) {
        SetteeColors.Accent
    } else {
        androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.66f)
    }
    val fg = if (highlight) SetteeColors.OnAccent else androidx.compose.ui.graphics.Color.White
    Text(
        text,
        style = SetteeType.CardTitle,
        color = fg,
        maxLines = 1,
        modifier = modifier
            .clip(SetteeShapes.Thumbnail)
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Poster shelf card width; the grid uses an adaptive min size close to this. */
private val POSTER_WIDTH = 140.dp

/** Rating to one decimal place, locale-independent (the "★" is drawn beside it). */
internal fun formatRating(rating: Double): String = String.format(java.util.Locale.US, "%.1f", rating)
