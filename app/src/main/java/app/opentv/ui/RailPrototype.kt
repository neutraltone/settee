/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.opentv.BuildConfig
import app.opentv.R

/*
 * PROTOTYPE for #90: how the left-hand navigation behaves with a remote. Throwaway, never merged.
 *
 * Three rails on the real main screen, cycled with the remote's rewind and fast-forward:
 * A snaps open over the content, B is always open, and Today is the rail on main, for comparison.
 * A and B share #90's rules: Left enters on the current tab, Right returns to the item left, Back is
 * unchanged, and the current tab stays marked when collapsed. Search, Profile and Settings stay in
 * the rail only because #90 hasn't settled where they go.
 */
enum class RailVariant(val label: String) {
    A("A: snaps open over content"),
    B("B: always open"),
    TODAY("Today: animated, pushes content"),
}

internal object RailPrototype {
    // In memory only, so a restart always opens on A.
    var variant by mutableStateOf(RailVariant.A)
}

// #86 keeps focused items inside a 5% overscan margin: 48dp across and 27dp down at 960x540dp.
private val OVERSCAN_X = 48.dp
private val OVERSCAN_Y = 27.dp
private val COLLAPSED = 104.dp
private val EXPANDED = 236.dp

/** Rewind and fast-forward cycle the rail; nothing else on the main screen uses them. */
@Composable
internal fun RailPrototypeSwitcher(modifier: Modifier = Modifier, rail: @Composable (RailVariant) -> Unit) {
    var focusIn by remember { mutableStateOf("nothing") }
    val variants = RailVariant.entries
    Box(
        modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                val step = when (event.key) {
                    Key.MediaFastForward -> 1
                    Key.MediaRewind -> -1
                    else -> return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) {
                    val next = (RailPrototype.variant.ordinal + step).mod(variants.size)
                    RailPrototype.variant = variants[next]
                }
                true
            }
            .onFocusChanged { focusIn = if (it.hasFocus) "app" else "nothing" },
    ) {
        rail(RailPrototype.variant)
        if (BuildConfig.DEBUG) {
            Text(
                "PROTOTYPE  ◀◀ ${RailPrototype.variant.label} ▶▶  focus in $focusIn",
                color = Color.Black,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(2f)
                    .padding(bottom = 8.dp)
                    .background(Color(0xFFFFD54F), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** A: the collapsed rail reserves its width; opening it covers the content and moves nothing. */
@Composable
internal fun SnapOverlayShell(
    tabs: List<Tab>,
    current: Tab,
    onSelect: (Tab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    activeProfileName: String,
    content: @Composable (tab: Tab, takeFocus: Boolean, onNothingToShow: () -> Unit) -> Unit,
) {
    var railFocused by remember { mutableStateOf(false) }
    val currentItem = remember { FocusRequester() }
    val railReturn = remember(current) { RailReturn() }
    Box(Modifier.fillMaxSize()) {
        ContentArea(railReturn, Modifier.padding(start = COLLAPSED)) {
            content(current, !railFocused) { runCatching { currentItem.requestFocus() } }
        }
        PrototypeRail(
            tabs, current, onSelect, onOpenSearch, onOpenSettings, onOpenProfiles, activeProfileName,
            expanded = railFocused,
            currentItem = currentItem,
            railReturn = railReturn,
            // Drawn last so it covers the content. Opaque: #85 rules out alpha-dimmed layers.
            modifier = Modifier
                .zIndex(1f)
                .width(if (railFocused) EXPANDED else COLLAPSED)
                .onFocusChanged { railFocused = it.hasFocus },
        )
    }
}

/** B: labels always showing, at the cost of about a quarter of the 960dp canvas. */
@Composable
internal fun AlwaysOpenShell(
    tabs: List<Tab>,
    current: Tab,
    onSelect: (Tab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    activeProfileName: String,
    content: @Composable (tab: Tab, takeFocus: Boolean, onNothingToShow: () -> Unit) -> Unit,
) {
    var railFocused by remember { mutableStateOf(false) }
    val currentItem = remember { FocusRequester() }
    val railReturn = remember(current) { RailReturn() }
    Row(Modifier.fillMaxSize()) {
        PrototypeRail(
            tabs, current, onSelect, onOpenSearch, onOpenSettings, onOpenProfiles, activeProfileName,
            expanded = true,
            currentItem = currentItem,
            railReturn = railReturn,
            modifier = Modifier.width(EXPANDED).onFocusChanged { railFocused = it.hasFocus },
        )
        ContentArea(railReturn, Modifier.weight(1f)) {
            content(current, !railFocused) { runCatching { currentItem.requestFocus() } }
        }
    }
}

/**
 * Where Right out of the rail goes. focusRestorer was tried first and lost the row: restored through
 * the nested lists it landed on the first card of the first row. So a screen marks its rows instead,
 * and only Home does so far; a tab that doesn't falls back to the nearest item, as today.
 */
internal class RailReturn {
    var target: FocusRequester? = null
}

internal val LocalRailReturn = staticCompositionLocalOf { RailReturn() }

@Composable
internal fun Modifier.railReturnTarget(): Modifier {
    val railReturn = LocalRailReturn.current
    val requester = remember { FocusRequester() }
    return focusRequester(requester).onFocusChanged { if (it.hasFocus) railReturn.target = requester }.focusGroup()
}

@Composable
private fun ContentArea(railReturn: RailReturn, modifier: Modifier, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRailReturn provides railReturn) {
        Box(modifier.fillMaxHeight().focusGroup()) { content() }
    }
}

@Composable
private fun PrototypeRail(
    tabs: List<Tab>,
    current: Tab,
    onSelect: (Tab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    activeProfileName: String,
    expanded: Boolean,
    currentItem: FocusRequester,
    railReturn: RailReturn,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .focusProperties { onEnter = { runCatching { currentItem.requestFocus() } } }
            // A key, not focusProperties' onExit: onExit never saw the Right press in the tests.
            .onKeyEvent { event ->
                val target = railReturn.target
                if (event.key != Key.DirectionRight || event.type != KeyEventType.KeyDown || target == null) {
                    return@onKeyEvent false
                }
                runCatching { target.requestFocus() }.getOrDefault(false)
            }
            .focusGroup()
            .padding(start = OVERSCAN_X, end = 8.dp, top = OVERSCAN_Y, bottom = OVERSCAN_Y),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_opentv_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(34.dp),
            )
            if (expanded) {
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        tabs.forEach { t ->
            PrototypeRailItem(
                t.icon,
                stringResource(t.labelRes),
                expanded,
                current == t,
                if (current == t) Modifier.focusRequester(currentItem) else Modifier,
            ) { onSelect(t) }
        }
        Spacer(Modifier.weight(1f))
        PrototypeRailItem(Icons.Filled.Search, stringResource(R.string.nav_search), expanded, false, onClick = onOpenSearch)
        PrototypeRailItem(Icons.Filled.Person, activeProfileName, expanded, false, onClick = onOpenProfiles)
        PrototypeRailItem(Icons.Filled.Settings, stringResource(R.string.nav_settings), expanded, false, onClick = onOpenSettings)
    }
}

@Composable
private fun PrototypeRailItem(
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    current: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val tint = when {
        focused -> colors.onPrimary
        current -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val mark = colors.primary
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) colors.primary else if (current) colors.surfaceVariant else colors.surface)
            // The mark tells the current tab from the highlight, which fills the whole item.
            .drawBehind { if (current && !focused) drawRect(mark, size = Size(4.dp.toPx(), size.height)) }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        if (expanded) {
            Spacer(Modifier.width(14.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = tint,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
