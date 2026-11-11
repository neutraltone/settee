/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.opentv.R
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

// LOOK.md's Rail: closed, 104 dp, the items on the 48 dp screen margin. Open, wide enough for the
// longest label in the languages Settee ships in at 16 sp.
private val RAIL_CLOSED = 104.dp
private val RAIL_OPEN = 236.dp
private val RAIL_END = 8.dp
private val RAIL_ITEM_PADDING = 12.dp
private val RAIL_ITEM_GAP = 4.dp
private const val ENTRY_RETRY_MILLIS = 100L
private const val ENTRY_TRIES = 20

/**
 * The rail beside the [current] tab's [content] (docs/LOOK.md, Rail).
 *
 * [content] gets `takeFocus`: Home and the Live guide take the Highlight when they appear (the guide
 * on the channel last watched), except while the viewer is in the rail. It also gets
 * `onNothingToShow`, which a tab with nothing to highlight calls to put the Highlight on its own
 * item in the rail.
 *
 * Back on any tab but Home goes to Home. On Home it calls [onExit], which asks before leaving: one
 * stray press would otherwise drop the viewer out to the TV's launcher. A dialog or panel open in a
 * tab takes Back before this, so it only fires at the true root.
 */
@Composable
internal fun MainShell(
    tabs: List<Tab>,
    current: Tab,
    onSelect: (Tab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    activeProfileName: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (tab: Tab, takeFocus: Boolean, onNothingToShow: () -> Unit) -> Unit,
) {
    BackHandler { if (current == Tab.HOME) onExit() else onSelect(Tab.HOME) }
    val slide = rememberRailSlide()
    val railFocused = slide.focused
    val currentItem = remember { FocusRequester() }
    val railReturn = remember(current) { RailReturn() }
    val contentFocus = remember { FocusRequester() }
    val entry = remember { RailEntry() }
    EnterContentAfterPick(entry, railFocused, railReturn, contentFocus)
    val travel = RAIL_OPEN - RAIL_CLOSED
    Box(
        modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) slide.pressed()
                false
            },
    ) {
        CompositionLocalProvider(LocalRailReturn provides railReturn) {
            Box(
                Modifier
                    .padding(start = if (slide.contentLaidOutOpen) RAIL_OPEN else RAIL_CLOSED)
                    .graphicsLayer {
                        val settled = if (slide.contentLaidOutOpen) travel.toPx() else 0f
                        translationX = slide.open() * travel.toPx() - settled
                    }
                    .fillMaxHeight()
                    .focusRequester(contentFocus)
                    .focusGroup(),
            ) {
                content(current, !railFocused || entry.entering) { runCatching { currentItem.requestFocus() } }
            }
        }
        Rail(
            tabs = tabs,
            current = current,
            onSelect = { tab ->
                entry.pick(tab, current)
                onSelect(tab)
            },
            onOpenSearch = onOpenSearch,
            onOpenSettings = onOpenSettings,
            onOpenProfiles = onOpenProfiles,
            activeProfileName = activeProfileName,
            labelled = slide.railLaidOutOpen,
            currentItem = currentItem,
            railReturn = railReturn,
            entry = entry,
            // Drawn last, over the tab's left edge while it slides, and clipped to the sliding edge.
            modifier = Modifier
                .zIndex(1f)
                .width(if (slide.railLaidOutOpen) RAIL_OPEN else RAIL_CLOSED)
                .drawWithContent {
                    clipRect(right = (RAIL_CLOSED + travel * slide.open()).toPx()) {
                        this@drawWithContent.drawContent()
                    }
                }
                .onFocusChanged { slide.onFocusChanged(it.hasFocus) },
        )
    }
}

/**
 * How far open the rail is, and how the rail and the tab beside it are laid out meanwhile.
 *
 * The slide is read only in draw and layer blocks and through derivedStateOf, so a frame of it never
 * recomposes or measures anything. Animating the rail's width instead had the tab beside it (the
 * guide, Home's rows) measured again on every frame, which stuttered on a Stick (#156).
 */
@Stable
private class RailSlide {
    var focused by mutableStateOf(false)
        private set

    /**
     * Whether a press is being handled: the Highlight moving into the rail during one is the
     * viewer's own move, which slides it open. Anything else (a tab with nothing to highlight,
     * coming back to this screen) finds it already open.
     *
     * A press lasts until the next frame, not until its key comes up: the Highlight moves while the
     * key is down, and a key that opens a dialog sends its release to the dialog's window, which
     * left the press on for good.
     */
    private var pressing = false
    private val presses = Channel<Unit>(Channel.CONFLATED)

    fun pressed() {
        pressing = true
        presses.trySend(Unit)
    }

    suspend fun endPressesEachFrame() {
        while (true) {
            presses.receive()
            withFrameNanos {}
            pressing = false
        }
    }

    var slidingOpen by mutableStateOf(false)
        private set

    val progress = Animatable(0f)

    /** 0 closed, 1 open. Until the snap open lands, a frame later, the rail already draws open. */
    fun open(): Float = if (focused && !slidingOpen) 1f else progress.value

    private val fullyOpen by derivedStateOf { open() >= 1f }
    private val partlyOpen by derivedStateOf { open() > 0f }

    /**
     * Opening, the tab keeps the closed layout until the rail is all the way out; closing, it takes
     * the closed layout straight away. Either way its right edge stays at or past the screen's, so
     * the slide never shows a gap there.
     */
    val contentLaidOutOpen get() = focused && fullyOpen

    /** Kept open while closing, so the panel and labels slide away instead of vanishing first. */
    val railLaidOutOpen get() = focused || partlyOpen

    fun onFocusChanged(hasFocus: Boolean) {
        if (hasFocus && !focused) slidingOpen = pressing
        focused = hasFocus
    }
}

@Composable
private fun rememberRailSlide(): RailSlide {
    val slide = remember { RailSlide() }
    LaunchedEffect(slide) { slide.endPressesEachFrame() }
    LaunchedEffect(slide.focused) {
        when {
            !slide.focused -> slide.progress.animateTo(0f)
            slide.slidingOpen -> slide.progress.animateTo(1f)
            else -> slide.progress.snapTo(1f)
        }
    }
    return slide
}

/** OK on a tab sends the Highlight into that tab, until it gets there or the viewer moves on in the rail. */
private class RailEntry {
    var entering by mutableStateOf(false)

    /**
     * Home and the guide take the Highlight once they've loaded, so they get first go at it, for as
     * long as they take: a big provider's guide can take far longer than the rail's retries.
     */
    var tabTakesItself = false

    fun pick(tab: Tab, current: Tab) {
        tabTakesItself = tab != current && (tab == Tab.HOME || tab == Tab.LIVE)
        entering = true
    }
}

/**
 * Home and the guide take the Highlight themselves, on the card or channel they'd open on. This
 * catches the tabs that don't, and OK on the tab already open. It retries because a tab can still be
 * loading, and gives up after two seconds, leaving the Highlight in the rail (but see
 * [RailEntry.tabTakesItself]). It mustn't act early on
 * a tab that takes the Highlight itself: in the prototype that landed on the guide's Watch tile and
 * ended [RailEntry.entering] before the channels had loaded, so the guide never took the Highlight.
 */
@Composable
private fun EnterContentAfterPick(
    entry: RailEntry,
    railFocused: Boolean,
    railReturn: RailReturn,
    contentFocus: FocusRequester,
) {
    val stillInRail by rememberUpdatedState(railFocused)
    // The tab took the Highlight (or the viewer left the rail some other way): the entry is over.
    LaunchedEffect(railFocused) { if (!railFocused) entry.entering = false }
    LaunchedEffect(entry.entering, railReturn) {
        if (!entry.entering) return@LaunchedEffect
        repeat(ENTRY_TRIES) { attempt ->
            delay(ENTRY_RETRY_MILLIS)
            if (!stillInRail) {
                entry.entering = false
                return@LaunchedEffect
            }
            if (entry.tabTakesItself && attempt < ENTRY_TRIES - 1) return@repeat
            val moved = runCatching { railReturn.target?.requestFocus() }.getOrDefault(false) == true ||
                runCatching { contentFocus.requestFocus() }.getOrDefault(false)
            if (moved) {
                entry.entering = false
                return@LaunchedEffect
            }
        }
        // A tab that takes the Highlight itself keeps its go until it does or the viewer moves on
        // in the rail: a guide still loading has nothing to take it yet, and giving up here left
        // the Highlight in the rail when the channels arrived.
        if (!entry.tabTakesItself) entry.entering = false
    }
}

/**
 * Where Right out of the rail goes. focusRestorer was tried first and lost the row: restored through
 * the nested lazy lists, it landed on the first card of the first row. So a tab marks its rows with
 * [railReturnTarget] instead; in a tab that doesn't, Right goes to the nearest item.
 */
internal class RailReturn {
    var target: FocusRequester? = null
}

internal val LocalRailReturn = staticCompositionLocalOf { RailReturn() }

/** Marks a row (or the guide) as where Right out of the rail goes back to once it has had the Highlight. */
@Composable
internal fun Modifier.railReturnTarget(): Modifier {
    val railReturn = LocalRailReturn.current
    val requester = remember { FocusRequester() }
    return focusRequester(requester).onFocusChanged { if (it.hasFocus) railReturn.target = requester }.focusGroup()
}

/** A lazy list row that Right out of the rail comes back to ([railReturnTarget]). */
internal fun LazyListScope.railReturnItem(key: Any, contentType: Any? = null, content: @Composable () -> Unit) {
    item(key = key, contentType = contentType) { Box(Modifier.railReturnTarget()) { content() } }
}

@Composable
private fun Rail(
    tabs: List<Tab>,
    current: Tab,
    onSelect: (Tab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    activeProfileName: String,
    labelled: Boolean,
    currentItem: FocusRequester,
    railReturn: RailReturn,
    entry: RailEntry,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            // Closed, the icons sit on the screen itself, with no panel behind them.
            .background(if (labelled) SetteeColors.Surface else Color.Transparent)
            .focusProperties { onEnter = { runCatching { currentItem.requestFocus() } } }
            // Moving on in the rail while a tab loads mustn't have the Highlight pulled away later.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key != Key.DirectionCenter && event.key != Key.Enter) {
                    entry.entering = false
                }
                false
            }
            // A key, not focusProperties' onExit: onExit never saw the Right press in the prototype's tests.
            .onKeyEvent { event ->
                val target = railReturn.target
                if (event.key != Key.DirectionRight || event.type != KeyEventType.KeyDown || target == null) {
                    return@onKeyEvent false
                }
                runCatching { target.requestFocus() }.getOrDefault(false)
            }
            .focusGroup()
            .padding(
                start = SetteeSpace.ScreenSide,
                end = RAIL_END,
                top = SetteeSpace.ScreenTopBottom,
                bottom = SetteeSpace.ScreenTopBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(RAIL_ITEM_GAP),
    ) {
        RailItem(Icons.Filled.Search, stringResource(R.string.nav_search), labelled, false, onClick = onOpenSearch)
        tabs.forEach { t ->
            RailItem(
                t.icon,
                stringResource(t.labelRes),
                labelled,
                current == t,
                if (current == t) Modifier.focusRequester(currentItem) else Modifier,
            ) { onSelect(t) }
        }
        Spacer(Modifier.weight(1f))
        RailItem(Icons.Filled.Person, activeProfileName, labelled, false, onClick = onOpenProfiles)
        RailItem(
            Icons.Filled.Settings,
            stringResource(R.string.nav_settings),
            labelled,
            false,
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    labelled: Boolean,
    current: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    HighlightSurface(
        onClick = onClick,
        style = if (current) HighlightStyle.CurrentRow else HighlightStyle.Row,
        modifier = modifier.fillMaxWidth(),
    ) { highlighted ->
        val colour = when {
            highlighted -> SetteeColors.OnAccent
            current -> SetteeColors.Accent
            else -> SetteeColors.QuietText
        }
        Row(Modifier.padding(RAIL_ITEM_PADDING), verticalAlignment = Alignment.CenterVertically) {
            // Closed, the icon names the item; open, the label does, so it isn't read out twice.
            Icon(icon, contentDescription = if (labelled) null else label, tint = colour)
            if (labelled) {
                Spacer(Modifier.width(RAIL_ITEM_PADDING))
                Text(
                    label,
                    SetteeType.Body,
                    color = if (highlighted ||
                        current
                    ) {
                        colour
                    } else {
                        SetteeColors.Text
                    },
                    maxLines = 1,
                )
            }
        }
    }
}
