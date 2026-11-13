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
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text

/**
 * Locked categories (GLOSSARY: Parental Lock): a switch per category of every provider, on when
 * it's locked. A screen of its own, opened from the Parental lock pane, because a big provider has
 * hundreds of categories: lazily, only the rows on screen are built. Back returns to the pane's row.
 */
@Composable
fun LockedCategoriesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChannelsViewModel = viewModel(),
    settings: AppSettings = ServiceLocator.get(LocalContext.current).settings,
) {
    val categories by viewModel.categoryGroups.collectAsState()
    val hidden by settings.hiddenCategories.collectAsState()
    val firstRow = remember { FocusRequester() }
    // Once, when the categories arrive. Not startsFocused on the first row: a lazy row is composed
    // again each time it scrolls back into view, and would take the Highlight back each time.
    LaunchedEffect(categories.isNotEmpty()) {
        if (categories.isEmpty()) return@LaunchedEffect
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
        if (categories.isEmpty()) {
            Text(
                stringResource(R.string.parental_no_categories),
                SetteeType.Body,
                color = SetteeColors.QuietText,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        LazyColumn(
            Modifier.weight(1f).widthIn(max = 900.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(categories, key = { _, group -> group.key }) { index, group ->
                val locked = group.key in hidden
                ListRow(
                    group.label,
                    onClick = { settings.setCategoryLocked(group.key, !locked) },
                    switch = locked,
                    modifier = if (index == 0) Modifier.focusRequester(firstRow) else Modifier,
                )
            }
        }
    }
}
