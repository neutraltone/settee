/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.data.model.Series

/** What the show page tests share: a show to open, and how they press and find things on its page. */
fun testSeries() = Series(
    id = 1,
    sourceId = 1,
    seriesId = "s",
    name = "The Long Way Round",
    categoryId = null,
    posterUrl = "https://example.com/poster.jpg",
    rating = null,
    year = 2024,
    plot = "Two friends and a lot of road.",
    backdropUrl = "https://example.com/backdrop.jpg",
)

/** The page places its highlight a moment after it appears, once its rows are laid out. */
fun ComposeContentTestRule.settleShowPage() {
    waitForIdle()
    mainClock.advanceTimeBy(500)
}

/** Presses [key] on whatever has the Highlight, and lets the page settle. */
@OptIn(ExperimentalTestApi::class)
fun ComposeContentTestRule.pressOnShowPage(key: Key) {
    onNode(isFocused()).performKeyInput { pressKey(key) }
    settleShowPage()
}

fun ComposeContentTestRule.episodeRow(season: Int, episode: Int): SemanticsNodeInteraction =
    onNode(hasText("S$season E$episode") and hasClickAction())

/** A pill on the show page: a season, More like this, or Quality. */
fun ComposeContentTestRule.showPagePill(label: String): SemanticsNodeInteraction =
    onNode(hasText(label) and hasClickAction())
