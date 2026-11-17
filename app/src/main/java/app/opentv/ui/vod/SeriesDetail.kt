/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Episode
import app.opentv.data.model.Series
import app.opentv.data.model.personNames
import app.opentv.data.parser.displayTitle
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.EpisodeStatuses
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.SeriesMainAction
import app.opentv.data.repo.TitleCover
import app.opentv.ui.design.Pill
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.openOptionsOn
import kotlinx.coroutines.delay

/**
 * A show's page once the show is known: the hero with its main button (Resume or Play the right
 * episode, from [mainAction]) and the Favourite toggle, the season pills, and the selected season's
 * episodes. Cast, when the show lists any, opens a panel of them, and a name picked there calls
 * [onOpenPerson]. [status] is where the Profile is with the show, for the meta line. [favourite] is null
 * until the Profile's Favourites have loaded, and the toggle does nothing till then. [versions] adds the
 * Quality pill for the whole show, as the film page has it. [suggestions] are its More like this: with
 * any, a More like this pill comes last in the pill row, and highlighting it shows them as a grid in the
 * episodes' place; OK on one calls [onOpenSeries]. Until [suggestionsKnown], a one-season show's episodes
 * wait, since a pill row may yet come above them.
 */
@Composable
internal fun SeriesDetailContent(
    series: Series,
    episodes: List<Episode>,
    statuses: EpisodeStatuses,
    mainAction: SeriesMainAction?,
    status: CoverStatus?,
    favourite: Boolean?,
    onPlayEpisode: (MediaKey) -> Unit,
    onSetFavourite: (Boolean) -> Unit,
    onOpenPerson: (String) -> Unit,
    versions: PageVersions = PageVersions(),
    onChooseVersion: (MediaKey) -> Unit = {},
    suggestions: List<TitleCover> = emptyList(),
    onOpenSeries: (Long) -> Unit = {},
    suggestionsKnown: Boolean = true,
) {
    val cast = remember(series.cast) { personNames(series.cast) }
    var castShown by remember(series.id) { mutableStateOf(false) }
    var choosingQuality by remember(series.id) { mutableStateOf(false) }
    var askingFor by remember(series.id) { mutableStateOf<MediaKey?>(null) }
    // Play asks first when only another language plays, and when nothing plays here an episode doesn't
    // either: the hero says why, and the player would only fail (#181).
    val play: (MediaKey) -> Unit = { key ->
        when {
            versions.cantPlay != null -> Unit
            versions.otherLanguage != null -> askingFor = key
            else -> onPlayEpisode(key)
        }
    }
    val seasons = remember(episodes) { seasonsOf(episodes) }
    val nav = remember(series.id) { ShowPageNav() }
    val hasLikeThis = suggestions.isNotEmpty()
    val shown = remember(seasons, mainAction, nav.pickedSeason, hasLikeThis, suggestionsKnown) {
        ShowSeasons(seasons, mainAction, nav.pickedSeason, hasLikeThis, suggestionsKnown)
    }
    val showingLikeThis = nav.showsLikeThis(shown)
    val pillRow = rememberLazyListState()
    val episodeList = rememberLazyListState()
    ShowPageFocusEffects(nav, shown, pillRow, episodeList, showingLikeThis)
    EpisodeListScrollEffects(nav, shown, episodeList)
    BackHandler(enabled = nav.backGoesToPills(shown)) { nav.pillTarget = PillTarget() }

    val backdrop: (@Composable BoxScope.() -> Unit)? =
        if (nav.heroAway) null else ({ HeroBackdrop(series.backdropUrl, series.posterUrl, HeroFade.AboveRows) })
    ScreenFrame(backdrop = backdrop) {
        Column(Modifier.fillMaxWidth()) {
            Text(series.displayTitle, style = SetteeType.Hero, maxLines = 2)
            if (!nav.heroAway) {
                ShowHero(series, nav, shown, status, favourite, versions.cantPlay, play, onSetFavourite) {
                    if (versions.showsPill) {
                        PillButton(
                            label = stringResource(R.string.vod_quality),
                            icon = Icons.Filled.HighQuality,
                            onClick = { choosingQuality = true },
                        )
                    }
                    if (cast.isNotEmpty()) {
                        PillButton(
                            label = stringResource(R.string.vod_cast),
                            icon = Icons.Filled.People,
                            onClick = { castShown = true },
                        )
                    }
                }
            }
            Spacer(Modifier.height(SetteeSpace.RowGap))
            if (shown.hasPills) {
                SeasonPills(nav, shown, pillRow, showingLikeThis)
                Spacer(Modifier.height(SetteeSpace.RowGap))
            }
            if (showingLikeThis) {
                LikeThisGrid(
                    suggestions,
                    firstCover = nav.focus.firstCover,
                    onFocusChanged = nav::gridFocusChanged,
                    onUpFromFirstRow = { nav.pillTarget = PillTarget() },
                    onOpenSeries = onOpenSeries,
                )
            } else {
                EpisodeList(series, nav, shown, statuses, episodeList, play, suggestions)
            }
        }
    }

    VersionPrompts(
        versions,
        choosingQuality = choosingQuality,
        askingLanguage = askingFor != null,
        onChooseVersion = onChooseVersion,
        onPlay = { askingFor?.let(onPlayEpisode) },
        onClose = {
            choosingQuality = false
            askingFor = null
        },
    )

    if (castShown) {
        CastPanel(
            title = stringResource(R.string.vod_cast),
            cast = cast,
            onPick = {
                castShown = false
                onOpenPerson(it)
            },
            onBack = { castShown = false },
        )
    }
}

/** The hero beneath the title: the meta line, the synopsis and the buttons, [moreButtons] last. */
@Composable
private fun ShowHero(
    series: Series,
    nav: ShowPageNav,
    shown: ShowSeasons,
    status: CoverStatus?,
    favourite: Boolean?,
    cantPlay: String?,
    onPlayEpisode: (MediaKey) -> Unit,
    onSetFavourite: (Boolean) -> Unit,
    moreButtons: @Composable () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    val seasonCount = shown.all.count { it.number > 0 }
    HeroMetaLine(
        heroMeta(
            firstGenre(series.genre),
            seasonCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.vod_season_count, it, it) },
            series.year?.toString(),
            heroStatusText(status),
        ),
    )
    series.plot?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, style = SetteeType.Synopsis, maxLines = 3, modifier = Modifier.fillMaxWidth(SYNOPSIS_WIDTH))
    }
    Spacer(Modifier.height(18.dp))
    Row(
        Modifier.onPreviewKeyEvent { e -> e.isPress(Key.DirectionDown) && nav.goDownFromButtons(shown) },
        horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
    ) {
        shown.mainAction?.let { action ->
            PillButton(
                label = mainActionLabel(action),
                icon = Icons.Filled.PlayArrow,
                onClick = { onPlayEpisode(MediaKey.Episode(action.episode.id)) },
                modifier = Modifier.focusRequester(nav.focus.main),
                enabled = cantPlay == null,
            )
        }
        FavouriteButton(
            favourite = favourite,
            onSetFavourite = onSetFavourite,
            modifier = Modifier
                .focusRequester(nav.focus.favourite)
                .onFocusChanged { nav.favouriteFocused = it.isFocused },
        )
        moreButtons()
    }
    cantPlay?.let {
        Text(
            cantPlayText(it),
            style = SetteeType.Body,
            color = SetteeColors.QuietText,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun mainActionLabel(action: SeriesMainAction): String = stringResource(
    when (action) {
        is SeriesMainAction.Resume -> R.string.vod_resume_episode
        is SeriesMainAction.Play -> R.string.vod_play_episode
    },
    action.episode.season,
    action.episode.episodeNumber,
)

/**
 * The row of season pills, and More like this last when the show has suggestions. Moving the Highlight
 * onto one selects it: a season's episodes, or the suggestions, show beneath.
 */
@Composable
private fun SeasonPills(nav: ShowPageNav, shown: ShowSeasons, pillRow: LazyListState, showingLikeThis: Boolean) {
    LazyRow(
        state = pillRow,
        horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
        modifier = Modifier.onPreviewKeyEvent { e -> e.isPress(Key.DirectionUp) && nav.backToHero() },
    ) {
        items(shown.all, key = { it.number }) { season ->
            val label = seasonLabel(season.number)
            val isSelected = !showingLikeThis && season.number == shown.selected?.number
            // OK opens the options too: arriving on a pill has already chosen its season.
            val openOptions = openOptionsOn(OptionsSubject.OfSeason(season.episodes, label))
            Pill(
                label = label,
                selected = isSelected,
                onClick = { openOptions?.invoke() },
                onOptions = openOptions,
                modifier = Modifier
                    .then(if (isSelected) Modifier.focusRequester(nav.focus.pill) else Modifier)
                    .onFocusChanged { if (it.isFocused) nav.pickSeason(season.number, shown) },
            )
        }
        if (shown.hasLikeThis) {
            item(key = LIKE_THIS_KEY) {
                // No options: there's nothing to mark watched or unwatched. Last, so having it moves nothing.
                Pill(
                    label = stringResource(R.string.vod_more_like_this),
                    selected = showingLikeThis,
                    onClick = {},
                    modifier = Modifier
                        .then(if (showingLikeThis) Modifier.focusRequester(nav.focus.pill) else Modifier)
                        .focusRequester(nav.focus.likeThisPill)
                        .onFocusChanged { if (it.isFocused) nav.likeThis = true }
                        // To the first cover, which the focus search wouldn't pick from a pill this far along.
                        .onPreviewKeyEvent { e -> e.isPress(Key.DirectionDown) && nav.goDownToGrid() },
                )
            }
        }
    }
}

/** The selected season's episodes, or a line saying they're on their way. */
@Composable
private fun EpisodeList(
    series: Series,
    nav: ShowPageNav,
    shown: ShowSeasons,
    statuses: EpisodeStatuses,
    episodeList: LazyListState,
    onPlayEpisode: (MediaKey) -> Unit,
    suggestions: List<TitleCover>,
) {
    val season = shown.selected
    // A one-season show's wait for its suggestions too, rather than be pushed down a row when More like
    // this's pill row arrives.
    if (season == null || shown.waiting) {
        Text(stringResource(R.string.vod_loading_episodes), style = SetteeType.Body, color = SetteeColors.QuietText)
        return
    }
    LazyColumn(
        state = episodeList,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .onFocusChanged { nav.listFocusChanged(it.hasFocus) }
            // The list itself takes the Highlight only while it changes season (see ShowPageNav.parking).
            .focusProperties { canFocus = nav.parking }
            .focusRequester(nav.focus.list)
            .focusable()
            .onPreviewKeyEvent { e ->
                when {
                    e.isPress(Key.DirectionUp) -> nav.goUpFromList(shown)
                    e.isPress(Key.DirectionDown) -> nav.goDownFromList(shown)
                    else -> false
                }
            },
    ) {
        items(season.episodes, key = { it.id }) { ep ->
            val targeted = ep.id == nav.episodeTarget?.episode?.id
            EpisodeRow(
                ep = ep,
                status = statuses[ep.id],
                seriesArt = listOfNotNull(series.backdropUrl, series.posterUrl),
                onPlay = onPlayEpisode,
                modifier = Modifier
                    .then(if (targeted) Modifier.focusRequester(nav.focus.episode) else Modifier)
                    .onFocusChanged { if (it.isFocused) nav.focusedEpisode = ep.id },
            )
        }
        // The next season's first episode, faded and out of the Highlight's reach, says Down carries on.
        shown.after(season)?.episodes?.firstOrNull()?.let { next ->
            item(key = PEEK_KEY) {
                EpisodePeek(next, statuses[next.id], listOfNotNull(series.backdropUrl, series.posterUrl))
            }
        }
        // Under the last season, More like this's title and the tops of its covers, as Down goes on there.
        if (shown.leadsToLikeThis(season)) {
            item(key = PEEK_KEY) { LikeThisPeek(suggestions) }
        }
    }
}

/**
 * Sends the Highlight where [nav] asks once that place is composed. An episode may be in a season
 * that isn't listed until the press asking for it selects it.
 */
@Composable
private fun ShowPageFocusEffects(
    nav: ShowPageNav,
    shown: ShowSeasons,
    pillRow: LazyListState,
    episodeList: LazyListState,
    showingLikeThis: Boolean,
) {
    val latest by rememberUpdatedState(shown)
    val onLikeThis by rememberUpdatedState(showingLikeThis)
    // Episodes are fetched on demand, so the main button can appear after the page does: the
    // highlight waits on the favourite toggle until then, and moves across only if it is still
    // there, never pulling a viewer back up from wherever they have gone since.
    val hasMainAction = shown.mainAction != null
    LaunchedEffect(hasMainAction) {
        delay(FIRST_HIGHLIGHT_DELAY_MILLIS)
        if (nav.highlightPlaced && !nav.favouriteFocused) return@LaunchedEffect
        runCatching { nav.focus.button(hasMainAction).requestFocus() }
        nav.highlightPlaced = true
    }
    LaunchedEffect(nav.episodeTarget) {
        val ep = nav.episodeTarget?.episode ?: return@LaunchedEffect
        // Back from More like this, the episodes it replaced are listed again first.
        withFrameNanos {}
        if (latest.selected?.number != ep.season) {
            // Listing another season takes away the row with the Highlight, and Compose would hand
            // it on to whatever is nearest: a pill, which would then select its own season. So the
            // list holds the Highlight itself while the seasons swap.
            nav.parking = true
            withFrameNanos {}
            runCatching { nav.focus.list.requestFocus() }
            nav.pickedSeason = ep.season
            withFrameNanos {}
            // Moving along the pills scrolls the row itself; this follows a season chosen from the list.
            pillRow.keepInView(latest.selectedIndex)
        }
        val index = latest.selected?.episodes?.indexOfFirst { it.id == ep.id } ?: -1
        if (index >= 0) episodeList.scrollToItem(index)
        withFrameNanos {}
        runCatching { nav.focus.episode.requestFocus() }
        nav.parking = false
    }
    LaunchedEffect(nav.likeThisTarget) {
        nav.likeThisTarget ?: return@LaunchedEffect
        // Highlighting More like this's pill is what shows the grid in the list's place, as moving along
        // the pills does; then on to its first cover, where Down from the pill goes.
        pillRow.keepInView(latest.all.size)
        withFrameNanos {}
        runCatching { nav.focus.likeThisPill.requestFocus() }
        withFrameNanos {}
        withFrameNanos {}
        runCatching { nav.focus.firstCover.requestFocus() }
    }
    LaunchedEffect(nav.pillTarget) {
        nav.pillTarget ?: return@LaunchedEffect
        pillRow.keepInView(if (onLikeThis) latest.all.size else latest.selectedIndex)
        withFrameNanos {}
        runCatching { nav.focus.pill.requestFocus() }
    }
    LaunchedEffect(nav.buttonTarget) {
        nav.buttonTarget ?: return@LaunchedEffect
        withFrameNanos {}
        runCatching { nav.focus.button(latest.mainAction != null).requestFocus() }
    }
}

/** Where the episode list scrolls by itself, apart from following the Highlight. */
@Composable
private fun EpisodeListScrollEffects(nav: ShowPageNav, shown: ShowSeasons, episodeList: LazyListState) {
    val latest by rememberUpdatedState(shown)
    // Opens with the main button's episode at the top of the list, so the Down that reaches it
    // doesn't also scroll the list under the viewer. Only until they've chosen a season themselves.
    val opening = shown.mainAction?.episode
    LaunchedEffect(opening?.id, shown.selected?.number) {
        if (nav.inList || nav.pickedSeason != null) return@LaunchedEffect
        val index = shown.selected?.episodes?.indexOfFirst { it.id == opening?.id } ?: -1
        if (index >= 0) episodeList.scrollToItem(index)
    }
    // A season picked on the pills lists from its first episode. Not one chosen from the list: that
    // scrolls to its own episode (ShowPageFocusEffects).
    LaunchedEffect(nav.listTopTarget) {
        nav.listTopTarget ?: return@LaunchedEffect
        episodeList.scrollToItem(0)
    }
    // On a season's last episode, bring what's faded beneath it into view: the next season's first
    // episode, or More like this. Asking for the last item scrolls only as far as the end of the list.
    LaunchedEffect(nav.focusedEpisode) {
        val season = latest.selected ?: return@LaunchedEffect
        val onLast = nav.focusedEpisode == season.episodes.lastOrNull()?.id
        if (onLast && latest.hasPeekAfter(season)) episodeList.animateScrollToItem(season.episodes.size)
    }
}

/** A show's seasons as the page lists them: which is selected, from the viewer's pick or else the main button's. */
@Immutable
private class ShowSeasons(
    val all: List<Season>,
    val mainAction: SeriesMainAction?,
    picked: Int?,
    /** Whether the show has suggestions, and so a More like this pill. */
    val hasLikeThis: Boolean,
    /** Whether the show's suggestions have been read, so whether it has the pill is known. */
    suggestionsKnown: Boolean = true,
) {
    val selected: Season? = all.firstOrNull { it.number == (picked ?: openingSeason(all, mainAction)) }
    val selectedIndex: Int = all.indexOf(selected)

    /** A pill row: for more than one season, or for More like this beside a show's only season. */
    val hasPills: Boolean = all.size > 1 || (hasLikeThis && all.isNotEmpty())

    /** A one-season show's episodes wait until it's known whether a pill row comes above them. */
    val waiting: Boolean = !suggestionsKnown && all.size == 1

    fun after(season: Season): Season? = all.getOrNull(all.indexOf(season) + 1)

    /** Whether Down from [season]'s last episode goes on to More like this: it's the last season. */
    fun leadsToLikeThis(season: Season) = hasLikeThis && after(season) == null

    /** Whether something faded under [season]'s last episode says where Down goes. */
    fun hasPeekAfter(season: Season) = after(season) != null || leadsToLikeThis(season)
}

/**
 * Where the Highlight goes on a show's page, and which season the viewer chose. Up and Down are
 * routed by hand rather than left to Compose's focus search, because the places they lead (an
 * episode in another season, the buttons of a hero that's away) aren't on screen until the press
 * brings them.
 */
@Stable
private class ShowPageNav {
    val focus = DetailFocus()

    /** The season the viewer chose; until then the page follows the main button's. */
    var pickedSeason by mutableStateOf<Int?>(null)

    var focusedEpisode by mutableStateOf<Long?>(null)
    var favouriteFocused by mutableStateOf(false)
    var highlightPlaced by mutableStateOf(false)
    var inList by mutableStateOf(false)
        private set
    var inGrid by mutableStateOf(false)
        private set

    /** Whether the viewer has moved onto More like this, so its grid shows in the episodes' place. */
    var likeThis by mutableStateOf(false)

    /** Going into the episodes moves the hero away in one step, and it stays away on the pills. */
    var heroAway by mutableStateOf(false)
        private set

    /** Whether the list is holding the Highlight itself while it changes season. */
    var parking by mutableStateOf(false)

    var episodeTarget by mutableStateOf<EpisodeTarget?>(null)
        private set
    var pillTarget by mutableStateOf<PillTarget?>(null)
    var likeThisTarget by mutableStateOf<LikeThisTarget?>(null)
        private set
    var buttonTarget by mutableStateOf<ButtonTarget?>(null)
        private set
    var listTopTarget by mutableStateOf<ListTopTarget?>(null)
        private set

    fun listFocusChanged(hasFocus: Boolean) {
        inList = hasFocus
        if (hasFocus) heroAway = true
    }

    /** Whether More like this's grid is shown in the episodes' place. */
    fun showsLikeThis(shown: ShowSeasons) = likeThis && shown.hasLikeThis

    /**
     * Back from anywhere in the episodes, or in More like this, goes to the pills first. Without pills,
     * Back leaves as it always has: there's nowhere nearer to go.
     */
    fun backGoesToPills(shown: ShowSeasons) = (inList && shown.hasPills) || (inGrid && showsLikeThis(shown))

    fun gridFocusChanged(hasFocus: Boolean) {
        inGrid = hasFocus
        if (hasFocus) heroAway = true
    }

    /**
     * The Highlight moved onto [season]'s pill, which selects it. A season listed this way starts at its
     * first episode, so Down from the pill reaches Episode 1 (#428): the seasons share one list, which
     * would otherwise keep the last season's scroll. Arriving back on the pill already selected (Back, Up
     * from the first episode) leaves the list where it was.
     */
    fun pickSeason(season: Int, shown: ShowSeasons) {
        if (season != shown.selected?.number || likeThis) listTopTarget = ListTopTarget()
        pickedSeason = season
        likeThis = false
    }

    /** Down from the buttons skips the pills, to the main button's episode, even from More like this. */
    fun goDownFromButtons(shown: ShowSeasons): Boolean {
        val ep = shown.mainAction?.episode ?: shown.selected?.episodes?.firstOrNull()
        ep?.let {
            likeThis = false
            episodeTarget = EpisodeTarget(it)
        }
        return ep != null
    }

    /** Down from More like this's pill reaches its first cover. */
    fun goDownToGrid(): Boolean = runCatching { focus.firstCover.requestFocus() }.isSuccess

    /**
     * Off a season's last episode onto the next season's first, or from the last season's onto More like
     * this's first cover. Without suggestions, the last season's last episode stays put.
     */
    fun goDownFromList(shown: ShowSeasons): Boolean {
        val season = shown.selected
        val onLast = season != null && focusedEpisode == season.episodes.lastOrNull()?.id
        if (season == null || !onLast) return false
        val next = shown.after(season)
        when {
            next != null -> episodeTarget = EpisodeTarget(next.episodes.first())
            shown.hasLikeThis -> likeThisTarget = LikeThisTarget()
        }
        return true
    }

    /** Up from a season's first episode reaches the pills, or the buttons when there are none. */
    fun goUpFromList(shown: ShowSeasons): Boolean {
        val onFirst = focusedEpisode == shown.selected?.episodes?.firstOrNull()?.id
        when {
            !onFirst -> Unit
            !shown.hasPills -> backToHero()
            else -> pillTarget = PillTarget()
        }
        return onFirst
    }

    fun backToHero(): Boolean {
        heroAway = false
        buttonTarget = ButtonTarget()
        return true
    }
}

/** Where the page can send the Highlight itself. */
private class DetailFocus {
    val main = FocusRequester()
    val favourite = FocusRequester()
    val episode = FocusRequester()
    val pill = FocusRequester()
    val list = FocusRequester()
    val firstCover = FocusRequester()

    /** More like this's pill, selected or not. */
    val likeThisPill = FocusRequester()

    /** The main button, or Favourite while there's no main button. */
    fun button(hasMainAction: Boolean) = if (hasMainAction) main else favourite
}

/** A request to highlight the hero's main button, or Favourite while there's no main button. */
private class ButtonTarget

/** A request to highlight the selected season's pill. */
private class PillTarget

/** A request to move from the episodes onto More like this's first cover. */
private class LikeThisTarget

/** A request to scroll the episode list back to its first episode. */
private class ListTopTarget

/** A request to highlight [episode]; a fresh one each time, so asking twice for the same episode still moves. */
private class EpisodeTarget(val episode: Episode)

private fun KeyEvent.isPress(key: Key) = type == KeyEventType.KeyDown && this.key == key

/** Test tag on the faded next-season episode under a season's last. */
internal const val NEXT_SEASON_PEEK = "next_season_peek"

private const val PEEK_KEY = "next-season-peek"
private const val LIKE_THIS_KEY = "more-like-this"

private const val FIRST_HIGHLIGHT_DELAY_MILLIS = 60L
private const val SYNOPSIS_WIDTH = 0.6f

/**
 * Scrolls the pill row so pill [index] is wholly in view, leaving the one before it showing where
 * there is one, so the row still says there's more that way.
 */
private suspend fun LazyListState.keepInView(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    val inView = item != null &&
        item.offset >= info.viewportStartOffset &&
        item.offset + item.size <= info.viewportEndOffset
    if (index >= 0 && !inView) scrollToItem((index - 1).coerceAtLeast(0))
}
