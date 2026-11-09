/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.MediaKey
import app.opentv.player.PlayerController
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A decoder that starts and never shows a picture ends in an error, not an endless spinner (#184). The
 * test's decoder starts and shows nothing when [TestPlayers.showPictures] says so.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FirstFrameLimitTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val players = TestPlayers(context)

    @After
    fun close() = players.close()

    @Test
    fun `no first frame 15 seconds after a decoder starts stops with the format named, and offers Retry`() {
        players.showPictures = false
        val controller = players.controller()
        start(controller)

        players.advance(LIMIT_MILLIS - MARGIN_MILLIS)
        assertThat(controller.state.value).isInstanceOf(PlayerController.State.Playing::class.java)

        players.advance(MARGIN_MILLIS)
        assertThat(controller.state.value).isEqualTo(
            PlayerController.State.Error(
                title = "Film",
                message = "This device couldn't show this video (H.264, 1280x720).",
                canRetry = true,
            ),
        )
        assertThat(controller.player.isPlaying).isFalse()
        assertThat(players.played).hasSize(1)
    }

    @Test
    fun `a stream that shows a frame in time carries on`() {
        val controller = players.controller()
        startUntilFirstFrame(controller)

        players.advance(LIMIT_MILLIS * 2)

        assertThat(controller.state.value).isInstanceOf(PlayerController.State.Playing::class.java)
    }

    @Test
    fun `a stream with no video never starts the clock`() {
        players.formats = SOUND_ONLY
        players.showPictures = false
        val controller = players.controller()
        start(controller)

        players.advance(LIMIT_MILLIS * 2)

        assertThat(controller.state.value).isInstanceOf(PlayerController.State.Playing::class.java)
    }

    @Test
    fun `changing channel starts the clock again`() {
        players.showPictures = false
        val controller = players.controller()
        start(controller, 1)
        players.advance(LIMIT_MILLIS - MARGIN_MILLIS)

        start(controller, 2)
        players.advance(LIMIT_MILLIS - MARGIN_MILLIS)
        assertThat(controller.state.value).isInstanceOf(PlayerController.State.Playing::class.java)

        players.advance(MARGIN_MILLIS)
        assertThat(controller.state.value).isInstanceOf(PlayerController.State.Error::class.java)
    }

    @Test
    fun `a channel changed to after one that showed a picture has the limit too`() {
        val controller = players.controller()
        startUntilFirstFrame(controller)
        players.showPictures = false

        start(controller, 2)
        players.advance(LIMIT_MILLIS)

        assertThat(controller.state.value).isInstanceOf(PlayerController.State.Error::class.java)
    }

    @Test
    fun `Retry starts the stream again with a new limit`() {
        players.showPictures = false
        val controller = players.controller()
        start(controller)
        players.advance(LIMIT_MILLIS)

        controller.retry()
        players.until { controller.state.value is PlayerController.State.Playing }

        assertThat(players.played).hasSize(2)
        players.advance(LIMIT_MILLIS)
        assertThat(controller.state.value).isInstanceOf(PlayerController.State.Error::class.java)
    }

    @Test
    fun `stopping ends the wait`() {
        players.showPictures = false
        val controller = players.controller()
        start(controller)

        controller.stop()
        players.advance(LIMIT_MILLIS * 2)

        assertThat(controller.state.value).isEqualTo(PlayerController.State.Idle)
    }

    /** Not [TestPlayers.play]: waiting that way runs everything delayed, which would use up the limit. */
    private fun start(controller: PlayerController, channel: Long = 1) {
        controller.play(PlayerController.Request(MediaKey.Live(channel)), debounce = false)
        players.until { controller.player.playbackState == Player.STATE_READY }
    }

    private fun startUntilFirstFrame(controller: PlayerController) {
        var shown = false
        controller.player.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                shown = true
            }
        })
        start(controller)
        players.until { shown }
    }

    private companion object {
        const val LIMIT_MILLIS = 15_000L

        /** More than a stream takes to start here, so the limit's edges can be told apart. */
        const val MARGIN_MILLIS = 2_000L
    }
}
