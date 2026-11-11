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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.core.StatusBus
import app.opentv.core.findActivity
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.repo.CategoryGroup
import app.opentv.data.repo.MediaKey
import app.opentv.player.PlayerController
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.LocalRailReturn
import app.opentv.ui.design.ActionButton
import app.opentv.ui.design.CategoriesSidebar
import app.opentv.ui.design.CategoriesSidebarWidth
import app.opentv.ui.design.Message
import app.opentv.ui.design.ProgressBar
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.ScreenFrameScope
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SidebarEntry
import app.opentv.ui.options.LocalConfirmLive
import app.opentv.ui.vod.rememberSidebarSlide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

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

    val categories by viewModel.visibleCategoryGroups.collectAsState()
    val rows by viewModel.rows.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val favouritesOnly by viewModel.favouritesOnly.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val windowStart by viewModel.windowStartMillis.collectAsState()
    val windowEnd by viewModel.windowEndMillis.collectAsState()
    val rowsBuilt by viewModel.rowsBuilt.collectAsState()
    val guideDayOffset by viewModel.guideDayOffset.collectAsState()
    // Tri-state: null = still checking the catalogue, true = channels on disk, false = confirmed
    // empty. Drives the choice between the loading message and a recoverable error below.
    val channelsPresent by viewModel.channelsPresent.collectAsState()

    // Where the Highlight is in the guide: what the band describes and the preview plays.
    var highlighted by remember { mutableStateOf<Pair<ChannelsViewModel.Row, Programme?>?>(null) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val activeRecordings by graph.recordingRepository.observeActive().collectAsState(initial = emptyList())
    val recordScope = rememberCoroutineScope()
    // The programme the viewer pressed OK on in the guide, whose details are open.
    var details by remember { mutableStateOf<Pair<ChannelsViewModel.Row, Programme>?>(null) }

    // Re-evaluate "now" once a minute so the band and the bold programme move with the time.
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
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
    var landOn by remember { mutableStateOf<Pair<Any, Boolean>?>(null) }

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
                programme = highlighted?.let { (row, programme) -> programme ?: row.now },
                previewPlayer = if (previewEnabled && !recordingActive) previewController.player else null,
            )
            Spacer(Modifier.height(GUIDE_GAP))
            Box(Modifier.weight(1f)) {
                if (rows.isEmpty()) {
                    when {
                        favouritesOnly -> NoFavourites(openSidebar)
                        // Work genuinely in progress: a sync is running, the catalogue check hasn't
                        // returned yet, or channels are on disk and the guide is still building. A big
                        // provider takes a while, and "No channels" during it reads as failure.
                        isSyncing || channelsPresent == null -> Loading(isSyncing)
                        // Built, with channels on the device, and nothing here: every one is hidden.
                        channelsPresent == true && rowsBuilt -> NothingToShow(openSidebar)
                        channelsPresent == true -> Loading(isSyncing)
                        // Nothing is syncing and the catalogue is confirmed empty: with a provider set
                        // up, the last load failed or returned nothing.
                        hasSources -> LoadFailed(onRetry = onRefresh, onEditProvider = onAddSource)
                        else -> NoChannels(onAddSource)
                    }
                } else {
                    val dayLabel = when (guideDayOffset) {
                        0 -> stringResource(R.string.guide_today)
                        1 -> stringResource(R.string.guide_tomorrow)
                        else -> remember(windowStart) {
                            SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(windowStart))
                        }
                    }
                    GuideGrid(
                        rows = rows,
                        windowStartMillis = windowStart,
                        windowEndMillis = windowEnd,
                        onWatch = { row -> requestLive(row.primary) },
                        onHighlight = { row, programme -> highlighted = row to programme },
                        onProgramme = { row, programme -> details = row to programme },
                        dayLabel = dayLabel,
                        dayOffset = guideDayOffset,
                        onNextDay = { row: ChannelsViewModel.Row ->
                            landOn = row.key to false
                            viewModel.nudgeGuideDay(1)
                        }.takeIf { guideDayOffset < ChannelsViewModel.GUIDE_MAX_DAYS },
                        onPrevDay = { row: ChannelsViewModel.Row ->
                            landOn = row.key to true
                            viewModel.nudgeGuideDay(-1)
                        }.takeIf { guideDayOffset > 0 },
                        returnToChannelId = returnToChannelId,
                        takeFocus = takeFocus,
                        onReturnToRow = { highlighted = it to null },
                        landOnRow = landOn?.first,
                        landAtEnd = landOn?.second == true,
                        onLanded = { landOn = null },
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
        findBox = { state, box -> DeviceTextField(state, findHint, FIND_MAX_LENGTH, box) },
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

@Composable
private fun Loading(isSyncing: Boolean) {
    val status by StatusBus.message.collectAsState()
    val progress by StatusBus.progress.collectAsState()
    Message(
        title = stringResource(R.string.guide_loading_channels),
        body = status ?: if (isSyncing) {
            stringResource(R.string.guide_fetching_desc)
        } else {
            // Channels are already on the device; the guide is being built from them.
            stringResource(R.string.guide_building_desc)
        },
    ) {
        progress?.let { ProgressBar(it, Modifier.fillMaxWidth()) }
    }
}

/** Favourites with none in them: how to add one, and a way to another category. */
@Composable
private fun NoFavourites(openSidebar: () -> Unit) {
    Message(
        stringResource(R.string.guide_no_favourites_hint),
        title = stringResource(R.string.guide_no_favourites_title),
    ) { ActionButton(stringResource(R.string.guide_choose_category), openSidebar) }
}

/**
 * A category, or All channels, whose every channel is hidden. Without a button there'd be nothing
 * here to take the Highlight, and so no way to Left and the sidebar.
 */
@Composable
private fun NothingToShow(openSidebar: () -> Unit) {
    Message(
        stringResource(R.string.guide_empty_category_hint),
        title = stringResource(R.string.guide_empty_category_title),
    ) { ActionButton(stringResource(R.string.guide_choose_category), openSidebar) }
}

/**
 * A provider is set up but there are no channels and nothing is syncing: the last load failed or
 * came back empty. Something to act on, rather than an endless loading message.
 */
@Composable
private fun LoadFailed(onRetry: () -> Unit, onEditProvider: () -> Unit) {
    Message(
        stringResource(R.string.guide_load_failed_desc),
        title = stringResource(R.string.guide_load_failed_title),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP)) {
            ActionButton(stringResource(R.string.common_try_again), onRetry)
            ActionButton(stringResource(R.string.guide_edit_provider), onEditProvider)
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
