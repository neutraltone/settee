/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Category
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.displayTitle
import app.opentv.data.repo.GenreGroup
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.TitleCover
import app.opentv.ui.Backdrop
import app.opentv.ui.LocalRailReturn
import app.opentv.ui.ResumeItem
import app.opentv.ui.VodViewModel
import app.opentv.ui.browseKey
import app.opentv.ui.design.CategoriesSidebar
import app.opentv.ui.design.CategoriesSidebarWidth
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.ScreenFrameScope
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.SidebarEntry
import app.opentv.ui.design.Text
import app.opentv.ui.design.TextField
import app.opentv.ui.options.LeaveDirectly
import app.opentv.ui.options.LocalOpenOptions
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.openOptionsOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Movies (docs/LOOK.md, Shows and Movies): For you's rows of covers, or a category's whole grid. A
 * film opens its page rather than playing straight away.
 */
@Composable
fun MoviesScreen(
    onOpenMovie: (Movie) -> Unit,
    onResume: (MediaKey) -> Unit,
    hasSources: Boolean,
    isSyncing: Boolean,
    viewModel: VodViewModel = viewModel(),
) {
    val resume = viewModel.continueWatchingMovies.collectAsState().value
    val favourites = viewModel.favouriteMovies.collectAsState().value
    val recommended by viewModel.recommendedMovies.collectAsState()
    val recommending by viewModel.recommendingMovies.collectAsState()
    val recentlyAdded by viewModel.recentlyAddedMovies.collectAsState()
    val genreRows by viewModel.movieGenreRows.collectAsState()
    val vodLoading by viewModel.vodLoading.collectAsState()

    // Pull the movie library the first time this tab is opened, not at login; refresh the computed
    // rows (recommended, by genre) on open too: cheap, and covers a library already on disk.
    LaunchedEffect(Unit) {
        if (hasSources) viewModel.ensureVodLoaded()
        viewModel.loadHomeFeeds()
    }

    val hasForYou = !resume.isNullOrEmpty() ||
        !favourites.isNullOrEmpty() ||
        recommended.isNotEmpty() ||
        recentlyAdded.isNotEmpty() ||
        genreRows.isNotEmpty()
    val categories by viewModel.movieCategories.collectAsState()
    // null is For you, else a category's browseKey. Saved, since opening a title disposes this screen
    // and Back should land on the category it was opened from.
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(category) { viewModel.selectMovieCategory(category) }
    val covers by viewModel.movieGrid.collectAsState()
    val scope = rememberCoroutineScope()
    val openOptions = LocalOpenOptions.current
    VodBrowse(
        categories = categories,
        category = category,
        onPickCategory = { category = it },
        backdropFor = viewModel::movieBackdrop,
        grid = { onHighlight ->
            CoverGrid(
                covers,
                loading = stringResource(R.string.vod_loading_movies),
                empty = stringResource(R.string.vod_empty_category_movies),
                onHighlight = onHighlight,
                onOpen = { id -> scope.launch { viewModel.movie(id)?.let(onOpenMovie) } },
                onOptions = openOptions?.let { open ->
                    { id ->
                        scope.launch { viewModel.movie(id)?.let { open(OptionsSubject.OfMovie(it), LeaveDirectly) } }
                    }
                },
            )
        },
        hasForYou = hasForYou,
        forYouLoaded = resume != null && favourites != null,
        empty = when {
            vodLoading || isSyncing -> VodEmpty.Loading(stringResource(R.string.vod_loading_movies))
            hasSources -> VodEmpty.Hint(
                stringResource(R.string.vod_no_movies),
                stringResource(R.string.vod_no_movies_provider),
            )
            else -> VodEmpty.Hint(stringResource(R.string.vod_no_movies), stringResource(R.string.vod_no_movies_add))
        },
    ) { onHighlight ->
        MovieShelves(
            resume.orEmpty(),
            favourites.orEmpty(),
            recommended,
            recentlyAdded,
            genreRows,
            onResume,
            onOpenMovie,
            onHighlight,
            recommending,
        )
    }
}

/** Shows: the same as [MoviesScreen]. A show opens its page, where its episodes are fetched. */
@Composable
fun SeriesScreen(
    onOpenSeries: (Series) -> Unit,
    onResume: (MediaKey) -> Unit,
    hasSources: Boolean,
    isSyncing: Boolean,
    viewModel: VodViewModel = viewModel(),
) {
    val resume = viewModel.continueWatchingSeries.collectAsState().value
    val favourites = viewModel.favouriteSeries.collectAsState().value
    val recommended by viewModel.recommendedSeries.collectAsState()
    val recommending by viewModel.recommendingSeries.collectAsState()
    val recentlyAdded by viewModel.recentlyAddedSeries.collectAsState()
    val genreRows by viewModel.seriesGenreRows.collectAsState()
    val vodLoading by viewModel.vodLoading.collectAsState()

    LaunchedEffect(Unit) {
        if (hasSources) viewModel.ensureVodLoaded()
        viewModel.loadHomeFeeds()
    }

    val hasForYou = !resume.isNullOrEmpty() ||
        !favourites.isNullOrEmpty() ||
        recommended.isNotEmpty() ||
        recentlyAdded.isNotEmpty() ||
        genreRows.isNotEmpty()
    val categories by viewModel.seriesCategories.collectAsState()
    // null is For you, else a category's browseKey. Saved, since opening a title disposes this screen
    // and Back should land on the category it was opened from.
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(category) { viewModel.selectSeriesCategory(category) }
    val covers by viewModel.seriesGrid.collectAsState()
    val scope = rememberCoroutineScope()
    val openOptions = LocalOpenOptions.current
    VodBrowse(
        categories = categories,
        category = category,
        onPickCategory = { category = it },
        backdropFor = viewModel::seriesBackdrop,
        grid = { onHighlight ->
            CoverGrid(
                covers,
                loading = stringResource(R.string.vod_loading_shows),
                empty = stringResource(R.string.vod_empty_category_shows),
                onHighlight = onHighlight,
                onOpen = { id -> scope.launch { viewModel.series(id)?.let(onOpenSeries) } },
                onOptions = openOptions?.let { open ->
                    { id ->
                        scope.launch { viewModel.series(id)?.let { open(OptionsSubject.OfSeries(it), LeaveDirectly) } }
                    }
                },
            )
        },
        hasForYou = hasForYou,
        forYouLoaded = resume != null && favourites != null,
        empty = when {
            vodLoading || isSyncing -> VodEmpty.Loading(stringResource(R.string.vod_loading_shows))
            hasSources -> VodEmpty.Hint(
                stringResource(R.string.vod_no_shows),
                stringResource(R.string.vod_no_shows_provider),
            )
            else -> VodEmpty.Hint(stringResource(R.string.vod_no_shows), stringResource(R.string.vod_no_shows_add))
        },
    ) { onHighlight ->
        SeriesShelves(
            resume.orEmpty(),
            favourites.orEmpty(),
            recommended,
            recentlyAdded,
            genreRows,
            onResume,
            onOpenSeries,
            onHighlight,
            recommending,
        )
    }
}

/** Movies' For you rows, top to bottom. An empty row is left out rather than shown blank. */
@Composable
internal fun ScreenFrameScope.MovieShelves(
    resume: List<ResumeItem>,
    favourites: List<Movie>,
    recommended: List<Movie>,
    recentlyAdded: List<Movie>,
    genreRows: List<GenreGroup<Movie>>,
    onResume: (MediaKey) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onHighlight: (Long) -> Unit = {},
    recommending: Boolean = false,
) {
    val held = rememberHeldRow(recommended)
    val badges = LocalCoverBadges.current
    val rows = listOf(
        TitleRow("fav", stringResource(R.string.vod_favourites), favourites),
        TitleRow("rec", stringResource(R.string.vod_recommended), held.titles, held.modifier, recommending),
        TitleRow("recent", stringResource(R.string.vod_recently_added), recentlyAdded),
    ) + genreRows.map { TitleRow("g:${it.genre}", it.genre, it.items) }
    ForYou(
        resume,
        rows.filter { it.titles.isNotEmpty() || it.pending },
        onResume,
        cover = { it.cover(badges) },
        onOpen = onOpenMovie,
        onHighlight = onHighlight,
        options = { openOptionsOn(OptionsSubject.OfMovie(it)) },
    )
}

/** Shows' For you rows, top to bottom. An empty row is left out rather than shown blank. */
@Composable
internal fun ScreenFrameScope.SeriesShelves(
    resume: List<ResumeItem>,
    favourites: List<Series>,
    recommended: List<Series>,
    recentlyAdded: List<Series>,
    genreRows: List<GenreGroup<Series>>,
    onResume: (MediaKey) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onHighlight: (Long) -> Unit = {},
    recommending: Boolean = false,
) {
    val held = rememberHeldRow(recommended)
    val badges = LocalCoverBadges.current
    val rows = listOf(
        TitleRow("fav", stringResource(R.string.vod_favourites), favourites),
        TitleRow("rec", stringResource(R.string.vod_recommended), held.titles, held.modifier, recommending),
        TitleRow("recent", stringResource(R.string.vod_recently_added), recentlyAdded),
    ) + genreRows.map { TitleRow("g:${it.genre}", it.genre, it.items) }
    ForYou(
        resume,
        rows.filter { it.titles.isNotEmpty() || it.pending },
        onResume,
        cover = { it.cover(badges) },
        onOpen = onOpenSeries,
        onHighlight = onHighlight,
        options = { openOptionsOn(OptionsSubject.OfSeries(it)) },
    )
}

private fun Movie.cover(badges: CoverBadges) =
    TitleCover(id, name, displayTitle, posterUrl, badges.of(StreamKind.MOVIE, id, name))

private fun Series.cover(badges: CoverBadges) =
    TitleCover(id, name, displayTitle, posterUrl, badges.of(StreamKind.SERIES, id, name))

/**
 * One of For you's rows; [modifier] goes around it (a held row's, see [rememberHeldRow]). A [pending]
 * row with no titles yet shows as a placeholder, holding its place so the rows below don't move
 * when it lands.
 */
internal class TitleRow<T>(
    val key: String,
    val title: String,
    val titles: List<T>,
    val modifier: Modifier = Modifier,
    val pending: Boolean = false,
)

/** What Movies or Shows says when For you has nothing in it. */
internal sealed interface VodEmpty {
    class Loading(val message: String) : VodEmpty

    class Hint(val title: String, val body: String?) : VodEmpty
}

/**
 * Movies' or Shows' screen: For you's rows ([forYou]) once its top rows have loaded ([forYouLoaded]),
 * or [empty] when it has none, with the
 * Categories Sidebar tucked away to their left. It slides open and pushes the screen along, and
 * slides shut ([SidebarSlide]).
 *
 * Left from the leftmost cover opens the sidebar: it's the Highlight trying to leave the content
 * to the left, which otherwise takes it to the rail. Right from it goes back the way the rail's
 * Right does ([LocalRailReturn]), to the row the viewer left rather than whichever is nearest.
 *
 * It closes on the ways out of it: Right, Left to the rail, or the content taking the Highlight.
 * Not whenever the sidebar loses it: Compose counts the Highlight moving into Find a category's
 * text box, an Android view, as leaving the sidebar, which closed it under the viewer.
 */
@Composable
private fun VodBrowse(
    categories: List<Category>,
    category: String?,
    onPickCategory: (String?) -> Unit,
    backdropFor: (Long) -> Flow<Backdrop?>,
    grid: @Composable ScreenFrameScope.(onHighlight: (Long) -> Unit) -> Unit,
    hasForYou: Boolean,
    forYouLoaded: Boolean,
    empty: VodEmpty,
    forYou: @Composable ScreenFrameScope.(onHighlight: (Long) -> Unit) -> Unit,
) {
    val backdrop = rememberRestingBackdrop(backdropFor)
    val slide = rememberSidebarSlide()
    val sidebar = remember { FocusRequester() }
    val content = remember { FocusRequester() }
    LaunchedEffect(slide.open) {
        if (!slide.open) return@LaunchedEffect
        // Once it's been composed and laid out, which is when it can take the Highlight.
        withFrameNanos {}
        runCatching { sidebar.requestFocus() }
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        ScreenFrame(
            Modifier
                .padding(start = if (slide.contentLaidOutOpen) CategoriesSidebarWidth else 0.dp)
                .testTag("browse-content")
                .graphicsLayer {
                    val travel = CategoriesSidebarWidth.toPx()
                    translationX = slide.progress.value * travel - if (slide.contentLaidOutOpen) travel else 0f
                }
                .focusRequester(content)
                // Touch can put the Highlight back in the content without passing Right or Left.
                .onFocusChanged { if (it.hasFocus) slide.open = false }
                .focusProperties {
                    onExit = {
                        if (requestedFocusDirection == FocusDirection.Left) {
                            slide.open = true
                            cancelFocusChange()
                        }
                    }
                }
                .focusGroup(),
        ) {
            BackdropArt(backdrop.shown)
            Column(Modifier.fillMaxSize()) {
                BackdropText(backdrop.shown)
                Box(Modifier.weight(1f)) {
                    when {
                        category != null -> grid(backdrop.onHighlight)
                        // Nothing until the rows at the top have loaded: arriving after the
                        // Highlight, they pushed the row it was on off the screen.
                        !forYouLoaded -> Unit
                        hasForYou -> forYou(backdrop.onHighlight)
                        else -> EmptyBrowse(empty)
                    }
                }
            }
        }
        if (slide.sidebarShown) {
            BrowseSidebar(
                categories,
                category,
                onPickCategory,
                content,
                onLeave = { slide.open = false },
                Modifier
                    .zIndex(1f)
                    .graphicsLayer { translationX = (slide.progress.value - 1f) * CategoriesSidebarWidth.toPx() }
                    .focusRequester(sidebar),
            )
        }
    }
}

/**
 * The Categories Sidebar on Movies and Shows: For you, then the provider's [categories]. Right goes
 * back into [content], Left to the rail, and either calls [onLeave], which closes it.
 */
@Composable
private fun BrowseSidebar(
    categories: List<Category>,
    category: String?,
    onPickCategory: (String?) -> Unit,
    content: FocusRequester,
    onLeave: () -> Unit,
    modifier: Modifier,
) {
    val railReturn = LocalRailReturn.current
    val focusManager = LocalFocusManager.current
    val findHint = stringResource(R.string.categories_find)
    CategoriesSidebar(
        pinned = listOf(SidebarEntry(FOR_YOU, stringResource(R.string.vod_for_you))),
        entries = remember(categories) { categories.map { SidebarEntry(it.browseKey, it.name) } },
        current = category ?: FOR_YOU,
        onPick = { key ->
            // The row the viewer left belongs to what's being replaced, so Right finds the new content.
            if (key != (category ?: FOR_YOU)) railReturn.target = null
            onPickCategory(key.takeUnless { it == FOR_YOU })
        },
        findBox = { state, box -> TextField(state, findHint, FIND_MAX_LENGTH, box) },
        modifier = modifier
            .background(SetteeColors.Surface)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val moved = when (event.key) {
                    // With no row to go back to (a category was just picked), the content takes it.
                    Key.DirectionRight ->
                        runCatching { railReturn.target?.requestFocus() }.getOrDefault(false) == true ||
                            runCatching { content.requestFocus() }.getOrDefault(false)
                    // Every row is at the sidebar's left edge, so Left leaves it, for the rail.
                    Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
                    else -> return@onKeyEvent false
                }
                if (moved) onLeave()
                moved
            },
    )
}

private const val FOR_YOU = "for-you"
private const val FIND_MAX_LENGTH = 40

@Composable
internal fun EmptyBrowse(empty: VodEmpty) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (empty) {
                is VodEmpty.Loading -> {
                    Text(empty.message, SetteeType.Section)
                    Text(
                        stringResource(R.string.vod_large_provider_note),
                        SetteeType.Body,
                        color = SetteeColors.QuietText,
                    )
                }
                is VodEmpty.Hint -> {
                    Text(empty.title, SetteeType.Section)
                    empty.body?.let { Text(it, SetteeType.Body, color = SetteeColors.QuietText) }
                }
            }
        }
    }
}
