/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A full-screen player takes the sound from other apps while it plays; a muted preview never does (#356). */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlayerAudioFocusTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
    private val players = TestPlayers(context)

    @After
    fun close() = players.close()

    @Test
    fun `a full-screen player asks for the sound when it plays, as a film`() {
        val controller = players.controller()

        players.play(controller)

        val request = audio.lastAudioFocusRequest?.audioFocusRequest
        assertThat(request).isNotNull()
        assertThat(request!!.focusGain).isEqualTo(AudioManager.AUDIOFOCUS_GAIN)
        assertThat(request.audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_MEDIA)
        assertThat(request.audioAttributes.contentType).isEqualTo(AudioAttributes.CONTENT_TYPE_MOVIE)
        controller.release()
    }

    @Test
    fun `a muted preview never asks for the sound, so music in another app plays on`() {
        val controller = players.controller(LIVE, preview = true)

        players.play(controller, isLive = true)

        assertThat(audio.lastAudioFocusRequest).isNull()
        controller.release()
    }
}
