/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.player.DecoderSize
import app.opentv.player.PlayerController
import app.opentv.player.PlayerController.Quality
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Channel Quality chosen by default that this device turns out not to play moves on to the next, best
 * first and once through. One the viewer picked goes straight to the sentence (#184).
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class QualityStepDownTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val players = TestPlayers(context)

    init {
        players.decoders = FakeDecoders(MimeTypes.VIDEO_H264 to DecoderSize(1920, 1088))
    }

    private val hd = Quality(MediaKey.Live(HD), "HD")
    private val sd = Quality(MediaKey.Live(SD), "SD")

    @After
    fun close() = players.close()

    private fun streams(vararg sizes: Pair<Long, Int>) {
        sizes.forEach { (id, height) -> players.formatsAt[urlOf(id)] = listOf(h264(height * 16 / 9, height)) }
    }

    private fun h264(width: Int, height: Int): Format =
        Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(width).setHeight(height).build()

    private fun controller() = players.controller { key ->
        Playable.Ready(key, urlOf((key as MediaKey.Live).channelId), userAgent = "", title = "News", isLive = true)
    }

    private fun PlayerController.startAt(id: Long, label: String, vararg lower: Quality) =
        play(PlayerController.Request(MediaKey.Live(id), label = label, lower = lower.toList()), debounce = false)

    @Test
    fun `a refused Quality moves on to the next and says so, with no error on the way`() {
        streams(UHD to 2160, HD to 1080)
        val controller = controller()
        val states = mutableListOf<PlayerController.State>()
        players.scope.launch { controller.state.collect { states += it } }

        controller.startAt(UHD, "4K", hd, sd)
        players.until { controller.player.playbackState == Player.STATE_READY }

        assertThat(players.played).containsExactly(urlOf(UHD), urlOf(HD)).inOrder()
        assertThat(controller.state.value).isInstanceOf(PlayerController.State.Playing::class.java)
        assertThat(states.filterIsInstance<PlayerController.State.Error>()).isEmpty()
        assertThat(controller.steppedDownTo.value).isEqualTo(MediaKey.Live(HD))
        assertThat(controller.notice.value).isEqualTo("This device can't play 4K. Playing HD instead.")
    }

    @Test
    fun `a Quality with no name steps down from a named one by saying it's lower`() {
        streams(UHD to 2160, HD to 1080)
        val controller = controller()

        controller.startAt(UHD, "4K", Quality(MediaKey.Live(HD), ""))
        players.until { controller.notice.value != null }

        assertThat(controller.notice.value).isEqualTo("This device can't play 4K. Playing a lower quality instead.")
    }

    @Test
    fun `playing in place of a Quality this device won't try says so, without trying it`() {
        streams(HD to 1080)
        val controller = controller()

        controller.play(PlayerController.Request(MediaKey.Live(HD), label = "HD"), debounce = false, skipped = "4K")
        players.until { controller.player.playbackState == Player.STATE_READY }

        assertThat(players.played).containsExactly(urlOf(HD))
        assertThat(controller.notice.value).isEqualTo("This device can't play 4K. Playing HD instead.")
    }

    @Test
    fun `the message goes after about 5 seconds`() {
        streams(UHD to 2160, HD to 1080)
        val controller = controller()
        controller.startAt(UHD, "4K", hd)
        players.until { controller.notice.value != null }

        players.advance(4_500)
        assertThat(controller.notice.value).isNotNull()

        players.advance(600)
        assertThat(controller.notice.value).isNull()
    }

    @Test
    fun `it keeps moving until one plays`() {
        streams(EIGHT_K to 4320, UHD to 2160, HD to 1080)
        val controller = controller()

        controller.startAt(EIGHT_K, "8K", Quality(MediaKey.Live(UHD), "4K"), hd)
        players.until { controller.player.playbackState == Player.STATE_READY }

        assertThat(players.played).containsExactly(urlOf(EIGHT_K), urlOf(UHD), urlOf(HD)).inOrder()
        assertThat(controller.notice.value).isEqualTo("This device can't play 4K. Playing HD instead.")
    }

    @Test
    fun `one the viewer picked goes straight to the sentence`() {
        streams(UHD to 2160, HD to 1080)
        val controller = controller()

        controller.startAt(UHD, "4K")
        players.until { controller.state.value is PlayerController.State.Error }

        assertThat(players.played).containsExactly(urlOf(UHD))
        assertThat(controller.notice.value).isNull()
        assertThat(controller.steppedDownTo.value).isNull()
    }

    @Test
    fun `running out of Qualities ends on the sentence, with no Retry`() {
        streams(EIGHT_K to 4320, UHD to 2160)
        val controller = controller()

        controller.startAt(EIGHT_K, "8K", Quality(MediaKey.Live(UHD), "4K"))
        players.until { controller.state.value is PlayerController.State.Error }

        val error = controller.state.value as PlayerController.State.Error
        assertThat(error.message).isEqualTo("This device can't play this video (H.264, 3840x2160).")
        assertThat(error.canRetry).isFalse()
        assertThat(players.played).hasSize(2)
        // "Playing 4K instead" would contradict the sentence just shown.
        assertThat(controller.notice.value).isNull()
    }

    @Test
    fun `a Quality chosen by default that shows no first frame steps down too`() {
        streams(FHD to 1080, HD to 720)
        players.showPictures = false
        val controller = controller()
        controller.startAt(FHD, "FHD", hd)
        players.until { controller.player.playbackState == Player.STATE_READY }

        players.advance(FIRST_FRAME_LIMIT_MILLIS)

        assertThat(players.played).containsExactly(urlOf(FHD), urlOf(HD)).inOrder()
        assertThat(controller.notice.value).isEqualTo("This device can't play FHD. Playing HD instead.")
    }

    @Test
    fun `a Quality the viewer picked that shows no first frame goes to the error, with Retry`() {
        streams(HD to 720)
        players.showPictures = false
        val controller = controller()
        controller.startAt(HD, "HD")
        players.until { controller.player.playbackState == Player.STATE_READY }

        players.advance(FIRST_FRAME_LIMIT_MILLIS)

        assertThat((controller.state.value as PlayerController.State.Error).canRetry).isTrue()
        assertThat(players.played).hasSize(1)
    }

    @Test
    fun `a Quality with no name still gets a sentence`() {
        streams(UHD to 2160, HD to 1080)
        val controller = controller()

        controller.startAt(UHD, "", Quality(MediaKey.Live(HD), ""))
        players.until { controller.notice.value != null }

        assertThat(controller.notice.value).isEqualTo("This device can't play that quality. Playing another instead.")
    }

    @Test
    fun `changing channel afterwards forgets the step down and the message`() {
        streams(UHD to 2160, HD to 1080, OTHER to 720)
        val controller = controller()
        controller.startAt(UHD, "4K", hd)
        players.until { controller.notice.value != null }

        controller.startAt(OTHER, "HD")

        assertThat(controller.notice.value).isNull()
        assertThat(controller.steppedDownTo.value).isNull()
    }

    private companion object {
        const val EIGHT_K = 8L
        const val UHD = 4L
        const val FHD = 3L
        const val HD = 2L
        const val SD = 1L
        const val OTHER = 9L
        const val FIRST_FRAME_LIMIT_MILLIS = 15_000L

        fun urlOf(id: Long) = "https://example.com/$id"
    }
}
