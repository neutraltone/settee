/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.Loader
import app.opentv.player.PlaybackRecovery
import app.opentv.player.Recovery
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Whether a failed stream is restarted by itself, and whether the viewer is offered Retry (#180, #255). */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaybackRecoveryTest {

    @Test
    fun `a stream that never played and won't parse isn't restarted, but the viewer can retry`() {
        PARSING.forEach { code ->
            assertThat(PlaybackRecovery.after(error(code), failuresInARow = 1, hadPlayed = false))
                .isEqualTo(Recovery(canRetry = true, canRestart = false))
        }
    }

    @Test
    fun `a stream that was playing and then won't parse is restarted`() {
        PARSING.forEach { code ->
            assertThat(PlaybackRecovery.after(error(code), failuresInARow = 1, hadPlayed = true))
                .isEqualTo(Recovery(canRetry = true, canRestart = true))
        }
    }

    @Test
    fun `a dropped connection, a bad status or a decoder hiccup is restarted, played or not`() {
        val transient = listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )

        transient.forEach { code ->
            listOf(false, true).forEach { hadPlayed ->
                assertThat(PlaybackRecovery.after(error(code), failuresInARow = 1, hadPlayed = hadPlayed))
                    .isEqualTo(Recovery(canRetry = true, canRestart = true))
            }
        }
    }

    @Test
    fun `a format this device can't decode is neither restarted nor offered again`() {
        assertThat(
            PlaybackRecovery.after(
                error(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
                failuresInARow = 1,
                hadPlayed = false,
            ),
        ).isEqualTo(Recovery(canRetry = false, canRestart = false))
    }

    @Test
    fun `running out of memory isn't restarted, but the viewer can retry`() {
        val outOfMemory = PlaybackException(
            null,
            Loader.UnexpectedLoaderException(OutOfMemoryError("Failed to allocate")),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )

        assertThat(PlaybackRecovery.after(outOfMemory, failuresInARow = 1, hadPlayed = true))
            .isEqualTo(Recovery(canRetry = true, canRestart = false))
    }

    @Test
    fun `the third failure in a row stops restarting`() {
        val dropped = error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        assertThat(PlaybackRecovery.after(dropped, failuresInARow = 2, hadPlayed = false).canRestart).isTrue()
        assertThat(PlaybackRecovery.after(dropped, failuresInARow = 3, hadPlayed = false))
            .isEqualTo(Recovery(canRetry = false, canRestart = false))
    }

    private fun error(code: Int) = PlaybackException(null, null, code)

    private companion object {
        val PARSING = listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
    }
}
