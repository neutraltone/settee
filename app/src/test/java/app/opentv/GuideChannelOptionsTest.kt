/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.repo.LineupChannel
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.channels.GuideGrid
import app.opentv.ui.options.ItemOptionsHost
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The channel options panel from the guide, and Select playing from it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GuideChannelOptionsTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val graph = ServiceLocator.get(ApplicationProvider.getApplicationContext<Application>())
    private val played = mutableListOf<String>()

    private fun row(id: Long, name: String, epgId: String? = null): ChannelsViewModel.Row {
        val channel = Channel(
            id = id,
            sourceId = 1,
            streamId = "$id",
            name = name,
            streamUrl = "https://example.com/$id.ts",
            categoryId = null,
            logoUrl = "https://example.com/$id.png",
            epgChannelId = epgId,
            number = id.toInt(),
        )
        return ChannelsViewModel.Row(LineupChannel(channel, listOf(channel)))
    }

    private var rows by mutableStateOf(listOf(row(1, "BBC One"), row(2, "BBC Two"), row(3, "ITV")))

    private fun showGrid() = compose.setTvContent {
        ItemOptionsHost {
            GuideGrid(
                rows = rows,
                windowStartMillis = 0,
                onWatch = { played += it.primary.name },
            )
        }
    }

    private fun waitFor(text: String): SemanticsNodeInteraction {
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onNode(hasText(text, substring = true))
    }

    private fun pressMenuOn(name: String) {
        compose.onNodeWithText(name).requestFocus().performKeyInput { pressKey(Key.Menu) }
    }

    private fun holdSelectOn(name: String) {
        compose.onNodeWithText(name).requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }
    }

    private fun assertChannelActionsShown() {
        waitFor("Watch").assertIsFocused()
        waitFor("Add to Favourites")
        waitFor("Hide channel")
        waitFor("Open in external player")
        waitFor("Record now")
    }

    @Test
    fun `Menu on a channel in the grid opens its options`() {
        showGrid()

        pressMenuOn("BBC One")

        assertChannelActionsShown()
        captureScreen("guide_channel_options")
    }

    @Test
    fun `holding Select on a channel in the grid opens its options, without playing it`() {
        showGrid()

        holdSelectOn("BBC One")

        assertChannelActionsShown()
        assertThat(played).isEmpty()
    }

    @Test
    fun `Select on a channel plays it, with no dialog, in the grid`() {
        showGrid()

        compose.onNodeWithText("BBC Two").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertThat(played).containsExactly("BBC Two")
        compose.onNodeWithText("Hide channel").assertDoesNotExist()
    }

    @Test
    fun `Watch in the panel plays the channel it was opened on`() {
        showGrid()
        pressMenuOn("BBC Two")

        waitFor("Watch").performClick()
        compose.waitForIdle()

        assertThat(played).containsExactly("BBC Two")
        compose.onNodeWithText("Hide channel").assertDoesNotExist()
    }

    @Test
    fun `Back closes the panel and leaves the highlight on the channel`() {
        showGrid()
        pressMenuOn("BBC Two")
        waitFor("Watch")

        Espresso.pressBack()
        compose.waitForIdle()

        compose.onNodeWithText("Hide channel").assertDoesNotExist()
        compose.onNodeWithText("BBC Two").assertIsFocused()
    }

    @Test
    fun `when the highlighted channel leaves the grid, the highlight moves to its neighbour`() {
        showGrid()
        compose.onNodeWithText("BBC Two").requestFocus()

        rows = rows.filterNot { it.primary.name == "BBC Two" }
        compose.waitForIdle()

        compose.onNodeWithText("ITV").assertIsFocused()
    }

    @Test
    fun `Schedule a later programme lists what's on later, and leads to the record and remind choices`() {
        runBlocking {
            val feed = graph.database.epgFeeds().insert(EpgFeed(name = "Feed"))
            graph.database.programmes().insertOrReplace(
                listOf(
                    Programme(
                        feedId = feed,
                        epgChannelId = "later-guide",
                        startUtcMillis = LATE_FILM_START,
                        endUtcMillis = LATE_FILM_START + 3_600_000,
                        title = "Late Film",
                    ),
                ),
            )
        }
        rows = listOf(row(1, "BBC One", epgId = "later-guide"))
        showGrid()
        pressMenuOn("BBC One")

        waitFor("Schedule a later programme").performClick()
        waitFor("Late Film").assertIsFocused()
        captureScreen("guide_channel_options_schedule_later")
        compose.onNode(hasText("Late Film", substring = true)).performClick()

        waitFor("Schedule recording")
        waitFor("Remind me")
        waitFor("Auto-switch")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L

        // A fixed time rather than an hour from now, which the list draws as a different time on
        // every run and so turned up as changed in every PR's screenshots.
        val LATE_FILM_START = Instant.parse("2099-01-01T20:00:00Z").toEpochMilli()
    }
}
