/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.shownName
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.design.CategoriesSidebar
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.Message
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.SidebarEntry
import app.opentv.ui.design.Text
import app.opentv.ui.design.TextField
import app.opentv.ui.options.ItemOption
import app.opentv.ui.options.OptionsPanel
import coil.compose.AsyncImage

/**
 * The channel manager (docs/LOOK.md, Live TV): which channels show in the guide.
 *
 * The Categories Sidebar is always open on the left, with Search for channels at the top and every
 * category under it, Parental-Locked ones included: the lock hides categories from browsing, not
 * from managing (GLOSSARY). Beside it, the picked category's channels, hidden ones too, so they
 * can be brought back. Each row is one target: OK switches it between Showing and Hidden, and
 * holding Select opens its options, which is where Favourite is. Back leaves.
 *
 * The list is always one category's (or a search's): a flat list of a 20,000-channel provider is
 * unscrollable on a remote, so browsing is by category by design.
 */
@Composable
fun ChannelManagerScreen(onBack: () -> Unit, viewModel: ChannelsViewModel = viewModel()) {
    val categoryGroups by viewModel.managerCategoryGroups.collectAsState()
    val selectedCategory by viewModel.managerSelectedCategory.collectAsState()
    val browseRows by viewModel.managerRows.collectAsState()

    var searching by remember { mutableStateOf(false) }
    val query = rememberTextFieldState()
    val searchResults by viewModel.managerResults.collectAsState()
    LaunchedEffect(query) { snapshotFlow { query.text.toString() }.collect { viewModel.setManagerQuery(it) } }

    val sidebar = remember { FocusRequester() }
    val currentRow = remember { FocusRequester() }
    // The row whose options are open, for Favourite.
    var optionsFor by remember { mutableStateOf<ChannelsViewModel.Row?>(null) }

    BackHandler { onBack() }

    // Open on the first category, so channels show straight away.
    LaunchedEffect(categoryGroups) {
        if (selectedCategory == null && categoryGroups.isNotEmpty()) {
            viewModel.selectManagerCategory(categoryGroups.first().key)
        }
    }
    // Once there's something to highlight, the sidebar takes it, on the current category. Only once,
    // so a background sync re-listing the categories never pulls the Highlight out of the list.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(categoryGroups.isNotEmpty(), selectedCategory != null) {
        if (landed || categoryGroups.isEmpty() || selectedCategory == null) return@LaunchedEffect
        // A frame resumes this before it recomposes, so the current row only wears its requester
        // a frame later; asked sooner, the sidebar falls back to its first item, Find a category.
        repeat(2) { withFrameNanos {} }
        // The row itself, not the sidebar: Android hands a window's first focus to its first view,
        // Find a category's text box, which already counts as being in the sidebar.
        runCatching { currentRow.requestFocus() }
        landed = true
    }

    val findHint = stringResource(R.string.categories_find)
    Row(Modifier.fillMaxSize().background(SetteeColors.Background)) {
        CategoriesSidebar(
            pinned = listOf(SidebarEntry(SEARCH, stringResource(R.string.channels_manager_search))),
            entries = remember(categoryGroups) { categoryGroups.map { SidebarEntry(it.key, it.label) } },
            current = if (searching) SEARCH else selectedCategory ?: SEARCH,
            onPick = { key ->
                searching = key == SEARCH
                if (!searching) viewModel.selectManagerCategory(key)
            },
            findBox = { state, box -> TextField(state, findHint, FIND_MAX_LENGTH, box) },
            modifier = Modifier
                .background(SetteeColors.Surface)
                .padding(start = SetteeSpace.ScreenSide / 2)
                .focusRequester(sidebar),
            currentRow = currentRow,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = SetteeSpace.ScreenSide, vertical = SetteeSpace.ScreenTopBottom),
        ) {
            Text(stringResource(R.string.channels_manager_title), SetteeType.ScreenTitle)
            Spacer(Modifier.height(SetteeSpace.RowGap))
            val toggle: (ChannelsViewModel.Row) -> Unit = { row -> viewModel.setRowHidden(row, !row.hidden) }
            if (searching) {
                ChannelManagerSearchPane(
                    query = query,
                    results = searchResults,
                    onToggle = toggle,
                    onOptions = { optionsFor = it },
                    onExitLeft = { runCatching { sidebar.requestFocus() }.isSuccess },
                )
            } else {
                CategoryPane(categoryGroups.isEmpty(), selectedCategory != null, browseRows, toggle) { optionsFor = it }
            }
        }
    }

    optionsFor?.let { row ->
        OptionsPanel(
            title = row.primary.shownName,
            options = listOf(if (row.favourite) ItemOption.RemoveFavourite else ItemOption.AddFavourite),
            onChoose = {
                viewModel.toggleFavourite(row)
                optionsFor = null
            },
            onDismiss = { optionsFor = null },
        )
    }
}

/** The picked category's channels, hidden ones included, so they can be shown again. */
@Composable
private fun CategoryPane(
    noCategories: Boolean,
    picked: Boolean,
    rows: List<ChannelsViewModel.Row>,
    onToggle: (ChannelsViewModel.Row) -> Unit,
    onOptions: (ChannelsViewModel.Row) -> Unit,
) {
    when {
        noCategories -> Message(stringResource(R.string.channels_manager_no_channels))
        !picked -> Unit
        rows.isEmpty() -> Message(stringResource(R.string.channels_manager_empty_category))
        else -> ManagerRows(rows, onToggle, onOptions)
    }
}

/** Search: a box at the top, its matches below it, each the same one-target row. */
@Composable
internal fun ChannelManagerSearchPane(
    query: TextFieldState,
    results: List<ChannelsViewModel.Row>,
    onToggle: (ChannelsViewModel.Row) -> Unit,
    onOptions: (ChannelsViewModel.Row) -> Unit,
    onExitLeft: () -> Boolean,
) {
    val searchBox = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { searchBox.requestFocus() }
    }
    val typed = query.text.toString()
    Column(Modifier.fillMaxSize()) {
        TextField(
            state = query,
            placeholder = stringResource(R.string.search_type_name),
            maxLength = MAX_SEARCH_LENGTH,
            imeAction = ImeAction.Search,
            // Left with the cursor at the start has nothing to move over, so it goes to the sidebar;
            // anywhere else it still moves the cursor.
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchBox)
                .onPreviewKeyEvent { e ->
                    e.type == KeyEventType.KeyDown &&
                        e.key == Key.DirectionLeft &&
                        query.selection == TextRange.Zero &&
                        onExitLeft()
                },
        )
        Spacer(Modifier.height(SetteeSpace.RowGap))
        when {
            typed.isBlank() -> Message(stringResource(R.string.channels_manager_search_hint))
            typed.trim().length < 2 -> Message(stringResource(R.string.common_keep_typing))
            results.isEmpty() -> Message(stringResource(R.string.channels_manager_no_match, typed))
            else -> ManagerRows(results, onToggle, onOptions)
        }
    }
}

@Composable
private fun ManagerRows(
    rows: List<ChannelsViewModel.Row>,
    onToggle: (ChannelsViewModel.Row) -> Unit,
    onOptions: (ChannelsViewModel.Row) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        items(rows, key = { it.key }) { row -> ManagerRow(row, { onToggle(row) }, { onOptions(row) }) }
    }
}

/** A channel: its logo and name, and whether the guide shows it. OK switches that. */
@Composable
private fun ManagerRow(row: ChannelsViewModel.Row, onToggle: () -> Unit, onOptions: () -> Unit) {
    val hidden = row.hidden
    HighlightSurface(
        onClick = onToggle,
        style = HighlightStyle.Cell,
        onOptions = onOptions,
        modifier = Modifier.fillMaxWidth(),
    ) { highlighted ->
        Row(Modifier.fillMaxWidth().padding(ROW_PADDING), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = row.primary.logoUrl,
                contentDescription = null,
                modifier = Modifier.size(LOGO).clip(SetteeShapes.Cell),
            )
            Spacer(Modifier.width(ROW_PADDING))
            Text(
                row.primary.shownName,
                SetteeType.RowTitle,
                color = when {
                    highlighted -> SetteeColors.OnAccent
                    hidden -> SetteeColors.QuietText
                    else -> SetteeColors.Text
                },
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(if (hidden) R.string.channels_hidden else R.string.channels_showing),
                SetteeType.Body,
                color = when {
                    highlighted -> SetteeColors.OnAccent
                    hidden -> SetteeColors.QuietText
                    else -> SetteeColors.Accent
                },
            )
        }
    }
}

/** A row is hidden when every one of its Qualities is. */
private val ChannelsViewModel.Row.hidden get() = variants.all { it.hidden }

// Never a category's key: those are normalised names, or a provider's number and category id.
private const val SEARCH = "pinned:search"
private const val FIND_MAX_LENGTH = 40
private val ROW_GAP = 2.dp
private val ROW_PADDING = 10.dp
private val LOGO = 32.dp
