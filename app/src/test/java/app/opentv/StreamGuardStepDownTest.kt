/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.MediaKey
import app.opentv.player.PlayerController
import app.opentv.player.StreamGuard
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The notice over the picture when a Quality steps down by itself names the hardware the way every other
 * sentence on the player does: "this TV" on a TV, "this device" elsewhere (#481).
 * [QualityStepDownTest] checks the stepping itself through the player.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StreamGuardStepDownTest {

    private val scope = TestScope()

    @After
    fun close() = scope.cancel()

    private fun guard(onTelevision: Boolean) = StreamGuard(
        ApplicationProvider.getApplicationContext<Context>(),
        scope,
        ANY_SIZE_DECODERS,
        moveDown = { null },
        onTelevision = onTelevision,
        onCantPlay = { _, _ -> },
    )

    private fun noticeFor(onTelevision: Boolean, from: String, to: String): String? {
        val guard = guard(onTelevision)
        guard.steppedDown(from, PlayerController.Quality(MediaKey.Live(2), to))
        return guard.notice.value
    }

    @Test
    fun `on a TV, a step down to a named Quality says this TV`() {
        assertThat(noticeFor(onTelevision = true, "4K", "HD")).isEqualTo("This TV can't play 4K. Playing HD instead.")
    }

    @Test
    fun `on a TV, a step down to a Quality with no name says this TV`() {
        assertThat(noticeFor(onTelevision = true, "4K", ""))
            .isEqualTo("This TV can't play 4K. Playing a lower quality instead.")
    }

    @Test
    fun `on a TV, a step down from a Quality with no name says this TV`() {
        assertThat(noticeFor(onTelevision = true, "", "HD"))
            .isEqualTo("This TV can't play that quality. Playing another instead.")
    }

    @Test
    fun `on a phone or tablet, the same notices say this device`() {
        assertThat(noticeFor(onTelevision = false, "4K", "HD"))
            .isEqualTo("This device can't play 4K. Playing HD instead.")
        assertThat(noticeFor(onTelevision = false, "4K", ""))
            .isEqualTo("This device can't play 4K. Playing a lower quality instead.")
        assertThat(noticeFor(onTelevision = false, "", "HD"))
            .isEqualTo("This device can't play that quality. Playing another instead.")
    }

    @Test
    fun `a notice said before the stream starts stays up until it does, then for its usual time`() {
        // A torrent knows its tracks long before its first frame: the AC3 a stereo-only TV can't play was
        // said and gone behind Loading before the film began, so the viewer heard nothing and was told nothing.
        val guard = guard(onTelevision = true)
        guard.started(limitStartUp = false)

        guard.steppedDown("4K", PlayerController.Quality(MediaKey.Live(2), "HD"))
        scope.advanceTimeBy(60_000)
        assertThat(guard.notice.value).isNotNull()

        guard.arrived()
        scope.advanceTimeBy(4_999)
        assertThat(guard.notice.value).isNotNull()
        scope.advanceTimeBy(2)
        assertThat(guard.notice.value).isNull()
    }

    @Test
    fun `a notice said once the stream is playing goes after its usual time`() {
        val guard = guard(onTelevision = true)
        guard.started(limitStartUp = false)
        guard.arrived()

        guard.steppedDown("4K", PlayerController.Quality(MediaKey.Live(2), "HD"))
        scope.advanceTimeBy(5_001)

        assertThat(guard.notice.value).isNull()
    }
}
