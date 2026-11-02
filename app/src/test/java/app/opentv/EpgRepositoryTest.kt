/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Programme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EpgRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val catalogue = TestCatalog(context)
    private val guide = catalogue.guide

    @After
    fun close() = catalogue.close()

    private suspend fun programme(title: String, start: Long, end: Long) = catalogue.db.programmes().upsertAll(
        listOf(Programme(feedId = 1, epgChannelId = "itv", startUtcMillis = start, endUtcMillis = end, title = title)),
    )

    @Test
    fun `the programme on now is the one that has started and not ended`() = runTest {
        programme("Early", start = 0, end = 1_000)
        programme("Late", start = 1_000, end = 2_000)

        assertThat(guide.programmeOnNow("itv", nowUtcMillis = 500)?.title).isEqualTo("Early")
        assertThat(guide.programmeOnNow("itv", nowUtcMillis = 1_000)?.title).isEqualTo("Late")
        assertThat(guide.programmeOnNow("itv", nowUtcMillis = 2_000)).isNull()
    }

    @Test
    fun `a programme that hasn't started yet isn't on now`() = runTest {
        programme("Later", start = 1_000, end = 2_000)

        assertThat(guide.programmeOnNow("itv", nowUtcMillis = 999)).isNull()
    }
}
