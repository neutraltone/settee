/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Movie
import app.opentv.data.model.personNames
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.Pill
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.SidePanel
import app.opentv.ui.design.Text
import kotlinx.coroutines.launch

// The two ways onto a person's page. A film's page has room beneath its hero, so its people are pills
// there, the cast in a row as it was before the redesign. A show's page doesn't: one Down from its buttons
// reaches the episode in progress, and a row in between would take that Down, so its cast is a panel
// opened from a button instead.

/**
 * A film's people beneath its hero: a Cast row of pills, everyone billed, then who directed, wrote and
 * made it as columns side by side, each name a pill. OK on a pill calls [onOpenPerson] with that
 * name. A heading only for what the film has, and nothing at all when it credits nobody.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PeopleRows(movie: Movie, onOpenPerson: (String) -> Unit, modifier: Modifier = Modifier) {
    val billed = personNames(movie.cast)
    val crew = listOf(
        R.string.vod_director to personNames(movie.director),
        R.string.vod_writer to personNames(movie.writer),
        R.string.vod_studio to personNames(movie.studio),
    ).filter { (_, names) -> names.isNotEmpty() }
    if (billed.isEmpty() && crew.isEmpty()) return
    // It all comes into view together, so the columns aren't left off the bottom of the screen while
    // the Highlight is on the Cast row above them.
    val wholeBlock = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(
        modifier
            .bringIntoViewRequester(wholeBlock)
            .onFocusChanged { if (it.hasFocus) scope.launch { wholeBlock.bringIntoView() } },
    ) {
        if (billed.isNotEmpty()) {
            Text(stringResource(R.string.vod_cast), SetteeType.Section)
            Spacer(Modifier.height(HEADING_GAP))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(PILL_GAP)) {
                items(billed, key = {
                    it
                }) { name -> Pill(label = name, selected = false, onClick = { onOpenPerson(name) }) }
            }
        }
        if (billed.isNotEmpty() && crew.isNotEmpty()) Spacer(Modifier.height(SetteeSpace.RowGap))
        Row(horizontalArrangement = Arrangement.spacedBy(CREW_COLUMN_GAP)) {
            crew.forEach { (heading, names) -> CrewColumn(stringResource(heading), names, onOpenPerson) }
        }
    }
}

/** One of the crew columns: a heading, then its names as pills, one under another. */
@Composable
private fun CrewColumn(heading: String, names: List<String>, onOpenPerson: (String) -> Unit) {
    Column(Modifier.width(CREW_COLUMN_WIDTH), verticalArrangement = Arrangement.spacedBy(PILL_GAP)) {
        Text(heading, SetteeType.Section)
        names.forEach { name -> Pill(label = name, selected = false, onClick = { onOpenPerson(name) }) }
    }
}

/**
 * A show's cast in a side panel under [title], a row each in billing order. OK on a row calls
 * [onPick] with their name, to open their page; Back calls [onBack].
 */
@Composable
internal fun CastPanel(title: String, cast: List<String>, onPick: (String) -> Unit, onBack: () -> Unit) {
    SidePanel(onBack = onBack) {
        Text(title, SetteeType.Section)
        Spacer(Modifier.height(HEADING_GAP))
        cast.forEach { name -> PanelRow(label = name, onClick = { onPick(name) }) }
    }
}

private val HEADING_GAP = 12.dp
private val PILL_GAP = 12.dp

/** Wide enough for most names and studios, so the columns line up as a table does. */
private val CREW_COLUMN_WIDTH = 240.dp
private val CREW_COLUMN_GAP = 24.dp
