/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ViewerError
import app.opentv.data.repo.MediaKey
import app.opentv.player.PlayerController
import app.opentv.player.StreamGuard
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The start-up limit (#463): a stream that never gets as far as playing, such as one from a panel that
 * takes the connection and then sends nothing, stops with a sentence and Try again instead of loading
 * for ten minutes. [StartUpLimitTest] checks the same through the player.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StreamGuardStartUpTest {

    private val scope = TestScope()
    private val gaveUp = mutableListOf<Pair<ViewerError, Boolean>>()
    private var lowerQualitiesAsked = 0

    /** The Quality [StreamGuard] steps down to when it asks; null when there's none left. */
    private var lower: StreamGuard.Step? = StreamGuard.Step("HD", PlayerController.Quality(MediaKey.Movie(2), "SD"))

    private val guard = StreamGuard(
        ApplicationProvider.getApplicationContext<Context>(),
        scope,
        ANY_SIZE_DECODERS,
        moveDown = {
            lowerQualitiesAsked++
            lower
        },
        onTelevision = true,
        onCantPlay = { reason, canRetry -> gaveUp += reason to canRetry },
    )

    @After
    fun close() = scope.cancel()

    private fun decoderStarted() = guard.firstFrameListener.onVideoDecoderInitialized(
        AnalyticsListener.EventTime(0L, Timeline.EMPTY, 0, null, 0L, Timeline.EMPTY, 0, null, 0L, 0L),
        "decoder",
        0L,
        0L,
    )

    private fun pass(millis: Long) {
        scope.advanceTimeBy(millis)
        scope.runCurrent()
    }

    @Test
    fun `a stream not playing 45 seconds after it was handed over stops with the timed-out sentence and Try again`() {
        guard.started(limitStartUp = true)

        pass(LIMIT_MILLIS - 1)
        assertThat(gaveUp).isEmpty()

        pass(1)
        assertThat(gaveUp).containsExactly(TIMED_OUT to true)
    }

    @Test
    fun `reaching the limit asks for no lower Quality`() {
        guard.started(limitStartUp = true)

        pass(LIMIT_MILLIS)

        assertThat(lowerQualitiesAsked).isEqualTo(0)
        assertThat(guard.steppedDownTo.value).isNull()
    }

    @Test
    fun `a stream that played or ended in time is left alone, however long it waits afterwards`() {
        guard.started(limitStartUp = true)
        pass(LIMIT_MILLIS - 1)

        guard.arrived()
        pass(LIMIT_MILLIS * 10)

        assertThat(gaveUp).isEmpty()
    }

    @Test
    fun `a stream left before the limit gets nothing, and the next one has a full limit of its own`() {
        guard.started(limitStartUp = true)
        pass(LIMIT_MILLIS - MARGIN_MILLIS)

        guard.leaving()
        guard.started(limitStartUp = true)
        pass(LIMIT_MILLIS - 1)
        assertThat(gaveUp).isEmpty()

        pass(1)
        assertThat(gaveUp).hasSize(1)
    }

    @Test
    fun `releasing ends the wait`() {
        guard.started(limitStartUp = true)

        guard.release()
        pass(LIMIT_MILLIS * 2)

        assertThat(gaveUp).isEmpty()
    }

    @Test
    fun `time held doesn't count, and the stream has a full limit once it's let go`() {
        guard.started(limitStartUp = true)
        pass(LIMIT_MILLIS - MARGIN_MILLIS)

        guard.held = true
        pass(LIMIT_MILLIS * 10)
        assertThat(gaveUp).isEmpty()

        guard.held = false
        pass(LIMIT_MILLIS - 1)
        assertThat(gaveUp).isEmpty()

        pass(1)
        assertThat(gaveUp).hasSize(1)
    }

    @Test
    fun `a stream handed over while held waits for the hold to end before its limit starts`() {
        guard.held = true
        guard.started(limitStartUp = true)
        pass(LIMIT_MILLIS * 10)
        assertThat(gaveUp).isEmpty()

        guard.held = false
        pass(LIMIT_MILLIS)
        assertThat(gaveUp).hasSize(1)
    }

    @Test
    fun `a stream that played while held is left alone once it's let go`() {
        guard.started(limitStartUp = true)
        guard.held = true
        guard.arrived()

        guard.held = false
        pass(LIMIT_MILLIS * 2)

        assertThat(gaveUp).isEmpty()
    }

    @Test
    fun `a stream given up on for showing no frame isn't then given up on again at the start-up limit`() {
        lower = null
        guard.started(limitStartUp = true)
        decoderStarted()

        pass(FIRST_FRAME_LIMIT_MILLIS)
        assertThat(gaveUp).hasSize(1)

        pass(LIMIT_MILLIS * 2)
        assertThat(gaveUp).hasSize(1)
    }

    @Test
    fun `a stream given up on at the start-up limit isn't then stepped down or given up on for showing no frame`() {
        guard.started(limitStartUp = true)
        pass(LIMIT_MILLIS - MARGIN_MILLIS)
        decoderStarted()

        pass(MARGIN_MILLIS)
        assertThat(gaveUp).containsExactly(TIMED_OUT to true)

        pass(FIRST_FRAME_LIMIT_MILLIS * 2)
        assertThat(gaveUp).containsExactly(TIMED_OUT to true)
        assertThat(lowerQualitiesAsked).isEqualTo(0)
    }

    private companion object {
        const val LIMIT_MILLIS = 45_000L
        const val FIRST_FRAME_LIMIT_MILLIS = 15_000L
        const val MARGIN_MILLIS = 2_000L

        val TIMED_OUT = ViewerError(
            "Your provider stopped responding. It may be busy, so try again in a moment.",
            "StartUpLimit",
        )
    }
}
