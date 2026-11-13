/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.ui.design.FindCount
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.SidebarEntry
import app.opentv.ui.design.Text
import app.opentv.ui.design.TextField
import app.opentv.ui.design.findCategories
import kotlinx.coroutines.flow.map

/**
 * Locked categories (docs/LOOK.md, Settings), opened from the Parental lock pane: a switch for every
 * live category, locked ones included. It's a screen rather than rows in the pane because a big
 * provider has hundreds of categories, and the pane builds all its rows just by being highlighted.
 */
@Composable
fun LockedCategoriesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val settings = graph.settings
    // Straight from the lineup, not ChannelsViewModel's StateFlow: that starts as an empty list, which
    // would show "No categories yet" every time the screen opens, until the fold finished.
    val categories by remember {
        graph.channelLineup.categoryGroups(includeLocked = true)
            .map { groups -> groups.map { SidebarEntry(it.key, it.label) } }
    }.collectAsState(initial = null)
    val locked by settings.hiddenCategories.collectAsState()
    LockedCategoriesContent(
        categories = categories,
        locked = locked,
        onLock = settings::setCategoryLocked,
        onBack = onBack,
    )
}

/**
 * The screen over plain [categories], in the order they're given: nothing is re-sorted when one is
 * locked, so a row never moves away from the Highlight. Null while they're still being read, when
 * the screen shows only its title. [onLock] is a category's key and whether to lock it.
 */
@Composable
internal fun LockedCategoriesContent(
    categories: List<SidebarEntry>?,
    locked: Set<String>,
    onLock: (key: String, lock: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val find = rememberTextFieldState()
    val firstRow = remember { FocusRequester() }
    // Once, when the categories first arrive: they're read off the main thread, so not on the first
    // frame. Finding afterwards mustn't pull the Highlight out of the box.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(categories.isNullOrEmpty()) {
        if (landed || categories.isNullOrEmpty()) return@LaunchedEffect
        withFrameNanos {}
        if (runCatching { firstRow.requestFocus() }.getOrNull() == true) landed = true
    }
    ScreenFrame(modifier) {
        Column(Modifier.widthIn(max = MAX_WIDTH), verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            Text(stringResource(R.string.parental_locked_categories), SetteeType.ScreenTitle)
            Text(stringResource(R.string.locked_categories_hint), SetteeType.Body, color = SetteeColors.QuietText)
            if (categories == null) return@Column
            if (categories.isEmpty()) {
                Text(
                    stringResource(R.string.parental_no_categories),
                    SetteeType.Body,
                    color = SetteeColors.QuietText,
                    modifier = Modifier.padding(top = 12.dp),
                )
                return@Column
            }
            TextField(find, stringResource(R.string.categories_find), FIND_MAX_LENGTH, Modifier.fillMaxWidth())
            val query = find.text.toString()
            val shown = findCategories(categories, query)
            FindCount(query, shown.size, categories.size)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                itemsIndexed(shown, key = { _, entry -> entry.key }) { index, entry ->
                    val isLocked = entry.key in locked
                    ListRow(
                        entry.label,
                        onClick = { onLock(entry.key, !isLocked) },
                        switch = isLocked,
                        modifier = if (index == 0) Modifier.focusRequester(firstRow) else Modifier,
                    )
                }
            }
        }
    }
}

private val MAX_WIDTH = 900.dp
private val ROW_GAP = 8.dp
private const val FIND_MAX_LENGTH = 40
