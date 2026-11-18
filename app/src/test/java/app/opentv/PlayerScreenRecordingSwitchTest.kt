/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.RecordingSignals
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.player.PlayerScreen
import app.opentv.ui.recordings.RecordingSwitchWarning
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The warning that the screen is about to switch to a recording (#437) over the real live player,
 * whose own key handling takes every arrow key when nothing is over the picture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class PlayerScreenRecordingSwitchTest {

    @get:Rule
    val compose = createComposeRule()

    private val graph = ServiceLocator.get(ApplicationProvider.getApplicationContext<Application>())

    private fun row(streamId: String, name: String) = Channel(
        sourceId = 1,
        streamId = streamId,
        name = name,
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "http://example.com/live/u/p/$streamId.ts",
    )

    // The graph is process-wide, so its database outlives each test, and so do the signals.
    @Before
    fun catalogue() = runBlocking<Unit> {
        RecordingSignals.reset()
        graph.database.sources().insert(
            Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"),
        )
        graph.database.channels().replaceCatalogue(
            1,
            listOf(row("1", "Alpha One"), row("2", "Bravo Two"), row("3", "Charlie Three")),
            syncStamp = 1,
        )
        graph.catalogRepository.renormalizeAll()
    }

    @After
    fun forgetSignals() = RecordingSignals.reset()

    /** The player on Bravo Two, in the middle of the list, with the warning drawn over it as the app does. */
    private fun showPlayingWithWarning() {
        val id = runBlocking { graph.database.channels().everyRow().single { it.streamId == "2" }.id }
        compose.setTvContent {
            PlayerScreen(channelId = id, onBack = {})
            RecordingSwitchWarning()
        }
        compose.waitUntilAtLeastOneExists(hasText("Bravo Two", substring = true), TIMEOUT_MILLIS)
        warnOfRecording()
        compose.waitForIdle()
    }

    /** Whether the player moved off Bravo Two: a zap names the Channel it lands on. */
    private fun zapped() = listOf("Alpha One", "Charlie Three").any { name ->
        compose.onAllNodes(hasText(name, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `over the live player the warning starts on Keep watching, and up and down don't change channel`() {
        showPlayingWithWarning()
        compose.onNodeWithText("Keep watching").assertIsFocused().assertIsInSidePanel()

        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.mainClock.advanceTimeBy(ZAP_SETTLE_MILLIS)
        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_UP)
        compose.mainClock.advanceTimeBy(ZAP_SETTLE_MILLIS)

        assertThat(zapped()).isFalse()
        compose.onNodeWithText("Keep watching").assertIsFocused()
    }

    @Test
    fun `OK on Keep watching gives the live player the remote back, and the booking doesn't switch the screen`() {
        showPlayingWithWarning()

        compose.pressKeyOnPanel(KeyEvent.KEYCODE_DPAD_CENTER)

        compose.onNodeWithText("Keep watching").assertDoesNotExist()
        assertThat(RecordingSignals.imminent.value).isNull()
        assertThat(switchesWhenRecordingStarts()).isFalse()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitUntil(TIMEOUT_MILLIS) { zapped() }
    }

    @Test
    fun `Back closes the warning over the live player, gives it the remote back, and the switch stays`() {
        showPlayingWithWarning()

        compose.pressBackOnPanel()

        compose.onNodeWithText("Keep watching").assertDoesNotExist()
        assertThat(switchesWhenRecordingStarts()).isTrue()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitUntil(TIMEOUT_MILLIS) { zapped() }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L

        // Past the player's 350 ms zap debounce.
        const val ZAP_SETTLE_MILLIS = 1_000L
    }
}
