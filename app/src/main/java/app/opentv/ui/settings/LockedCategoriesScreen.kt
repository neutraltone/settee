/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.CategoryGroup
import app.opentv.ui.design.FindCount
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.SidebarEntry
import app.opentv.ui.design.Text
import app.opentv.ui.design.TextField
import app.opentv.ui.design.findCategories
import kotlinx.coroutines.flow.Flow

/**
 * Locked categories (GLOSSARY: Parental Lock): a switch per category of every provider, on when
 * it's locked. A screen of its own, opened from the Parental lock pane, because a big provider has
 * hundreds of categories: lazily, only the rows on screen are built. Back returns to the pane's row.
 * Find a category narrows it as the Categories Sidebar's does, by the same rule ([findCategories]).
 */
@Composable
fun LockedCategoriesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    settings: AppSettings = ServiceLocator.get(LocalContext.current).settings,
    // Straight from the lineup, not ChannelsViewModel's StateFlow, which starts as an empty list:
    // that showed "No categories yet" every time the screen opened, until the fold had finished.
    categories: Flow<List<CategoryGroup>> = ServiceLocator.get(LocalContext.current).channelLineup.let { lineup ->
        remember(lineup) { lineup.categoryGroups(includeLocked = true) }
    },
) {
    val loaded by categories.collectAsState(initial = null)
    val entries = remember(loaded) { loaded?.map { SidebarEntry(it.key, it.label) } }
    val hidden by settings.hiddenCategories.collectAsState()
    val firstRow = remember { FocusRequester() }
    // Once, when the categories arrive. Not startsFocused on the first row: a lazy row is composed
    // again each time it scrolls back into view, and would take the Highlight back each time.
    LaunchedEffect(entries.isNullOrEmpty()) {
        if (entries.isNullOrEmpty()) return@LaunchedEffect
        withFrameNanos {}
        runCatching { firstRow.requestFocus() }
    }
    BackHandler(onBack = onBack)
    // The pane only opens this once the PIN is in, but Android can bring the app back on this
    // screen after it closed, which forgets the PIN: then it's not this screen's to show.
    val pinIsSet by settings.pinIsSet.collectAsState()
    val pinEntered by settings.pinEntered.collectAsState()
    if (pinIsSet && !pinEntered) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    Column(
        modifier
            .fillMaxSize()
            .background(SetteeColors.Background)
            .padding(horizontal = SetteeSpace.ScreenSide, vertical = SetteeSpace.ScreenTopBottom),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.parental_locked_categories), SetteeType.ScreenTitle)
        Text(stringResource(R.string.parental_lock_sync_note), SetteeType.Body, color = SetteeColors.QuietText)
        // Nothing more until they've been read, rather than saying there are none.
        if (entries == null) return@Column
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.parental_no_categories),
                SetteeType.Body,
                color = SetteeColors.QuietText,
                modifier = Modifier.padding(top = 12.dp),
            )
            return@Column
        }
        val find = rememberTextFieldState()
        TextField(find, stringResource(R.string.categories_find), FIND_MAX_LENGTH, Modifier.widthIn(max = 900.dp))
        val query = find.text.toString()
        val shown = findCategories(entries, query)
        FindCount(query, shown.size, entries.size)
        LazyColumn(
            Modifier.weight(1f).widthIn(max = 900.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(shown, key = { _, entry -> entry.key }) { index, entry ->
                val locked = entry.key in hidden
                ListRow(
                    entry.label,
                    onClick = { settings.setCategoryLocked(entry.key, !locked) },
                    switch = locked,
                    modifier = if (index == 0) Modifier.focusRequester(firstRow) else Modifier,
                )
            }
        }
    }
}

private const val FIND_MAX_LENGTH = 40
