/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppDispatchers
import app.opentv.core.ErrorWording
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.CatalogRepository.SyncResult
import app.opentv.data.repo.SourceRepository
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * A portal that can't be reached, or that answers with server trouble, says so (#468), rather than
 * telling the viewer their MAC was refused or that their account has no channels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StalkerConnectionTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val test = TestCatalog(context)

    @After
    fun close() = test.close()

    @Test
    fun `a portal whose address can't be found says it can't be reached, not that the MAC was refused`() = testing {
        test.panel.replies["handshake"] = "!host"

        val reason = failedSync()

        assertThat(reason.sentence)
            .isEqualTo("Can't reach your provider. Check this device is online and the address is right.")
        assertThat(reason.detail).isEqualTo("UnknownHost")
    }

    @Test
    fun `a portal that stops responding says so`() = testing {
        test.panel.replies["handshake"] = "!timeout"

        val reason = failedSync()

        assertThat(reason.sentence)
            .isEqualTo("Your provider stopped responding. It may be busy, so try again in a moment.")
        assertThat(reason.detail).isEqualTo("SocketTimeout")
    }

    @Test
    fun `a portal answering every handshake with server trouble says the trouble is theirs`() = testing {
        test.panel.replies["handshake"] = "!503"

        assertThat(failedSync().sentence)
            .isEqualTo("Your provider is having problems. That's at their end, so try again later.")
    }

    @Test
    fun `a channel list that fails after the handshake is server trouble, not an account with no channels`() = testing {
        test.panel.replies["handshake"] = """{"js": {"token": "t"}}"""
        test.panel.replies["get_all_channels"] = "!503"

        val reason = failedSync()

        assertThat(reason.sentence)
            .isEqualTo("Your provider is having problems. That's at their end, so try again later.")
        assertThat(reason.detail).isEqualTo("HTTP 503")
    }

    @Test
    fun `a portal that answers without a token is still a refused MAC`() = testing {
        test.panel.replies["handshake"] = """{"js": {}}"""

        assertThat(failedSync().sentence)
            .isEqualTo("Your provider didn't accept these details. Check your provider's details in Settings.")
    }

    @Test
    fun `an address with no portal at any of its paths is still refused details`() = testing {
        test.panel.replies["handshake"] = "!404"

        assertThat(failedSync().sentence)
            .isEqualTo("Your provider didn't accept these details. Check your provider's details in Settings.")
    }

    @Test
    fun `Test connection on a portal that can't be reached says so`() = testing {
        test.panel.replies["handshake"] = "!host"
        val dispatchers = AppDispatchers()
        val sources = SourceRepository(
            test.db.sources(),
            XtreamApi(test.panel.http, dispatchers = dispatchers),
            StalkerApi(test.panel.http, dispatchers = dispatchers),
            test.panel.http,
            test.settings,
            dispatchers,
        )

        val error = sources.test(STALKER_SOURCE).exceptionOrNull()!!

        assertThat(ErrorWording(context, onTelevision = true).ofProvider(error, R.string.error_test_failed).sentence)
            .isEqualTo("Can't reach your provider. Check this TV is online and the address is right.")
    }

    @Test
    fun `a portal that can't be reached logs only what kind of failure it was, never its address or MAC`() = testing {
        test.panel.replies["handshake"] = "!host"
        ShadowLog.clear()

        failedSync()

        val logged = ShadowLog.getLogs().filter { it.type >= Log.WARN }
        assertThat(logged).isNotEmpty()
        logged.forEach { entry ->
            val text = entry.msg + generateSequence(entry.throwable) { it.cause }.joinToString { "${it.message}" }
            assertThat(text).doesNotContain("example.com")
            assertThat(text).doesNotContain("00:1A:79")
        }
    }

    private suspend fun failedSync() = test.db.sources().insert(STALKER_SOURCE).let {
        val result = test.catalog.fetchLive(STALKER_SOURCE, 0L)
        assertThat(result).isInstanceOf(SyncResult.Failed::class.java)
        (result as SyncResult.Failed).reason
    }
}
