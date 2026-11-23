/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ViewerError
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.data.repo.RecordedOn
import app.opentv.player.PlayerController
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A stream that never gets as far as playing stops after the start-up limit with a sentence and Try
 * again, instead of ten minutes of loading (#463). The test's hanging streams never start and never fail,
 * as from a panel that takes the connection and sends nothing.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StartUpLimitTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val players = TestPlayers(context).apply { limitStartUp = true }

    @After
    fun close() = players.close()

    @Test
    fun `a stream not playing 45 seconds after it was asked for stops with the timed-out sentence and Try again`() {
        val controller = hangingController()
        start(controller)

        players.advance(LIMIT_MILLIS - MARGIN_MILLIS)
        assertThat(controller.state.value).isEqualTo(PlayerController.State.Buffering("Film"))

        players.advance(MARGIN_MILLIS)
        assertThat(controller.state.value).isEqualTo(
            PlayerController.State.Error(title = "Film", reason = TIMED_OUT, canRetry = true),
        )
    }

    @Test
    fun `reaching the limit neither restarts the stream nor steps down a Quality`() {
        val controller = hangingController()
        start(
            controller,
            PlayerController.Request(
                MediaKey.Movie(1),
                label = "HD",
                lower = listOf(PlayerController.Quality(MediaKey.Movie(2), "SD")),
            ),
        )

        players.advance(LIMIT_MILLIS)
        players.advance(TestPlayers.RESTARTS_MILLIS + LIMIT_MILLIS)

        assertThat(players.played).hasSize(1)
        assertThat(controller.steppedDownTo.value).isNull()
        assertThat(controller.state.value).isEqualTo(
            PlayerController.State.Error(title = "Film", reason = TIMED_OUT, canRetry = true),
        )
    }

    @Test
    fun `Try again starts the stream again with a full limit`() {
        val controller = hangingController()
        start(controller)
        players.advance(LIMIT_MILLIS)

        controller.retry()
        players.until { players.played.size == 2 }

        players.advance(LIMIT_MILLIS - MARGIN_MILLIS)
        assertThat(controller.state.value).isEqualTo(PlayerController.State.Buffering("Film"))
        players.advance(MARGIN_MILLIS)
        assertThat(controller.state.value).isEqualTo(
            PlayerController.State.Error(title = "Film", reason = TIMED_OUT, canRetry = true),
        )
    }

    @Test
    fun `a stream that played in time carries on`() {
        val controller = players.controller()
        start(controller)
        players.until { controller.player.playbackState == Player.STATE_READY }

        players.advance(LIMIT_MILLIS * 2)

        assertThat(controller.state.value).isEqualTo(PlayerController.State.Playing("Film"))
    }

    @Test
    fun `changing channel before the limit gives the new one a full limit of its own`() {
        val controller = hangingController()
        start(controller, PlayerController.Request(MediaKey.Live(1)))
        players.advance(LIMIT_MILLIS - MARGIN_MILLIS)

        start(controller, PlayerController.Request(MediaKey.Live(2)))
        players.advance(LIMIT_MILLIS - MARGIN_MILLIS)
        assertThat(controller.state.value).isEqualTo(PlayerController.State.Buffering("Film"))

        players.advance(MARGIN_MILLIS)
        assertThat(controller.state.value).isEqualTo(
            PlayerController.State.Error(title = "Film", reason = TIMED_OUT, canRetry = true),
        )
    }

    @Test
    fun `a stream that fails first is restarted by itself as before, and each restart gets a full limit`() {
        val controller = hangingController(failing = 2)
        start(controller)

        // Two silent restarts after the dropped connections, as PlaybackRecovery decides; the third hangs.
        players.until { players.played.size == 3 }
        // Past where the first stream's limit would have run out, had its error not ended it.
        players.advance(LIMIT_MILLIS - MARGIN_MILLIS)
        assertThat(controller.state.value).isEqualTo(PlayerController.State.Buffering("Film"))

        players.advance(MARGIN_MILLIS)
        assertThat(controller.state.value).isEqualTo(
            PlayerController.State.Error(title = "Film", reason = TIMED_OUT, canRetry = true),
        )
    }

    @Test
    fun `time behind another app doesn't count, and coming back gives a film a full limit`() {
        val controller = hangingController()
        start(controller)
        players.advance(LIMIT_MILLIS - MARGIN_MILLIS)

        controller.behindAnotherApp = true
        players.advance(LIMIT_MILLIS * 10)
        assertThat(controller.state.value).isEqualTo(PlayerController.State.Buffering("Film"))

        controller.behindAnotherApp = false
        players.advance(LIMIT_MILLIS - MARGIN_MILLIS)
        assertThat(controller.state.value).isEqualTo(PlayerController.State.Buffering("Film"))
        players.advance(MARGIN_MILLIS)
        assertThat(controller.state.value).isEqualTo(
            PlayerController.State.Error(title = "Film", reason = TIMED_OUT, canRetry = true),
        )
    }

    @Test
    fun `stopping ends the wait`() {
        val controller = hangingController()
        start(controller)

        controller.stop()
        players.advance(LIMIT_MILLIS * 2)

        assertThat(controller.state.value).isEqualTo(PlayerController.State.Idle)
    }

    @Test
    fun `a recording still being written has no limit, since it waits on the recording, not a provider`() {
        players.liveRecording = true
        val controller = hangingController()
        start(controller)

        players.advance(LIMIT_MILLIS * 2)

        assertThat(controller.state.value).isEqualTo(PlayerController.State.Buffering("Film"))
    }

    @Test
    fun `a NAS recording that never starts says to check the NAS, not the provider`() {
        players.hanging = true
        val controller = players.controller { key ->
            Playable.Ready(
                key,
                "smb://nas.example.com/recordings/film.ts",
                userAgent = "",
                title = "Film",
                isLive = false,
                RecordedOn.NAS,
            )
        }
        start(controller, PlayerController.Request(MediaKey.Recording(1)))

        players.advance(LIMIT_MILLIS)

        assertThat(controller.state.value).isEqualTo(
            PlayerController.State.Error(
                title = "Film",
                reason = ViewerError(
                    "Couldn't reach your NAS. Check it's on and the details in Recording settings are right.",
                    "StartUpLimit",
                ),
                canRetry = true,
            ),
        )
    }

    private fun hangingController(failing: Int = 0): PlayerController {
        players.hanging = true
        return players.controller(failing = failing)
    }

    /**
     * Hands [request] over and waits only until the player has it. Not [TestPlayers.play], which waits
     * for a stream that's ready, and a hanging one never is.
     */
    private fun start(
        controller: PlayerController,
        request: PlayerController.Request = PlayerController.Request(MediaKey.Movie(1)),
    ) {
        val before = players.played.size
        controller.play(request, debounce = false)
        players.until { players.played.size > before }
    }

    private companion object {
        const val LIMIT_MILLIS = 45_000L

        /** More than handing a stream over takes here, so the limit's edges can be told apart. */
        const val MARGIN_MILLIS = 2_000L

        val TIMED_OUT = ViewerError(
            "Your provider stopped responding. It may be busy, so try again in a moment.",
            "StartUpLimit",
        )
    }
}
