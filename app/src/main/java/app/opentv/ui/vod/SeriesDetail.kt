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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Episode
import app.opentv.data.model.Series
import app.opentv.data.parser.displayTitle
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.EpisodeStatuses
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.SeriesMainAction
import app.opentv.ui.VodViewModel
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
 * A show's page. Episodes are fetched when it opens rather than during catalogue sync: pulling every
 * episode of every series up front is what makes a first sync take twenty minutes on a large
 * provider, and most of it is never looked at.
 */
@Composable
fun SeriesDetailScreen(seriesId: Long, onPlayEpisode: (MediaKey) -> Unit, viewModel: VodViewModel = viewModel()) {
    var series by remember(seriesId) { mutableStateOf<Series?>(null) }

    LaunchedEffect(seriesId) {
        val loaded = viewModel.seriesDetail(seriesId)
        series = loaded
        if (loaded != null) viewModel.loadEpisodes(loaded)
    }

    val s = series
    val episodes by (s?.let { viewModel.episodes(it) } ?: viewModel.noEpisodes).collectAsState(initial = emptyList())
    val statuses by viewModel.episodeStatuses.collectAsState(initial = EpisodeStatuses())
    val mainAction by remember(episodes) { viewModel.seriesMainAction(episodes) }.collectAsState(initial = null)
    val status by remember(episodes) { viewModel.seriesStatus(episodes) }.collectAsState(initial = null)
    val favouriteSeries by viewModel.favouriteSeries.collectAsState()

    if (s == null) {
        LoadingDetail()
        return
    }

    SeriesDetailContent(
        series = s,
        episodes = episodes,
        statuses = statuses,
        mainAction = mainAction,
        status = status,
        favourite = favouriteSeries?.any { it.id == s.id },
        onPlayEpisode = onPlayEpisode,
        onSetFavourite = { viewModel.setSeriesFavourite(s, it) },
    )
}

/**
 * A show's page once the show is known: the hero with its main button (Resume or Play the right
 * episode, from [mainAction]) and the Favourite toggle, the season pills, and the selected season's
 * episodes. [status] is where the Profile is with the show, for the meta line. [favourite] is null
 * until the Profile's Favourites have loaded, and the toggle does nothing till then.
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
) {
    val seasons = remember(episodes) { seasonsOf(episodes) }
    val nav = remember(series.id) { ShowPageNav() }
    val shown = remember(seasons, mainAction, nav.pickedSeason) { ShowSeasons(seasons, mainAction, nav.pickedSeason) }
    val pillRow = rememberLazyListState()
    val episodeList = rememberLazyListState()
    ShowPageFocusEffects(nav, shown, pillRow, episodeList)
    EpisodeListScrollEffects(nav, shown, episodeList)
    // Back from anywhere in the list goes to the pills first. Without pills, Back leaves as it
    // always has: there's nowhere nearer to go.
    BackHandler(enabled = nav.inList && seasons.size > 1) { nav.pillTarget = PillTarget() }

    val backdrop: (@Composable BoxScope.() -> Unit)? =
        if (nav.heroAway) null else ({ HeroBackdrop(series.backdropUrl, series.posterUrl) })
    ScreenFrame(backdrop = backdrop) {
        Column(Modifier.fillMaxWidth()) {
            Text(series.displayTitle, style = SetteeType.Hero, maxLines = 2)
            if (!nav.heroAway) ShowHero(series, nav, shown, status, favourite, onPlayEpisode, onSetFavourite)
            Spacer(Modifier.height(SetteeSpace.RowGap))
            if (seasons.size > 1) {
                SeasonPills(nav, shown, pillRow)
                Spacer(Modifier.height(SetteeSpace.RowGap))
            }
            EpisodeList(series, nav, shown, statuses, episodeList, onPlayEpisode)
        }
    }
}

/** The hero beneath the title: the meta line, the synopsis and the buttons. */
@Composable
private fun ShowHero(
    series: Series,
    nav: ShowPageNav,
    shown: ShowSeasons,
    status: CoverStatus?,
    favourite: Boolean?,
    onPlayEpisode: (MediaKey) -> Unit,
    onSetFavourite: (Boolean) -> Unit,
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
            )
        }
        FavouriteButton(
            favourite = favourite,
            onSetFavourite = onSetFavourite,
            modifier = Modifier
                .focusRequester(nav.focus.favourite)
                .onFocusChanged { nav.favouriteFocused = it.isFocused },
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

/** The row of season pills. Moving the Highlight onto one selects its season. */
@Composable
private fun SeasonPills(nav: ShowPageNav, shown: ShowSeasons, pillRow: LazyListState) {
    LazyRow(
        state = pillRow,
        horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
        modifier = Modifier.onPreviewKeyEvent { e -> e.isPress(Key.DirectionUp) && nav.backToHero() },
    ) {
        items(shown.all, key = { it.number }) { season ->
            val label = seasonLabel(season.number)
            val isSelected = season.number == shown.selected?.number
            // OK opens the options too: arriving on a pill has already chosen its season.
            val openOptions = openOptionsOn(OptionsSubject.OfSeason(season.episodes, label))
            Pill(
                label = label,
                selected = isSelected,
                onClick = { openOptions?.invoke() },
                onOptions = openOptions,
                modifier = Modifier
                    .then(if (isSelected) Modifier.focusRequester(nav.focus.pill) else Modifier)
                    .onFocusChanged { if (it.isFocused) nav.pickedSeason = season.number },
            )
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
) {
    val season = shown.selected
    if (season == null) {
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
) {
    val latest by rememberUpdatedState(shown)
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
    LaunchedEffect(nav.pillTarget) {
        nav.pillTarget ?: return@LaunchedEffect
        pillRow.keepInView(latest.selectedIndex)
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
    // On a season's last episode, bring the faded next one into view beneath it. Asking for the
    // last item scrolls only as far as the end of the list.
    LaunchedEffect(nav.focusedEpisode) {
        val season = latest.selected ?: return@LaunchedEffect
        val onLast = nav.focusedEpisode == season.episodes.lastOrNull()?.id
        if (onLast && latest.after(season) != null) episodeList.animateScrollToItem(season.episodes.size)
    }
}

/** A show's seasons as the page lists them: which is selected, from the viewer's pick or else the main button's. */
@Immutable
private class ShowSeasons(val all: List<Season>, val mainAction: SeriesMainAction?, picked: Int?) {
    val selected: Season? = all.firstOrNull { it.number == (picked ?: openingSeason(all, mainAction)) }
    val selectedIndex: Int = all.indexOf(selected)

    fun after(season: Season): Season? = all.getOrNull(all.indexOf(season) + 1)
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

    /** Going into the episodes moves the hero away in one step, and it stays away on the pills. */
    var heroAway by mutableStateOf(false)
        private set

    /** Whether the list is holding the Highlight itself while it changes season. */
    var parking by mutableStateOf(false)

    var episodeTarget by mutableStateOf<EpisodeTarget?>(null)
        private set
    var pillTarget by mutableStateOf<PillTarget?>(null)
    var buttonTarget by mutableStateOf<ButtonTarget?>(null)
        private set

    fun listFocusChanged(hasFocus: Boolean) {
        inList = hasFocus
        if (hasFocus) heroAway = true
    }

    /** Down from the buttons skips the pills, to the main button's episode. */
    fun goDownFromButtons(shown: ShowSeasons): Boolean {
        val ep = shown.mainAction?.episode ?: shown.selected?.episodes?.firstOrNull()
        ep?.let { episodeTarget = EpisodeTarget(it) }
        return ep != null
    }

    /** Off a season's last episode onto the next season's first; the last season's last stays put. */
    fun goDownFromList(shown: ShowSeasons): Boolean {
        val season = shown.selected
        val onLast = season != null && focusedEpisode == season.episodes.lastOrNull()?.id
        if (season != null && onLast) shown.after(season)?.let { episodeTarget = EpisodeTarget(it.episodes.first()) }
        return onLast
    }

    /** Up from a season's first episode reaches the pills, or the buttons when there are none. */
    fun goUpFromList(shown: ShowSeasons): Boolean {
        val onFirst = focusedEpisode == shown.selected?.episodes?.firstOrNull()?.id
        when {
            !onFirst -> Unit
            shown.all.size < 2 -> backToHero()
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

    /** The main button, or Favourite while there's no main button. */
    fun button(hasMainAction: Boolean) = if (hasMainAction) main else favourite
}

/** A request to highlight the hero's main button, or Favourite while there's no main button. */
private class ButtonTarget

/** A request to highlight the selected season's pill. */
private class PillTarget

/** A request to highlight [episode]; a fresh one each time, so asking twice for the same episode still moves. */
private class EpisodeTarget(val episode: Episode)

private fun KeyEvent.isPress(key: Key) = type == KeyEventType.KeyDown && this.key == key

/** Test tag on the faded next-season episode under a season's last. */
internal const val NEXT_SEASON_PEEK = "next_season_peek"

private const val PEEK_KEY = "next-season-peek"

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
