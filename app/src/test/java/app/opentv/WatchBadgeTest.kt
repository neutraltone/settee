/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.opentv.data.repo.WatchStatus
import app.opentv.ui.WatchBadge
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
class WatchBadgeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(status: WatchStatus) = compose.setTvContent { WatchBadge(status) }

    @Test
    fun `a Watched title is labelled Watched`() {
        show(WatchStatus.Watched)

        compose.onNodeWithText("Watched").assertExists()
    }

    @Test
    fun `an In Progress title is labelled with the minutes left`() {
        show(WatchStatus.InProgress(progress = 0.425f, remainingMillis = 23 * 60_000L))

        compose.onNodeWithText("23 min left").assertExists()
    }

    @Test
    fun `the minutes left are rounded to the nearest minute`() {
        show(WatchStatus.InProgress(progress = 0.4f, remainingMillis = 22 * 60_000L + 40_000))

        compose.onNodeWithText("23 min left").assertExists()
    }

    @Test
    fun `seconds left still count as a minute, never none`() {
        show(WatchStatus.InProgress(progress = 0.9f, remainingMillis = 20_000))

        compose.onNodeWithText("1 min left").assertExists()
    }

    @Test
    fun `an In Progress title with no known duration is labelled In progress`() {
        show(WatchStatus.InProgress(progress = 0f, remainingMillis = null))

        compose.onNodeWithText("In progress").assertExists()
    }

    @Test
    fun `an Unwatched title has no badge`() {
        show(WatchStatus.Unwatched)

        compose.onAllNodes(anyLabel, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the badge's label is all TalkBack reads, and it can't take focus`() {
        show(WatchStatus.InProgress(progress = 0.425f, remainingMillis = 23 * 60_000L))

        compose.onAllNodes(anyLabel, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(isFocusable(), useUnmergedTree = true).assertCountEquals(0)
    }

    private val anyLabel = hasText("", substring = true) or hasContentDescription("", substring = true)
}
