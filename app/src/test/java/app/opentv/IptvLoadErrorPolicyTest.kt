/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.exoplayer.upstream.Loader
import app.opentv.player.IptvLoadErrorPolicy
import com.google.common.truth.Truth.assertThat
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only failures that can fix themselves are retried, so a stream that can't play says so in seconds (#255). */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class IptvLoadErrorPolicyTest {

    private val policy = IptvLoadErrorPolicy()
    private val spec = DataSpec(Uri.parse("http://example.com/live/u/p/1.ts"))

    @Test
    fun `failures that can never succeed aren't retried`() {
        val hopeless = listOf(
            ParserException.createForMalformedContainer("an HTML page, not video", null),
            UnrecognizedInputFormatException("an HTML page, not video", Uri.EMPTY),
            FileNotFoundException(),
            HttpDataSource.CleartextNotPermittedException(IOException(), spec),
            Loader.UnexpectedLoaderException(OutOfMemoryError("Failed to allocate")),
        )

        hopeless.forEach { exception ->
            assertThat(policy.getRetryDelayMsFor(info(exception, errorCount = 1)))
                .isEqualTo(C.TIME_UNSET)
        }
    }

    @Test
    fun `a connection that times out backs off from half a second to eight, then gives up`() {
        val timeout = HttpDataSource.HttpDataSourceException.createForIOException(
            SocketTimeoutException(),
            spec,
            HttpDataSource.HttpDataSourceException.TYPE_OPEN,
        )

        val delays = (1..6).map { policy.getRetryDelayMsFor(info(timeout, errorCount = it)) }

        assertThat(delays).containsExactly(500L, 1_000L, 2_000L, 4_000L, 8_000L, C.TIME_UNSET).inOrder()
    }

    @Test
    fun `a busy provider's statuses back off, and ones that won't fix themselves aren't retried`() {
        listOf(403, 429, 503).forEach { code ->
            assertThat(policy.getRetryDelayMsFor(info(status(code), errorCount = 1))).isEqualTo(500L)
        }
        listOf(401, 404, 410).forEach { code ->
            assertThat(policy.getRetryDelayMsFor(info(status(code), errorCount = 1))).isEqualTo(C.TIME_UNSET)
        }
    }

    private fun status(code: Int) =
        HttpDataSource.InvalidResponseCodeException(code, null, IOException(), emptyMap(), spec, ByteArray(0))

    private fun info(exception: IOException, errorCount: Int) = LoadErrorInfo(
        LoadEventInfo(LoadEventInfo.getNewId(), spec, 0L),
        MediaLoadData(C.DATA_TYPE_MEDIA),
        exception,
        errorCount,
    )
}
