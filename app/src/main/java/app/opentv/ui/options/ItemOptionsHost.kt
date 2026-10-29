/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.options

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.displayTitle
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.mediaKey
import app.opentv.ui.VodViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What an options panel is open on, and the heading it shows. */
sealed interface OptionsSubject {
    val title: String

    data class OfMovie(val movie: Movie) : OptionsSubject {
        override val title get() = movie.displayTitle
    }

    data class OfSeries(val series: Series) : OptionsSubject {
        override val title get() = series.displayTitle
    }

    /** [title] is how the series page lists the episode. */
    data class OfEpisode(val episode: Episode, override val title: String) : OptionsSubject

    data class OfContinueWatching(val item: VodViewModel.ResumeItem) : OptionsSubject {
        override val title get() = item.title
    }

    /**
     * A channel, with the quality [variants] it stands for in a list that folds them into one row:
     * like the row's star, the Favourite covers the whole logical channel.
     */
    data class OfChannel(val channel: Channel, val variants: List<Channel> = listOf(channel)) : OptionsSubject {
        override val title get() = channel.displayName
    }
}

/**
 * Opens the options panel on a subject; null where no [ItemOptionsHost] is above, so a card drawn
 * on its own (a test, a preview) is plainly clickable.
 */
val LocalOpenOptions = staticCompositionLocalOf<((OptionsSubject) -> Unit)?> { null }

/**
 * Holds the app's one options panel. Cards anywhere below open it through [LocalOpenOptions]
 * rather than each screen threading a callback and a panel of its own down to every card.
 */
@Composable
fun ItemOptionsHost(viewModel: ItemOptionsViewModel = viewModel(), content: @Composable () -> Unit) {
    var open by remember { mutableStateOf<Pair<OptionsSubject, List<ItemOption>>?>(null) }
    val scope = rememberCoroutineScope()
    val openOptions: (OptionsSubject) -> Unit = remember(viewModel, scope) {
        { subject -> scope.launch { open = subject to viewModel.optionsFor(subject) } }
    }
    CompositionLocalProvider(LocalOpenOptions provides openOptions, content = content)
    open?.let { (subject, options) ->
        OptionsPanel(
            title = subject.title,
            options = options,
            onChoose = {
                viewModel.choose(subject, it)
                open = null
            },
            onDismiss = { open = null },
        )
    }
}

/** Works out what a subject's panel offers, and does what was chosen, for the active Profile. */
class ItemOptionsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val library get() = graph.viewerLibrary
    private val catalog get() = graph.catalogRepository

    suspend fun optionsFor(subject: OptionsSubject): List<ItemOption> = when (subject) {
        is OptionsSubject.OfMovie -> OptionsFor.Movie(
            library.marks.statusOf(subject.movie.mediaKey),
            isFavourite(subject.movie),
        )
        is OptionsSubject.OfSeries -> OptionsFor.Series(isFavourite(subject.series))
        is OptionsSubject.OfEpisode -> OptionsFor.Episode(library.marks.statusOf(subject.episode.mediaKey))
        is OptionsSubject.OfChannel -> {
            val ids = library.favourites.channelIds().first()
            OptionsFor.Channel(subject.variants.any { it.id in ids })
        }
        is OptionsSubject.OfContinueWatching -> OptionsFor.ContinueWatching(
            when (val title = resolve(subject.item)) {
                is Resolved.MovieCard -> isFavourite(title.movie)
                is Resolved.SeriesCard -> title.series?.let { isFavourite(it) }
                null -> null
            },
        )
    }.options()

    fun choose(subject: OptionsSubject, option: ItemOption) {
        viewModelScope.launch {
            when (subject) {
                is OptionsSubject.OfMovie -> chooseFor(subject.movie, option)
                is OptionsSubject.OfSeries -> setFavourite(subject.series, option)
                is OptionsSubject.OfEpisode -> mark(subject.episode.mediaKey, option)
                is OptionsSubject.OfChannel -> favouriteChoice(option)?.let { favourite ->
                    subject.variants.forEach { library.favourites.set(it, favourite) }
                }
                is OptionsSubject.OfContinueWatching -> chooseFor(subject.item, option)
            }
        }
    }

    private suspend fun chooseFor(movie: Movie, option: ItemOption) {
        mark(movie.mediaKey, option)
        setFavourite(movie, option)
    }

    private suspend fun chooseFor(item: VodViewModel.ResumeItem, option: ItemOption) {
        val title = resolve(item) ?: return
        mark(item.key.stored, option)
        if (option == ItemOption.RemoveFromContinueWatching) library.marks.removeFromContinueWatching(title.card)
        when (title) {
            is Resolved.MovieCard -> setFavourite(title.movie, option)
            is Resolved.SeriesCard -> title.series?.let { setFavourite(it, option) }
        }
    }

    private suspend fun mark(mediaKey: String, option: ItemOption) = when (option) {
        ItemOption.MarkWatched -> library.marks.markWatched(mediaKey)
        ItemOption.MarkUnwatched -> library.marks.markUnwatched(mediaKey)
        else -> Unit
    }

    private suspend fun setFavourite(movie: Movie, option: ItemOption) = favouriteChoice(option)?.let {
        library.favourites.set(movie, it)
    }

    private suspend fun setFavourite(series: Series, option: ItemOption) = favouriteChoice(option)?.let {
        library.favourites.set(series, it)
    }

    private suspend fun isFavourite(movie: Movie) =
        library.favourites.contains(StreamKind.MOVIE, movie.sourceId, movie.streamId)

    private suspend fun isFavourite(series: Series) =
        library.favourites.contains(StreamKind.SERIES, series.sourceId, series.seriesId)

    /** The title behind a Continue Watching card, or null when it has left the catalogue. */
    private suspend fun resolve(item: VodViewModel.ResumeItem): Resolved? = when (val card = item.card) {
        is ContinueWatchingCard.Movie -> catalog.movie(card.movieId)?.let { Resolved.MovieCard(it, card) }
        is ContinueWatchingCard.Series -> Resolved.SeriesCard(catalog.seriesOf(card.episode), card)
        null -> null
    }

    private sealed interface Resolved {
        val card: ContinueWatchingCard

        data class MovieCard(val movie: Movie, override val card: ContinueWatchingCard) : Resolved

        data class SeriesCard(val series: Series?, override val card: ContinueWatchingCard) : Resolved
    }
}

private fun favouriteChoice(option: ItemOption): Boolean? = when (option) {
    ItemOption.AddFavourite -> true
    ItemOption.RemoveFavourite -> false
    else -> null
}
