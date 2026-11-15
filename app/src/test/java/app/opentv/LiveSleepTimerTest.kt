/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.core.SleepTimer
import app.opentv.player.PlayerController
import app.opentv.ui.player.LivePlayerContent
import app.opentv.ui.player.SubtitlePicks
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The live player and the sleep timer: it leaves when the timer runs out, and only then. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(UnstableApi::class)
class LiveSleepTimerTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val players = TestPlayers(context)
    private val controller = players.controller(LIVE, dvr = true)
    private var backs = 0

    @After
    fun release() {
        // A process-wide singleton: a timer left armed would close the next test's player.
        SleepTimer.clear()
        controller.release()
        players.close()
    }

    private fun showChannel() {
        compose.setTvContent {
            LivePlayerContent(
                controller = controller,
                settings = AppSettings.get(context),
                queue = emptyList(),
                variants = emptyList(),
                currentId = 1L,
                previousId = null,
                cardChannel = null,
                nowAndNext = { null },
                onNow = { emptyMap() },
                recordingThis = false,
                livePause = true,
                pausePromptDismissed = false,
                onPlayChannel = {},
                onZap = { _, _ -> },
                onTune = {},
                onToggleRecord = {},
                favourite = false,
                onToggleFavourite = {},
                onTurnOnLivePause = {},
                onDontAskAgain = {},
                subtitlePicks = SubtitlePicks(onOff = {}, onPicked = {}),
                onBack = { backs++ },
            )
        }
        players.play(controller, isLive = true)
        compose.waitForIdle()
    }

    @Test
    fun `a sleep timer that ran out before the channel opened is off, and the channel plays on`() {
        SleepTimer.armMinutes(-1)

        showChannel()
        compose.mainClock.advanceTimeBy(60_000L)

        assertThat(backs).isEqualTo(0)
        assertThat(controller.state.value).isNotEqualTo(PlayerController.State.Idle)
        assertThat(SleepTimer.deadline.value).isNull()
    }

    @Test
    fun `a sleep timer that runs out while watching stops the channel, leaves and turns itself off`() {
        showChannel()
        SleepTimer.armMinutes(15)

        compose.mainClock.advanceTimeBy(14 * 60_000L)
        assertThat(backs).isEqualTo(0)

        compose.mainClock.advanceTimeBy(60_000L + 1_000L)
        assertThat(backs).isEqualTo(1)
        assertThat(controller.state.value).isEqualTo(PlayerController.State.Idle)
        assertThat(SleepTimer.deadline.value).isNull()
    }
}
