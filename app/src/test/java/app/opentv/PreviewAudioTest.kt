/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.MediaKey
import app.opentv.player.PlayerController
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A muted preview selects no sound track at all: Dolby sent over HDMI can ignore the player's volume (#110). */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PreviewAudioTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val players = TestPlayers(context).apply { formats = PICTURE_AND_SOUND }

    @After
    fun close() = players.close()

    @Test
    fun `a preview plays its picture and selects no sound track`() {
        val controller = players.controller(LIVE, preview = true)

        players.play(controller, isLive = true)

        val tracks = controller.player.currentTracks
        assertThat(tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)).isTrue()
        assertThat(tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)).isFalse()
    }

    @Test
    fun `a full-screen player keeps its sound track`() {
        val controller = players.controller(LIVE)

        players.play(controller, isLive = true)

        assertThat(controller.player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)).isTrue()
    }

    @Test
    fun `turning audio on and off takes effect on the stream already playing`() {
        val controller = players.controller(LIVE, preview = true)
        players.play(controller, isLive = true)

        controller.audioEnabled = true
        TestPlayerRunHelper.run(controller.player).untilPendingCommandsAreFullyHandled()
        assertThat(controller.player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)).isTrue()

        controller.audioEnabled = false
        TestPlayerRunHelper.run(controller.player).untilPendingCommandsAreFullyHandled()
        assertThat(controller.player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)).isFalse()
    }

    @Test
    fun `audio stays off when the preview moves to another channel`() {
        val controller = players.controller(LIVE, preview = true)
        players.play(controller, isLive = true)

        controller.play(PlayerController.Request(MediaKey.Live(2)), debounce = false)
        players.until { players.played.size == 2 }
        players.untilReady(controller.player)

        val tracks = controller.player.currentTracks
        assertThat(tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)).isTrue()
        assertThat(tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)).isFalse()
    }

    @Test
    fun `a radio station in a silent preview shows no error and doesn't keep restarting`() {
        players.formats = SOUND_ONLY
        val controller = players.controller(LIVE, preview = true)

        players.play(controller, isLive = true)
        players.advance(10_000)

        assertThat(controller.state.value).isNotInstanceOf(PlayerController.State.Error::class.java)
        assertThat(players.played).hasSize(1)
    }
}
