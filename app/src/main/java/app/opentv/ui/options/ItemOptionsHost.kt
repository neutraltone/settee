/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.options

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.core.requestIgnoreBatteryOptimizations
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.Programme
import app.opentv.data.model.Series
import app.opentv.data.model.StreamKind
import app.opentv.data.model.shownName
import app.opentv.data.parser.displayTitle
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.mediaKey
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.RecordingBackgroundDialog
import app.opentv.ui.RecordingBackgroundPrompt
import app.opentv.ui.VodViewModel
import app.opentv.ui.channels.ProgrammeDialog
import kotlinx.coroutines.Job
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
     * A channel with all its Qualities, which every action covers. [onWatch] is what Select does on
     * the surface it was opened from: only that surface knows what has to happen before playing,
     * such as Home stopping its preview, and the player switches channel rather than opening another.
     */
    data class OfChannel(val channel: LineupChannel, val onWatch: () -> Unit = {}) : OptionsSubject {
        override val title get() = channel.primary.shownName
    }

    data class OfRecentSearch(override val title: String) : OptionsSubject
}

/**
 * Opens the options panel on a subject; null where no [ItemOptionsHost] is above, so a card drawn
 * on its own (a test, a preview) is plainly clickable.
 */
val LocalOpenOptions = staticCompositionLocalOf<((OptionsSubject) -> Unit)?> { null }

/** To call whenever a recording is started or booked, wherever from. */
val LocalRecordingStarted = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Holds the app's one options panel. Cards anywhere below open it through [LocalOpenOptions]
 * rather than each screen threading a callback and a panel of its own down to every card.
 */
@Composable
fun ItemOptionsHost(viewModel: ItemOptionsViewModel = viewModel(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf<Pair<OptionsSubject, List<ItemOption>>?>(null) }
    var scheduling by remember { mutableStateOf<Scheduling?>(null) }
    var showBackgroundPrompt by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val openOptions: (OptionsSubject) -> Unit = remember(viewModel, scope) {
        { subject -> scope.launch { open = subject to viewModel.optionsFor(subject) } }
    }
    // First time the viewer records or schedules while the app isn't exempt from battery optimisation,
    // offer the exemption so the capture survives standby. Once per session; never blocks recording.
    val recordingStarted: () -> Unit = remember(context) {
        {
            if (RecordingBackgroundPrompt.shouldShow(context)) {
                RecordingBackgroundPrompt.markShown()
                showBackgroundPrompt = true
            }
        }
    }

    fun chooseForChannel(subject: OptionsSubject.OfChannel, option: ItemOption) {
        when (option) {
            ItemOption.ScheduleLater -> scope.launch {
                // The panel stays up, moving on to its next list.
                open = subject to viewModel.channels.laterProgrammes(subject.channel).map(ItemOption::LaterProgramme)
            }
            is ItemOption.LaterProgramme -> {
                open = null
                scope.launch {
                    scheduling =
                        Scheduling(viewModel.channels.rowWithProgrammes(subject.channel), option.programme, subject)
                }
            }
            ItemOption.Watch -> {
                open = null
                subject.onWatch()
            }
            ItemOption.OpenInExternalPlayer -> {
                open = null
                scope.launch { viewModel.channels.openInExternalPlayer(context, subject.channel) }
            }
            else -> {
                open = null
                viewModel.choose(subject, option)
                tellViewer(context, subject, option, recordingStarted)
            }
        }
    }

    val activeRecordings by viewModel.channels.activeRecordings.collectAsState(initial = emptyList())
    val sources by viewModel.channels.sources.collectAsState(initial = emptyList())
    ConfirmLiveHost(activeRecordings, viewModel.channels::stopRecording) {
        CompositionLocalProvider(
            LocalOpenOptions provides openOptions,
            LocalRecordingStarted provides recordingStarted,
        ) {
            content()
            scheduling?.let { (row, programme, subject) ->
                ProgrammeDialog(
                    targetRow = row,
                    programme = programme,
                    nowMillis = System.currentTimeMillis(),
                    sources = sources,
                    activeRecordings = activeRecordings,
                    scope = scope,
                    onWatch = { subject.onWatch() },
                    // Only a finished programme offers catch-up, and these are all still to come.
                    onPlayCatchup = {},
                    onDismiss = { scheduling = null },
                )
            }
        }
        open?.let { (subject, options) ->
            OptionsPanel(
                title = subject.title,
                options = options,
                onChoose = { option ->
                    if (subject is OptionsSubject.OfChannel) {
                        chooseForChannel(subject, option)
                    } else {
                        viewModel.choose(subject, option)
                        open = null
                    }
                },
                onDismiss = { open = null },
            )
        }
    }
    if (showBackgroundPrompt) {
        RecordingBackgroundDialog(
            onAllow = {
                showBackgroundPrompt = false
                context.requestIgnoreBatteryOptimizations()
            },
            onDismiss = { showBackgroundPrompt = false },
        )
    }
}

/** The programme picked from a channel's Schedule a later programme list. */
private data class Scheduling(
    val row: ChannelsViewModel.Row,
    val programme: Programme,
    val subject: OptionsSubject.OfChannel,
)

/** Says what a channel action did, as the guide always has, and where a hidden channel can be found. */
private fun tellViewer(
    context: Context,
    subject: OptionsSubject.OfChannel,
    option: ItemOption,
    recordingStarted: () -> Unit,
) {
    val name = subject.channel.primary.shownName
    val message = when (option) {
        ItemOption.HideChannel -> context.getString(R.string.options_channel_hidden, name)
        ItemOption.RecordNow -> context.getString(R.string.rec_recording_started_see_tab, name)
        ItemOption.StopRecording -> context.getString(R.string.rec_recording_stopped)
        is ItemOption.RecordWholeSeries ->
            context.getString(R.string.rec_series_recording_set, option.programme.title)
        else -> return
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    if (option == ItemOption.RecordNow || option is ItemOption.RecordWholeSeries) recordingStarted()
}

/** Works out what a subject's panel offers, and does what was chosen, for the active Profile. */
class ItemOptionsViewModel @JvmOverloads constructor(
    app: Application,
    private val graph: ServiceLocator.Graph = ServiceLocator.get(app),
) : AndroidViewModel(app) {
    private val library get() = graph.viewerLibrary
    private val catalog get() = graph.catalogRepository

    val channels = ChannelOptions(graph)

    suspend fun optionsFor(subject: OptionsSubject): List<ItemOption> = when (subject) {
        is OptionsSubject.OfMovie -> OptionsFor.Movie(
            library.marks.statusOf(subject.movie.mediaKey),
            isFavourite(subject.movie),
        )
        is OptionsSubject.OfSeries -> OptionsFor.Series(isFavourite(subject.series))
        is OptionsSubject.OfEpisode -> OptionsFor.Episode(library.marks.statusOf(subject.episode.mediaKey))
        is OptionsSubject.OfChannel -> {
            val ids = library.favourites.channelIds().first()
            channels.factsAbout(subject.channel, ids)
        }
        is OptionsSubject.OfContinueWatching -> OptionsFor.ContinueWatching(
            when (val title = resolve(subject.item)) {
                is Resolved.MovieCard -> isFavourite(title.movie)
                is Resolved.SeriesCard -> title.series?.let { isFavourite(it) }
                null -> null
            },
        )
        is OptionsSubject.OfRecentSearch -> OptionsFor.RecentSearch
    }.options()

    fun choose(subject: OptionsSubject, option: ItemOption): Job = viewModelScope.launch {
        when (subject) {
            is OptionsSubject.OfMovie -> chooseFor(subject.movie, option)
            is OptionsSubject.OfSeries -> setFavourite(subject.series, option)
            is OptionsSubject.OfEpisode -> mark(subject.episode.mediaKey, option)
            is OptionsSubject.OfChannel -> channels.choose(subject.channel, option)
            is OptionsSubject.OfContinueWatching -> chooseFor(subject.item, option)
            is OptionsSubject.OfRecentSearch -> if (option == ItemOption.RemoveRecentSearch) {
                library.recentSearches.remove(subject.title)
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
