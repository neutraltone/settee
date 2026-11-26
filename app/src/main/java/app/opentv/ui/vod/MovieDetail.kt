/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Movie
import app.opentv.data.model.StremioStream
import app.opentv.data.parser.displayTitle
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Opened
import app.opentv.ui.VodViewModel
import app.opentv.ui.design.ArtCard
import app.opentv.ui.design.ArtCardSize
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.ScreenFrameScope
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Shelf
import app.opentv.ui.design.SkeletonShelf
import app.opentv.ui.design.Text
import app.opentv.ui.design.rememberShelfMemory
import app.opentv.ui.design.shelfCard
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.openOptionsOn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * A film's page. Opening it enriches the row from the provider (backdrop, genre and so on) if those
 * fields are still bare, so a title that was plain in a grid fills out here. A film no longer stored
 * says so, with a Back that calls [onBack].
 */
@Composable
fun MovieDetailScreen(
    movieId: Long,
    onPlay: (Movie) -> Unit,
    onPlayAddonStream: (MediaKey.AddonStream) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onOpenPerson: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: VodViewModel = viewModel(),
) {
    var movie by remember(movieId) { mutableStateOf<Movie?>(null) }
    var gone by remember(movieId) { mutableStateOf(false) }
    var similar by remember(movieId) { mutableStateOf<List<Movie>?>(null) }
    var resumes by remember(movieId) { mutableStateOf(false) }
    var versions by remember(movieId) { mutableStateOf(PageVersions()) }
    val hasAddons by viewModel.hasAddons.collectAsState()
    val scope = rememberCoroutineScope()

    // The page waits for everything its buttons depend on, read side by side, so they're all there when it
    // shows rather than arriving one by one: Resume replacing Play, the Quality pill pushing the star along.
    LaunchedEffect(movieId) {
        val opened = viewModel.openMovie(movieId)
        if (opened !is Opened.Found) {
            gone = true
            return@LaunchedEffect
        }
        val loaded = opened.title
        coroutineScope {
            val resuming = async { viewModel.resumesPartway(loaded) }
            val versionsOf = async { viewModel.pageVersions(MediaKey.Movie(loaded.id)) }
            resumes = resuming.await()
            versions = versionsOf.await()
        }
        movie = loaded
    }
    // Apart from the film's own load, so the hero never waits on the row.
    LaunchedEffect(movie?.id) { movie?.let { similar = viewModel.moreLikeThis(it) } }

    if (gone) {
        GoneDetail(onBack)
        return
    }

    val m = movie
    // Waited for as well: the line it adds to the hero would push the buttons up.
    val status = remember(movieId) { viewModel.movieStatus(movieId).loaded() }.collectAsState(initial = null).value
    // Null until the Profile's Favourites have loaded, which the page waits for too, so the star doesn't change.
    val favourites = viewModel.favouriteMovies.collectAsState().value
    if (m == null || favourites == null || status == null) {
        LoadingDetail()
        return
    }

    MovieDetailContent(
        movie = m,
        resumes = resumes,
        status = status.value,
        favourite = viewModel.isFavourite(m, favourites),
        findAddonStreams = if (hasAddons) ({ viewModel.addonStreams(m) }) else null,
        similar = similar,
        onOpenMovie = onOpenMovie,
        onOpenPerson = onOpenPerson,
        onPlay = { onPlay(m) },
        onPlayAddonStream = { stream -> stream.keyFor(MediaKey.Movie(m.id))?.let(onPlayAddonStream) },
        onSetFavourite = { viewModel.setMovieFavourite(m, it) },
        versions = versions,
        onChooseVersion = { chosen ->
            scope.launch {
                viewModel.chooseVersion(chosen)
                versions = viewModel.pageVersions(MediaKey.Movie(m.id))
                resumes = viewModel.resumesPartway(m)
            }
        },
    )
}

/**
 * A film's page once the film is known: the hero, and beneath it [similar] films as More like this,
 * whose covers call [onOpenMovie]; [similar] is null while they're still being picked. Between them
 * are the film's people as rows of names ([PeopleRows]), which call [onOpenPerson]. The main button resumes
 * when [resumes], else plays. [findAddonStreams] is null unless add-ons are set up, and then adds an
 * Add-on sources button, last, whose picker plays the stream chosen. [favourite] is null until the
 * Profile's Favourites have loaded, and the toggle does nothing till then. When what Play stands for is a
 * film kept from an add-on ([PageVersions.playOpensPicker]), Play opens that picker instead, and is
 * off with no add-on set up. [versions] adds the Quality
 * pill when the film's Versions differ, whose choice goes to [onChooseVersion]; Play asks first when only
 * another language plays, and is off, saying why, when nothing does.
 */
@Composable
internal fun MovieDetailContent(
    movie: Movie,
    resumes: Boolean,
    status: CoverStatus?,
    favourite: Boolean?,
    findAddonStreams: (() -> Flow<AddonSearch>)?,
    similar: List<Movie>?,
    onOpenMovie: (Movie) -> Unit,
    onOpenPerson: (String) -> Unit,
    onPlay: () -> Unit,
    onPlayAddonStream: (StremioStream) -> Unit,
    onSetFavourite: (Boolean) -> Unit,
    versions: PageVersions = PageVersions(),
    onChooseVersion: (MediaKey) -> Unit = {},
) {
    val playFocus = remember { FocusRequester() }
    val spot = rememberReturnSpot(movie.id)
    val likeThisRow = rememberLazyListState()
    var choosingQuality by remember(movie.id) { mutableStateOf(false) }
    var askingLanguage by remember(movie.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberAddonPickerState(movie.id)
    // A film kept from an add-on plays only through the picker, so with no add-on left it can't play.
    val throughPicker = findAddonStreams.takeIf { versions.playOpensPicker }
    val plays = versions.plays && (!versions.playOpensPicker || throughPicker != null)

    FirstHighlight(movie, spot, similar, likeThisRow, playFocus)

    val page = rememberScrollState()
    ScreenFrame(backdrop = { HeroBackdrop(movie.backdropUrl, movie.posterUrl) }) {
        // The hero fills the screen but for the top of More like this, which shows there's more below.
        // It leaves that room even before the row has loaded, and when there turns out to be none, so
        // the hero never moves under the viewer.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val heroHeight = maxHeight - ROW_PEEK
            Column(Modifier.fillMaxSize().verticalScroll(page)) {
                Column(Modifier.fillMaxWidth().height(heroHeight), verticalArrangement = Arrangement.Bottom) {
                    MovieHero(movie, status)
                    Spacer(Modifier.height(18.dp))
                    MovieButtons(
                        resumes = resumes,
                        plays = plays,
                        showsPill = versions.showsPill,
                        favourite = favourite,
                        onSetFavourite = onSetFavourite,
                        onPlay = playAction(
                            viaPicker = throughPicker?.let { find -> { picker.ask(find) } },
                            asksLanguage = versions.otherLanguage != null,
                            askLanguage = { askingLanguage = true },
                            play = onPlay,
                        ),
                        onQuality = { choosingQuality = true },
                        // Play is already this film's way to the picker when it's kept from an add-on.
                        onAddonSources = findAddonStreams?.takeUnless { versions.playOpensPicker }
                            ?.let { find -> { picker.ask(find) } },
                        playFocus = playFocus,
                        spot = spot,
                        // Back on the buttons, the whole hero comes back, not just as much as shows them.
                        modifier = Modifier.onFocusChanged {
                            if (it.hasFocus) scope.launch { page.animateScrollTo(0) }
                        },
                    )
                }
                versions.cantPlay?.let {
                    Text(
                        cantPlayText(it),
                        style = SetteeType.Body,
                        color = SetteeColors.QuietText,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                PeopleRows(movie, onOpenPerson, spot, Modifier.padding(top = SetteeSpace.RowGap))
                if (similar == null) {
                    Spacer(Modifier.height(SetteeSpace.RowGap))
                    SkeletonShelf(stringResource(R.string.vod_more_like_this))
                } else if (similar.isNotEmpty()) {
                    Spacer(Modifier.height(SetteeSpace.RowGap))
                    MoreLikeThis(
                        similar,
                        onOpenMovie,
                        likeThisRow,
                        spot,
                        Modifier.onFocusChanged {
                            if (it.hasFocus) scope.launch { page.animateScrollTo(page.maxValue) }
                        },
                    )
                }
            }
        }
    }

    VersionPrompts(
        versions,
        choosingQuality = choosingQuality,
        askingLanguage = askingLanguage,
        onChooseVersion = onChooseVersion,
        onPlay = onPlay,
        onClose = {
            choosingQuality = false
            askingLanguage = false
        },
    )

    if (findAddonStreams != null) AddonStreamPicker(picker, findAddonStreams) { onPlayAddonStream(it.stream) }
}

/** What Play does: opens the add-on picker [viaPicker] if given, else asks about the language first, or plays. */
private fun playAction(
    viaPicker: (() -> Unit)?,
    asksLanguage: Boolean,
    askLanguage: () -> Unit,
    play: () -> Unit,
): () -> Unit = viaPicker ?: if (asksLanguage) askLanguage else play

/**
 * The hero's buttons: Play (Resume when [resumes]), the Quality pill when [showsPill], the star, and
 * Add-on sources when [onAddonSources] is given. With Play off, [playFocus] goes on the star instead,
 * so the page opens there.
 */
@Composable
private fun MovieButtons(
    resumes: Boolean,
    plays: Boolean,
    showsPill: Boolean,
    favourite: Boolean?,
    onSetFavourite: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onQuality: () -> Unit,
    onAddonSources: (() -> Unit)?,
    playFocus: FocusRequester,
    spot: ReturnSpot,
    modifier: Modifier = Modifier,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap), modifier = modifier) {
        PillButton(
            label = stringResource(if (resumes) R.string.vod_resume else R.string.vod_play),
            icon = Icons.Filled.PlayArrow,
            onClick = onPlay,
            modifier = (if (plays) Modifier.focusRequester(playFocus) else Modifier).returnSpot(spot, null),
            enabled = plays,
        )
        if (showsPill) {
            PillButton(
                label = stringResource(R.string.vod_quality),
                icon = Icons.Filled.HighQuality,
                onClick = onQuality,
                modifier = Modifier.returnSpot(spot, null),
            )
        }
        FavouriteButton(
            favourite,
            onSetFavourite,
            (if (plays) Modifier else Modifier.focusRequester(playFocus)).returnSpot(spot, null),
        )
        if (onAddonSources != null) {
            PillButton(
                label = stringResource(R.string.vod_addon_sources),
                icon = Icons.Filled.Extension,
                onClick = onAddonSources,
                modifier = Modifier.returnSpot(spot, null),
            )
        }
    }
}

/**
 * Puts the Highlight on Play when the page opens, or back where the viewer left from ([spot]) when they
 * come back to it: a person's name, or a cover in More like this ([likeThisRow]), once [similar] is read.
 */
@Composable
private fun FirstHighlight(
    movie: Movie,
    spot: ReturnSpot,
    similar: List<Movie>?,
    likeThisRow: LazyListState,
    playFocus: FocusRequester,
) {
    val similarNow by rememberUpdatedState(similar)
    LaunchedEffect(movie.id) {
        delay(60)
        val back = when (val start = spot.start) {
            is PageSpot.Film -> spot.restoreAmong({ similarNow }, { PageSpot.Film(it.id) }) { likeThisRow.bringOut(it) }
            // Its row scrolls to it as the page is built (PeopleRows), so it may take a frame or two.
            is PageSpot.Person -> start in peopleSpots(movie) && spot.restoreSoon()
            else -> false
        }
        spot.settled()
        if (!back) runCatching { playFocus.requestFocus() }
    }
}

/** The film's title, meta line, synopsis and credits. */
@Composable
private fun MovieHero(movie: Movie, status: CoverStatus?) {
    Text(movie.displayTitle, style = SetteeType.Hero, maxLines = 2)
    Spacer(Modifier.height(8.dp))
    HeroMetaLine(
        heroMeta(
            firstGenre(movie.genre),
            movie.year?.toString(),
            movie.durationSeconds?.takeIf { it > 0 }?.let { runtimeText(it) },
            heroStatusText(status),
        ),
    )
    movie.plot?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, style = SetteeType.Synopsis, maxLines = 4, modifier = Modifier.fillMaxWidth(SYNOPSIS_WIDTH))
    }
    heroCredits(movie.cast, movie.director)?.let {
        Spacer(Modifier.height(10.dp))
        Text(
            it,
            style = SetteeType.Body,
            color = SetteeColors.QuietText,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(SYNOPSIS_WIDTH),
        )
    }
}

/** Films like this one, as covers that open their own page. */
@Composable
private fun ScreenFrameScope.MoreLikeThis(
    similar: List<Movie>,
    onOpenMovie: (Movie) -> Unit,
    state: LazyListState,
    spot: ReturnSpot,
    modifier: Modifier,
) {
    val memory = rememberShelfMemory(remember(similar) { similar.map { "movie:${it.id}" } })
    Shelf(stringResource(R.string.vod_more_like_this), memory, modifier, state) {
        items(similar, key = { it.id }) { film ->
            ArtCard(
                title = film.displayTitle,
                artworkUrl = film.posterUrl,
                onClick = { onOpenMovie(film) },
                size = ArtCardSize.Portrait,
                modifier = Modifier.returnSpot(spot, PageSpot.Film(film.id)).shelfCard(memory, "movie:${film.id}"),
                onOptions = openOptionsOn(OptionsSubject.OfMovie(film)),
            )
        }
    }
}

/** The top of More like this showing under the hero: its title and the tops of its covers. */
private val ROW_PEEK = 84.dp

private const val SYNOPSIS_WIDTH = 0.6f
