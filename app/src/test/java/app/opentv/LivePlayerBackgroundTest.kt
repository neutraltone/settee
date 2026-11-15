/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.core.PipState
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
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

/**
 * Live TV behind another app stops, so the provider's connection closes, and the channel tunes in again
 * on coming back (#356).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class LivePlayerBackgroundTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val players = TestPlayers(context)
    private val lifecycle = TestLifecycle()

    /** Every channel the controller was asked to find. */
    private val asked = mutableListOf<MediaKey>()
    private val controller = players.controller(LIVE) { key ->
        asked += key
        Playable.Ready(key, "https://example.com/live", userAgent = "", title = "Seven", isLive = true)
    }

    @After
    fun release() {
        controller.release()
        players.close()
    }

    /** The live player on [player], in a screen whose lifecycle [lifecycle] moves, after [start]. */
    private fun showChannel(player: PlayerController = controller, start: (PlayerController) -> Unit = ::startSeven) {
        compose.setTvContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                LivePlayerContent(
                    controller = player,
                    settings = AppSettings.get(context),
                    queue = emptyList(),
                    variants = emptyList(),
                    currentId = 7L,
                    previousId = null,
                    onNow = { emptyMap() },
                    cardChannel = null,
                    nowAndNext = { null },
                    recordingThis = false,
                    livePause = false,
                    pausePromptDismissed = true,
                    onPlayChannel = {},
                    onZap = { _, _ -> },
                    onTune = {},
                    onToggleRecord = {},
                    favourite = false,
                    onToggleFavourite = {},
                    onTurnOnLivePause = {},
                    onDontAskAgain = {},
                    subtitlePicks = SubtitlePicks(onOff = {}, onPicked = {}),
                    onBack = {},
                )
            }
        }
        start(player)
        compose.waitForIdle()
    }

    private fun startSeven(player: PlayerController) {
        player.play(PlayerController.Request(MediaKey.Live(7)), debounce = false)
        players.untilReady(player.player)
    }

    @Test
    fun `behind another app the channel stops, closing its connection, and coming back it tunes in again`() {
        showChannel()

        lifecycle.goBehind()
        compose.waitForIdle()

        assertThat(controller.state.value).isEqualTo(PlayerController.State.Idle)
        assertThat(controller.player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(controller.player.isLoading).isFalse()

        lifecycle.comeBack()
        compose.waitForIdle()
        players.untilReady(controller.player)

        assertThat(asked).containsExactly(MediaKey.Live(7), MediaKey.Live(7))
        assertThat(controller.state.value).isInstanceOf(PlayerController.State.Playing::class.java)
        assertThat(controller.playWhenReady.value).isTrue()
    }

    @Test
    fun `a channel asked for once the app is behind waits, and comes on when it's back`() {
        showChannel()
        lifecycle.goBehind()
        compose.waitForIdle()

        // A zap that finished looking up its channel after Home was pressed.
        controller.play(PlayerController.Request(MediaKey.Live(8)), debounce = false)
        players.advance(1_000)

        assertThat(asked).containsExactly(MediaKey.Live(7))
        assertThat(controller.player.playbackState).isEqualTo(Player.STATE_IDLE)

        lifecycle.comeBack()
        compose.waitForIdle()
        players.untilReady(controller.player)

        assertThat(asked).containsExactly(MediaKey.Live(7), MediaKey.Live(8)).inOrder()
        assertThat(controller.state.value).isInstanceOf(PlayerController.State.Playing::class.java)
    }

    @Test
    fun `an error showing as the app goes behind is still there on coming back, and nothing restarted it`() {
        val failing = players.controller(LIVE, failing = 100)
        showChannel(failing) {
            it.play(PlayerController.Request(MediaKey.Live(1)), debounce = false)
            players.until { it.state.value is PlayerController.State.Error }
        }
        val tries = players.played.size

        lifecycle.goBehind()
        compose.waitForIdle()
        players.advance(10_000)
        lifecycle.comeBack()
        compose.waitForIdle()
        players.advance(10_000)

        assertThat(failing.state.value).isInstanceOf(PlayerController.State.Error::class.java)
        assertThat(players.played).hasSize(tries)
        failing.release()
    }

    @Test
    fun `in picture-in-picture the channel plays on when the app is behind another`() {
        PipState.setInPip(true)
        try {
            showChannel()

            lifecycle.goBehind()
            compose.waitForIdle()

            assertThat(controller.state.value).isInstanceOf(PlayerController.State.Playing::class.java)
            assertThat(controller.player.playbackState).isEqualTo(Player.STATE_READY)
        } finally {
            PipState.setInPip(false)
        }
    }
}
