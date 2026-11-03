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
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.MediaKey
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

    data class OfRecentSearch(override val title: String) : OptionsSubject
}

/**
 * Opens the options panel on a subject; null where no [ItemOptionsHost] is above, so a card drawn
 * on its own (a test, a preview) is plainly clickable.
 */
val LocalOpenOptions = staticCompositionLocalOf<((OptionsSubject, LeaveScreen) -> Unit)?> { null }

/**
 * How a screen lets the panel leave it for the next one: whatever it does before a card's usual
 * Select leaves, then `go`. The panel sits above the screens, so it can't know what that is.
 */
typealias LeaveScreen = (go: () -> Unit) -> Unit

/** For a screen with nothing to do before leaving. */
val LeaveDirectly: LeaveScreen = { go -> go() }

/**
 * Holds the app's one options panel. Cards anywhere below open it through [LocalOpenOptions]
 * rather than each screen threading a callback and a panel of its own down to every card.
 * [navigate] goes where an option that leaves the screen says.
 */
@Composable
fun ItemOptionsHost(
    navigate: (OptionsDestination) -> Unit = {},
    viewModel: ItemOptionsViewModel = viewModel(),
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf<OpenPanel?>(null) }
    val scope = rememberCoroutineScope()
    val openOptions: (OptionsSubject, LeaveScreen) -> Unit = remember(viewModel, scope) {
        { subject, leave ->
            scope.launch {
                open = OpenPanel(subject, viewModel.optionsFor(subject), viewModel.destinationsFor(subject), leave)
            }
        }
    }
    CompositionLocalProvider(LocalOpenOptions provides openOptions, content = content)
    open?.let { panel ->
        OptionsPanel(
            title = panel.subject.title,
            options = panel.options,
            onChoose = { option ->
                open = null
                // Known before the panel opened: looking it up now would hand the highlight back
                // to the card first, where a quick Select would navigate as well.
                when (val destination = panel.destinations[option]) {
                    null -> viewModel.choose(panel.subject, option)
                    else -> panel.leave { navigate(destination) }
                }
            },
            onDismiss = { open = null },
        )
    }
}

private class OpenPanel(
    val subject: OptionsSubject,
    val options: List<ItemOption>,
    val destinations: Map<ItemOption, OptionsDestination>,
    val leave: LeaveScreen,
)

/** Where an option that leaves the screen goes; the app's navigation turns it into a route. */
sealed interface OptionsDestination {
    /** The player, at 0 rather than [key]'s resume point. */
    data class PlayFromStart(val key: MediaKey) : OptionsDestination

    data class MoviePage(val movieId: Long) : OptionsDestination

    data class SeriesPage(val seriesId: Long) : OptionsDestination
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
        is OptionsSubject.OfContinueWatching -> {
            val favourite = when (val title = catalog.resolve(subject.item)) {
                is Resolved.MovieCard -> isFavourite(title.movie)
                is Resolved.SeriesCard -> title.series?.let { isFavourite(it) }
                null -> null
            }
            OptionsFor.ContinueWatching(
                library.marks.statusOf(subject.item.key.stored),
                inCatalogue = favourite != null,
                favourite = favourite == true,
            )
        }
        is OptionsSubject.OfRecentSearch -> OptionsFor.RecentSearch
    }.options()

    /** Where the options that leave the screen go on [subject]; the rest stay on it. */
    suspend fun destinationsFor(subject: OptionsSubject): Map<ItemOption, OptionsDestination> = buildMap {
        playKeyOf(subject)?.let { put(ItemOption.PlayFromStart, OptionsDestination.PlayFromStart(it)) }
        catalog.pageOf(subject)?.let { put(ItemOption.ShowInfo, it) }
    }

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
                is OptionsSubject.OfRecentSearch -> if (option == ItemOption.RemoveRecentSearch) {
                    library.recentSearches.remove(subject.title)
                }
            }
        }
    }

    private suspend fun chooseFor(movie: Movie, option: ItemOption) {
        mark(movie.mediaKey, option)
        setFavourite(movie, option)
    }

    private suspend fun chooseFor(item: VodViewModel.ResumeItem, option: ItemOption) {
        val title = catalog.resolve(item) ?: return
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
}

/** The title behind a Continue Watching card, or null when it has left the catalogue. */
private suspend fun CatalogRepository.resolve(item: VodViewModel.ResumeItem): Resolved? = when (val card = item.card) {
    is ContinueWatchingCard.Movie -> movie(card.movieId)?.let { Resolved.MovieCard(it, card) }
    is ContinueWatchingCard.Series -> Resolved.SeriesCard(seriesOf(card.episode), card)
    null -> null
}

private sealed interface Resolved {
    val card: ContinueWatchingCard

    data class MovieCard(val movie: Movie, override val card: ContinueWatchingCard) : Resolved

    data class SeriesCard(val series: Series?, override val card: ContinueWatchingCard) : Resolved
}

private suspend fun CatalogRepository.pageOf(subject: OptionsSubject): OptionsDestination? = when (subject) {
    is OptionsSubject.OfMovie -> OptionsDestination.MoviePage(subject.movie.id)
    is OptionsSubject.OfSeries -> OptionsDestination.SeriesPage(subject.series.id)
    is OptionsSubject.OfContinueWatching -> when (val title = resolve(subject.item)) {
        is Resolved.MovieCard -> OptionsDestination.MoviePage(title.movie.id)
        is Resolved.SeriesCard -> title.series?.let { OptionsDestination.SeriesPage(it.id) }
        null -> null
    }
    else -> null
}

private fun playKeyOf(subject: OptionsSubject): MediaKey? = when (subject) {
    is OptionsSubject.OfMovie -> MediaKey.Movie(subject.movie.id)
    is OptionsSubject.OfEpisode -> MediaKey.Episode(subject.episode.id)
    is OptionsSubject.OfContinueWatching -> subject.item.key
    else -> null
}

private fun favouriteChoice(option: ItemOption): Boolean? = when (option) {
    ItemOption.AddFavourite -> true
    ItemOption.RemoveFavourite -> false
    else -> null
}
