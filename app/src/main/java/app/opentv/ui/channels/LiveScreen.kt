/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.core.ReportedSource
import app.opentv.core.ServiceLocator
import app.opentv.core.findActivity
import app.opentv.data.model.Channel
import app.opentv.data.model.GuideProgramme
import app.opentv.data.repo.CategoryGroup
import app.opentv.data.repo.MediaKey
import app.opentv.player.PlayerController
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.LocalRailReturn
import app.opentv.ui.LocalShellCovered
import app.opentv.ui.ProviderFailure
import app.opentv.ui.ReportButton
import app.opentv.ui.dayLabel
import app.opentv.ui.design.ActionButton
import app.opentv.ui.design.CategoriesSidebar
import app.opentv.ui.design.CategoriesSidebarWidth
import app.opentv.ui.design.Message
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.ScreenFrameScope
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SidebarEntry
import app.opentv.ui.design.TextField
import app.opentv.ui.options.LocalConfirmLive
import app.opentv.ui.rememberErrorReport
import app.opentv.ui.rememberOpeningPlace
import app.opentv.ui.vod.rememberSidebarSlide
import kotlinx.coroutines.delay

/** Where the Highlight lands on the guide's [day], once paged there: [row]'s last cell with [atEnd], else its first. */
private data class DayLanding(val row: Any, val atEnd: Boolean, val day: Int)

/**
 * Live TV (docs/LOOK.md, Live TV): the preview band above the guide, with the Categories Sidebar
 * tucked away to the guide's left. Left from the guide's first column opens it, as on Movies and
 * Shows, and Right goes back to the channel and programme the viewer left.
 *
 * OK on Live TV in the rail lands on the channel last watched, or the first channel shown when
 * there's none, once the rows have arrived ([GuideGrid]'s return to the channel). Until then
 * nothing here can take the Highlight, so it stays in the rail rather than landing in the sidebar
 * and jumping when the rows arrive, which is how a press used to land on the wrong thing.
 */
@Composable
fun LiveScreen(
    isTelevision: Boolean,
    hasSources: Boolean,
    isSyncing: Boolean,
    /** Why a provider's last Catalogue Refresh couldn't download its channels, for the guide's empty state. */
    loadFailure: ProviderFailure? = null,
    onPlayChannel: (Channel) -> Unit,
    onAddSource: () -> Unit,
    onRefresh: () -> Unit,
    onPlayCatchup: (MediaKey.Catchup) -> Unit = {},
    takeFocus: Boolean = true,
    viewModel: ChannelsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val settings = remember { graph.settings }
    val previewEnabled by settings.guidePreviewVideo.collectAsState()

    // What reads the database is collected only while the screen is started, so none of it runs
    // under Settings, Search or a title's page, which keep the guide built (see programmeIndex, #327).
    val categories by viewModel.visibleCategoryGroups.collectAsStateWithLifecycle()
    val guideRows by viewModel.guideRows.collectAsStateWithLifecycle()
    val rows = guideRows.rows
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val favouritesOnly by viewModel.favouritesOnly.collectAsState()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    // Kept current while covered, as they read no database: Back from a guide that went back to
    // today while covered then draws today at once, and leaves out the stale rows' other day until
    // today's are built, as paging does. Their window moves first, which is what the rows land on.
    val windowStart by viewModel.windowStartMillis.collectAsState()
    val windowEnd by viewModel.windowEndMillis.collectAsState()
    val rowsBuilt by viewModel.rowsBuilt.collectAsState()
    val guideDayOffset by viewModel.guideDayOffset.collectAsState()
    // Tri-state: null = still checking the catalogue, true = channels on disk, false = confirmed
    // empty. Drives the choice between the loading message and a recoverable error below.
    val channelsPresent by viewModel.channelsPresent.collectAsStateWithLifecycle()

    // Where the Highlight is in the guide: what the band describes and the preview plays.
    var highlighted by remember { mutableStateOf<Pair<ChannelsViewModel.Row, GuideProgramme?>?>(null) }
    var nowMillis by remember { mutableLongStateOf(viewModel.now()) }

    val activeRecordings by graph.recordingRepository.observeActive()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val recordScope = rememberCoroutineScope()
    // The programme the viewer pressed OK on in the guide, whose details are open.
    var details by remember { mutableStateOf<Pair<ChannelsViewModel.Row, GuideProgramme>?>(null) }

    // Re-evaluate "now" once a minute so the band and the bold programme move with the time.
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = viewModel.now()
            viewModel.tick()
            delay(60_000)
        }
    }

    // Keep the band on a channel still shown when the list changes (a category picked, a channel
    // hidden): the same one if it's still there, otherwise the first.
    LaunchedEffect(rows) {
        val row = rows.firstOrNull { it.key == highlighted?.first?.key } ?: rows.firstOrNull()
        highlighted = row?.let { it to highlighted?.second?.takeIf { _ -> it.key == highlighted?.first?.key } }
    }

    // Live TV opens on today: a later day browsed last time isn't where anyone expects to come
    // back to, from the player or another tab, and getting back from it is a Left per day.
    DisposableEffect(viewModel) { onDispose { viewModel.guideToNow() } }
    // Settings, Search and title pages keep the guide built under them (KeptShell), so it isn't
    // disposed there: back to today as one covers it instead.
    val covered = LocalShellCovered.current
    LaunchedEffect(covered) { if (covered) viewModel.guideToNow() }

    // The guide opens on the channel last watched, so Back from the player lands where you were and
    // shows what's on it next. The guide is disposed under the player, so this is the way back in.
    val returnToChannelId = remember { settings.lastChannelId.takeIf { it != 0L } }

    // ---- Live preview player -----------------------------------------------------------------
    // One player, reused. It only ever decodes while the guide is the foreground screen, and is
    // stopped before any hand-off to full screen, so the box never runs two decoders at once.
    val previewController = rememberGuidePreviewController()

    val confirmLive = LocalConfirmLive.current
    fun requestLive(channel: Channel) {
        confirmLive {
            viewModel.zapFromGuide()
            previewController.stop()
            onPlayChannel(channel)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var screenResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> screenResumed = true
                Lifecycle.Event.ON_PAUSE -> {
                    screenResumed = false
                    previewController.stop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Hold the screen awake while the preview plays, or the box's screensaver fires mid-browse.
    DisposableEffect(previewEnabled, screenResumed) {
        val window = context.findActivity()?.window
        if (previewEnabled && screenResumed) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Tune the preview to the highlighted channel, debounced so holding a direction doesn't re-tune
    // every step. Silenced while a recording runs: on a single-connection line a muted preview is
    // still a second stream, which fights the recording and risks a provider ban.
    val recordingActive = activeRecordings.isNotEmpty()
    val previewRow = highlighted?.first
    val mayPreview = previewEnabled && screenResumed && !recordingActive
    LaunchedEffect(previewRow?.key, mayPreview) {
        if (!mayPreview || previewRow == null) {
            previewController.stop()
            return@LaunchedEffect
        }
        previewController.play(PlayerController.Request(MediaKey.Live(previewRow.primary.id)), debounce = true)
    }

    // The guide's day pages: where the Highlight lands on the new day, a row's first or last cell.
    // Cleared once it has, so the row coming back into view later doesn't take the Highlight again.
    var landOn by remember { mutableStateOf<DayLanding?>(null) }
    // A day's landing still to happen ignores further pages, or a second Right before it lands pages
    // again (#448). One that can no longer happen is dropped, or it would stop paging for good: a
    // category without that row, the day moved some other way first (Left back to now), or the
    // viewer moved to another row, which may scroll this one away before it lands.
    LaunchedEffect(guideRows.scope) { landOn = null }
    LaunchedEffect(guideDayOffset) { if (landOn?.day != guideDayOffset) landOn = null }
    // Pages [by] days from [row], unless a landing is still to happen; says whether it paged.
    fun pageDay(row: ChannelsViewModel.Row, by: Int): Boolean {
        if (landOn != null) return false
        viewModel.nudgeGuideDay(by)
        landOn = DayLanding(row.key, atEnd = by < 0, day = viewModel.guideDayOffset.value)
        return true
    }

    val current = when {
        favouritesOnly -> FAVOURITES
        else -> selectedCategory ?: ALL_CHANNELS
    }
    LiveBrowse(
        categories = categories,
        current = current,
        onPick = { key ->
            when (key) {
                FAVOURITES -> viewModel.selectFavourites()
                ALL_CHANNELS -> viewModel.selectCategory(null)
                else -> viewModel.selectCategory(key)
            }
        },
    ) { openSidebar ->
        Column(Modifier.fillMaxSize()) {
            GuideBand(
                row = highlighted?.first,
                programme = highlighted?.let { (row, programme) -> programme ?: row.liveAt(nowMillis) },
                previewPlayer = if (previewEnabled && !recordingActive) previewController.player else null,
                inFull = viewModel::programmeInFull,
            )
            Spacer(Modifier.height(GUIDE_GAP))
            // The highlighted cell leaves with the last row, and nothing in the message that replaces
            // the guide takes the Highlight on its own (#450). Whether the guide had it is read in the
            // composition that empties it, before the cell's going tells this it lost it.
            val guideFocus = remember { GuideFocus() }
            val emptiedFromGuide = remember(rows.isEmpty()) { rows.isEmpty() && guideFocus.has }
            Box(Modifier.weight(1f).onFocusChanged { guideFocus.has = it.hasFocus }) {
                // The guide's skeleton holds the Highlight while it shows; losing its cell as the rows
                // come sent the Highlight back to the rail, where the guide wouldn't take it from.
                val skeleton = rows.isEmpty() && !favouritesOnly && channelsPresent == true && !rowsBuilt
                val opening = rememberOpeningPlace(placeNow = skeleton)
                if (rows.isEmpty()) {
                    when {
                        favouritesOnly -> NoFavourites(openSidebar, takeFocus = emptiedFromGuide)
                        // Channels on the device and the guide still being built from them, as on
                        // every cold start: the guide's shape, its first row holding the Highlight,
                        // so the rows fill in rather than replace a message (#581).
                        skeleton -> OpeningGuideSkeleton(takeFocus, opening.modifierIf(true))
                        // Built, with channels on the device, and nothing here: every one is hidden.
                        channelsPresent == true -> NothingToShow(openSidebar, takeFocus = emptiedFromGuide)
                        // No channels on the device yet and a sync under way: the first, which takes
                        // a while on a big provider, and "No channels" during it reads as failure.
                        isSyncing -> Loading()
                        // The catalogue check is only a moment: nothing, rather than a flash of words.
                        channelsPresent == null -> Unit
                        // Nothing is syncing and the catalogue is confirmed empty: with a provider set
                        // up, the last load failed or returned nothing.
                        hasSources -> LoadFailed(loadFailure, onRetry = onRefresh, onEditProvider = onAddSource)
                        else -> NoChannels(onAddSource)
                    }
                } else {
                    val dayLabel = when (guideDayOffset) {
                        0 -> stringResource(R.string.guide_today)
                        1 -> stringResource(R.string.guide_tomorrow)
                        else -> dayLabel(windowStart)
                    }
                    GuideGrid(
                        rows = rows,
                        windowStartMillis = windowStart,
                        windowEndMillis = windowEnd,
                        rowsFor = guideRows.scope,
                        onToNow = { viewModel.guideToNow() },
                        onWatch = { row -> requestLive(row.primary) },
                        onHighlight = { row, programme ->
                            highlighted = row to programme
                            if (landOn?.row != row.key) landOn = null
                        },
                        onProgramme = { row, programme -> details = row to programme },
                        dayLabel = dayLabel,
                        dayOffset = guideDayOffset,
                        onNextDay = { row: ChannelsViewModel.Row -> pageDay(row, 1) }
                            .takeIf { guideDayOffset < ChannelsViewModel.GUIDE_MAX_DAYS },
                        onPrevDay = { row: ChannelsViewModel.Row -> pageDay(row, -1) }.takeIf { guideDayOffset > 0 },
                        returnToChannelId = returnToChannelId,
                        takeFocus = takeFocus || opening.held,
                        onReturnToRow = { highlighted = it to null },
                        landOnRow = landOn?.row,
                        landAtEnd = landOn?.atEnd == true,
                        onLanded = { landOn = null },
                        nowMillis = nowMillis,
                    )
                }
            }
        }
    }

    details?.let { (row, programme) ->
        ProgrammeDetails(
            targetRow = row,
            programme = programme,
            nowMillis = nowMillis,
            sources = sources,
            activeRecordings = activeRecordings,
            scope = recordScope,
            onWatch = ::requestLive,
            onPlayCatchup = onPlayCatchup,
            onDismiss = { details = null },
        )
    }
}

/**
 * Live TV's frame: [content] (the band and the guide), with the Categories Sidebar tucked away to
 * its left. It slides open and pushes the guide along, as on Movies and Shows ([VodBrowse]'s
 * slide): the rebuilt guide is drawn as a layer while it moves, so it's never measured mid-slide.
 *
 * Left out of the guide (only its first column has nothing further left) opens the sidebar on the
 * current category. Right from the sidebar goes back to the cell the viewer left ([LocalRailReturn]),
 * and Left from it to the rail; either closes it.
 */
@Composable
private fun LiveBrowse(
    categories: List<CategoryGroup>,
    current: String,
    onPick: (String) -> Unit,
    content: @Composable ScreenFrameScope.(openSidebar: () -> Unit) -> Unit,
) {
    val slide = rememberSidebarSlide()
    val sidebar = remember { FocusRequester() }
    val guide = remember { FocusRequester() }
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
                .testTag("live-content")
                .graphicsLayer {
                    val travel = CategoriesSidebarWidth.toPx()
                    translationX = slide.progress.value * travel - if (slide.contentLaidOutOpen) travel else 0f
                }
                .focusRequester(guide)
                // Touch can put the Highlight back in the guide without passing Right or Left.
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
            content { slide.open = true }
        }
        if (slide.sidebarShown) {
            LiveSidebar(
                categories,
                current,
                onPick,
                guide,
                onLeave = { slide.open = false },
                Modifier
                    .zIndex(1f)
                    .graphicsLayer { translationX = (slide.progress.value - 1f) * CategoriesSidebarWidth.toPx() }
                    .focusRequester(sidebar),
            )
        }
    }
}

/** Live TV's Categories Sidebar: Favourites and All channels, then every provider's [categories]. */
@Composable
private fun LiveSidebar(
    categories: List<CategoryGroup>,
    current: String,
    onPick: (String) -> Unit,
    guide: FocusRequester,
    onLeave: () -> Unit,
    modifier: Modifier,
) {
    val railReturn = LocalRailReturn.current
    val focusManager = LocalFocusManager.current
    val findHint = stringResource(R.string.categories_find)
    CategoriesSidebar(
        pinned = listOf(
            SidebarEntry(FAVOURITES, stringResource(R.string.guide_favourites)),
            SidebarEntry(ALL_CHANNELS, stringResource(R.string.guide_all_channels)),
        ),
        entries = remember(categories) { categories.map { SidebarEntry(it.key, it.label) } },
        current = current,
        onPick = { key ->
            // The cell the viewer left belongs to the list being replaced, so Right finds the new one.
            if (key != current) railReturn.target = null
            onPick(key)
        },
        findBox = { state, box -> TextField(state, findHint, FIND_MAX_LENGTH, box) },
        modifier = modifier
            .background(SetteeColors.Surface)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val moved = when (event.key) {
                    // With no cell to go back to (a category was just picked), the guide takes it.
                    Key.DirectionRight ->
                        runCatching { railReturn.target?.requestFocus() }.getOrDefault(false) == true ||
                            runCatching { guide.requestFocus() }.getOrDefault(false)
                    // Every row is at the sidebar's left edge, so Left leaves it, for the rail.
                    Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
                    else -> return@onKeyEvent false
                }
                if (moved) onLeave()
                moved
            },
    )
}

/**
 * A first sync's channels on their way. In its own words, not the main screen corner's: the corner
 * names the refresh's steps, and repeating them here only said the same thing twice.
 */
@Composable
private fun Loading() {
    Message(
        title = stringResource(R.string.guide_loading_channels),
        body = stringResource(R.string.guide_fetching_desc),
    )
}

/** Where the Highlight is, kept outside the snapshot so the guide's every move doesn't recompose. */
private class GuideFocus {
    var has = false
}

/**
 * Choose a category, which takes the Highlight when [takeFocus]: the message has just replaced a
 * guide that had it.
 */
@Composable
private fun ChooseCategory(openSidebar: () -> Unit, takeFocus: Boolean) {
    val button = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        // Once it's been laid out, which is when it can take the Highlight.
        withFrameNanos {}
        runCatching { button.requestFocus() }
    }
    ActionButton(stringResource(R.string.guide_choose_category), openSidebar, Modifier.focusRequester(button))
}

/** Favourites with none in them: how to add one, and a way to another category. */
@Composable
private fun NoFavourites(openSidebar: () -> Unit, takeFocus: Boolean) {
    Message(
        stringResource(R.string.guide_no_favourites_hint),
        title = stringResource(R.string.guide_no_favourites_title),
    ) { ChooseCategory(openSidebar, takeFocus) }
}

/**
 * A category, or All channels, whose every channel is hidden. Without a button there'd be nothing
 * here to take the Highlight, and so no way to Left and the sidebar.
 */
@Composable
private fun NothingToShow(openSidebar: () -> Unit, takeFocus: Boolean) {
    Message(
        stringResource(R.string.guide_empty_category_hint),
        title = stringResource(R.string.guide_empty_category_title),
    ) { ChooseCategory(openSidebar, takeFocus) }
}

/**
 * A provider is set up but there are no channels and nothing is syncing: the last load failed or
 * came back empty. Something to act on, rather than an endless loading message.
 */
@Composable
private fun LoadFailed(failure: ProviderFailure?, onRetry: () -> Unit, onEditProvider: () -> Unit) {
    val report = rememberErrorReport(failure?.error, ReportedSource.of(failure?.kind))
    // With no saved failure to say why, the generic sentence; it has no detail to show.
    Message(
        failure?.error?.sentence ?: stringResource(R.string.guide_load_failed_desc),
        title = failure?.let { stringResource(R.string.guide_load_failed_title_named, it.name) }
            ?: stringResource(R.string.guide_load_failed_title),
        detail = report?.detail ?: failure?.error?.detail.orEmpty(),
    ) {
        val tryAgain = remember { FocusRequester() }
        Row(horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            ActionButton(stringResource(R.string.common_try_again), onRetry, Modifier.focusRequester(tryAgain))
            ActionButton(stringResource(R.string.guide_edit_provider), onEditProvider)
            ReportButton(report, returnTo = tryAgain)
        }
    }
}

@Composable
private fun NoChannels(onAddSource: () -> Unit) {
    Message(
        stringResource(R.string.guide_add_provider_desc),
        title = stringResource(R.string.guide_no_channels_title),
    ) { ActionButton(stringResource(R.string.guide_add_provider_button), onAddSource) }
}

// Never a category's key: those are normalised names, or a provider's number and category id.
private const val FAVOURITES = "pinned:favourites"
private const val ALL_CHANNELS = "pinned:all"
private const val FIND_MAX_LENGTH = 40
private val GUIDE_GAP = 14.dp
private val BUTTON_GAP = 12.dp
