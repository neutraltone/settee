/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.media3.common.util.UnstableApi
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

    private val guard = StreamGuard(
        ApplicationProvider.getApplicationContext<Context>(),
        scope,
        ANY_SIZE_DECODERS,
        moveDown = {
            lowerQualitiesAsked++
            StreamGuard.Step("HD", PlayerController.Quality(MediaKey.Movie(2), "SD"))
        },
        onTelevision = true,
        onCantPlay = { reason, canRetry -> gaveUp += reason to canRetry },
    )

    @After
    fun close() = scope.cancel()

    private fun wait(millis: Long) {
        scope.advanceTimeBy(millis)
        scope.runCurrent()
    }

    @Test
    fun `a stream not playing 45 seconds after it was handed over stops with the timed-out sentence and Try again`() {
        guard.started()

        wait(LIMIT_MILLIS - 1)
        assertThat(gaveUp).isEmpty()

        wait(1)
        assertThat(gaveUp).containsExactly(TIMED_OUT to true)
    }

    @Test
    fun `reaching the limit asks for no lower Quality`() {
        guard.started()

        wait(LIMIT_MILLIS)

        assertThat(lowerQualitiesAsked).isEqualTo(0)
        assertThat(guard.steppedDownTo.value).isNull()
    }

    @Test
    fun `a stream that played or ended in time is left alone, however long it waits afterwards`() {
        guard.started()
        wait(LIMIT_MILLIS - 1)

        guard.arrived()
        wait(LIMIT_MILLIS * 10)

        assertThat(gaveUp).isEmpty()
    }

    @Test
    fun `a stream left before the limit gets nothing, and the next one has a full limit of its own`() {
        guard.started()
        wait(LIMIT_MILLIS - MARGIN_MILLIS)

        guard.leaving()
        guard.started()
        wait(LIMIT_MILLIS - 1)
        assertThat(gaveUp).isEmpty()

        wait(1)
        assertThat(gaveUp).hasSize(1)
    }

    @Test
    fun `releasing ends the wait`() {
        guard.started()

        guard.release()
        wait(LIMIT_MILLIS * 2)

        assertThat(gaveUp).isEmpty()
    }

    @Test
    fun `time held doesn't count, and the stream has a full limit once it's let go`() {
        guard.started()
        wait(LIMIT_MILLIS - MARGIN_MILLIS)

        guard.held = true
        wait(LIMIT_MILLIS * 10)
        assertThat(gaveUp).isEmpty()

        guard.held = false
        wait(LIMIT_MILLIS - 1)
        assertThat(gaveUp).isEmpty()

        wait(1)
        assertThat(gaveUp).hasSize(1)
    }

    @Test
    fun `a stream handed over while held waits for the hold to end before its limit starts`() {
        guard.held = true
        guard.started()
        wait(LIMIT_MILLIS * 10)
        assertThat(gaveUp).isEmpty()

        guard.held = false
        wait(LIMIT_MILLIS)
        assertThat(gaveUp).hasSize(1)
    }

    @Test
    fun `a stream that played while held is left alone once it's let go`() {
        guard.started()
        guard.held = true
        guard.arrived()

        guard.held = false
        wait(LIMIT_MILLIS * 2)

        assertThat(gaveUp).isEmpty()
    }

    private companion object {
        const val LIMIT_MILLIS = 45_000L
        const val MARGIN_MILLIS = 2_000L

        val TIMED_OUT = ViewerError(
            "Your provider stopped responding. It may be busy, so try again in a moment.",
            "StartUpLimit",
        )
    }
}
