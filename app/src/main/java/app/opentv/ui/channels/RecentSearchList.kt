/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.openOptionsOn

/**
 * What the search screen shows with nothing typed. [recentSearches] is null until loaded, when it
 * shows nothing rather than a hint that the list then replaces.
 */
@Composable
internal fun EmptySearchBox(recentSearches: List<String>?, onChoose: (String) -> Unit, onClear: () -> Unit) {
    when {
        recentSearches == null -> Unit
        recentSearches.isEmpty() -> SearchHint(stringResource(R.string.search_start_hint))
        else -> RecentSearchList(recentSearches, onChoose, onClear)
    }
}

/**
 * The Profile's Recent Searches, for an empty box. A plain column, not a lazy one: there are ten at
 * most, and the highlight has to be able to land on any of them when a neighbour is removed.
 */
@Composable
private fun RecentSearchList(recentSearches: List<String>, onChoose: (String) -> Unit, onClear: () -> Unit) {
    val rows = remember(recentSearches.size) { List(recentSearches.size) { FocusRequester() } }
    var highlighted by remember { mutableStateOf<Int?>(null) }
    var previousSize by remember { mutableIntStateOf(recentSearches.size) }
    val current by rememberUpdatedState(recentSearches)

    // Removing the highlighted row would otherwise drop the highlight, leaving the remote nothing to move.
    LaunchedEffect(recentSearches.size) {
        val removed = recentSearches.size < previousSize
        previousSize = recentSearches.size
        val at = highlighted
        if (removed && at != null) runCatching { rows[at.coerceAtMost(rows.lastIndex)].requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SearchSectionHeader(stringResource(R.string.search_recent_title))
        recentSearches.forEachIndexed { i, text ->
            key(text) {
                RecentSearchRow(
                    text = text,
                    onClick = { onChoose(text) },
                    modifier = Modifier
                        .focusRequester(rows[i])
                        .onFocusChanged {
                            if (it.isFocused) {
                                highlighted = i
                            } else if (highlighted == i && text in current) {
                                // Still listed, so the highlight moved off it rather than it being removed.
                                highlighted = null
                            }
                        },
                )
            }
        }
        OutlinedButton(onClick = onClear, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.search_clear_recent))
        }
    }
}

@Composable
private fun RecentSearchRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SearchRow(
        onClick = onClick,
        onOptions = openOptionsOn(OptionsSubject.OfRecentSearch(text)),
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
