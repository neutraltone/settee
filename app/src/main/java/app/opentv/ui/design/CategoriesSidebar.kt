/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R

/**
 * A tab's list of categories (docs/LOOK.md, Categories Sidebar), knowing nothing about what's in
 * them: [pinned] rows such as For you, then [entries] in order, the [current] one's key, and
 * [onPick] with the key the viewer chose. Coming into it from anywhere lands on [current].
 *
 * Find a category narrows [entries] to those whose label has what's typed, and says how many of
 * them are showing. [pinned] rows aren't categories, so they always show and aren't counted.
 * [findBox] draws its text box over the state it's given, so the screen can use the text box the
 * rest of the app types with.
 */
@Composable
fun CategoriesSidebar(
    pinned: List<SidebarEntry>,
    entries: List<SidebarEntry>,
    current: String,
    onPick: (String) -> Unit,
    findBox: @Composable (state: TextFieldState, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val find = rememberTextFieldState()
    val currentRow = remember { FocusRequester() }
    val list = rememberLazyListState(
        initialFirstVisibleItemIndex = (pinned + entries).indexOfFirst { it.key == current }.coerceAtLeast(0),
    )
    Column(
        modifier
            .width(CategoriesSidebarWidth)
            .fillMaxHeight()
            .focusProperties { onEnter = { runCatching { currentRow.requestFocus() } } }
            .focusGroup(),
    ) {
        findBox(find, Modifier.fillMaxWidth())
        val query = find.text.toString().trim()
        val shown = if (query.isEmpty()) entries else entries.filter { it.label.contains(query, ignoreCase = true) }
        if (query.isNotEmpty()) {
            Text(
                stringResource(R.string.categories_find_count, shown.size, entries.size),
                SetteeType.Body,
                color = SetteeColors.QuietText,
                modifier = Modifier.padding(ROW_PADDING),
            )
        }
        LazyColumn(state = list) {
            items(pinned + shown, key = { it.key }) { entry ->
                val isCurrent = entry.key == current
                HighlightSurface(
                    onClick = { onPick(entry.key) },
                    style = HighlightStyle.Cell,
                    modifier = (if (isCurrent) Modifier.focusRequester(currentRow) else Modifier).fillMaxWidth(),
                ) { highlighted ->
                    Text(
                        entry.label,
                        SetteeType.Body,
                        color = when {
                            highlighted -> SetteeColors.OnAccent
                            isCurrent -> SetteeColors.Accent
                            else -> SetteeColors.Text
                        },
                        maxLines = 1,
                        modifier = Modifier.padding(ROW_PADDING),
                    )
                }
            }
        }
    }
}

/** How wide a [CategoriesSidebar] is, for sliding the screen beside it along by as much. */
val CategoriesSidebarWidth = 214.dp

private val ROW_PADDING = 10.dp
