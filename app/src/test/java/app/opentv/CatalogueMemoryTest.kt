/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.CatalogRepository.SyncResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A channel list too big for the device ends the sync with a sentence saying so (#261), rather than
 * crashing it or sending the viewer to their provider about channels it did send.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CatalogueMemoryTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())

    @After
    fun close() = test.close()

    @Test
    fun `a portal's channel list too big for memory says so, and the next sync can try again`() = runTest {
        test.db.sources().insert(STALKER_SOURCE)
        test.panel.replies["handshake"] = """{"js": {"token": "t"}}"""
        test.panel.replies["get_all_channels"] = "!oom"

        val result = test.catalog.fetchLive(STALKER_SOURCE, 0L)

        assertThat(result).isInstanceOf(SyncResult.Failed::class.java)
        val reason = (result as SyncResult.Failed).reason
        assertThat(reason.sentence).isEqualTo("Your provider's channel list needs more memory than this device has.")
        assertThat(reason.detail).isEqualTo("OutOfMemory")

        test.panel.replies["get_all_channels"] = """{"js": {"data": [{"id": "9", "name": "News"}]}}"""

        assertThat(test.catalog.fetchLive(STALKER_SOURCE, 0L)).isEqualTo(SyncResult.Success(1, 0, 0))
    }
}
