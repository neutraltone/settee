/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.RecordingStatus
import app.opentv.testing
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A channel records once at a time, so a double press of Record never opens a second connection (#498). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RecordingEngineStartTest {

    private val fixture = RecordingFixture(ApplicationProvider.getApplicationContext())
    private val recordings = fixture.recordings
    private val engine = fixture.engine
    private val bbcOne = fixture.bbcOne

    @After
    fun close() = fixture.close()

    @Test
    fun `pressing Record twice at once records the channel once`() = testing {
        val ids = listOf(async { engine.startChannel(bbcOne) }, async { engine.startChannel(bbcOne) }).awaitAll()

        val active = recordings.active()
        assertThat(active).hasSize(1)
        assertThat(ids).containsExactly(active.single().id, active.single().id)
    }

    @Test
    fun `recording another Quality of a channel that's recording records it as well`() = testing {
        val hd = engine.startChannel(bbcOne)
        val sd = engine.startChannel(bbcOne.copy(id = 8, streamId = "8", name = "UK| BBC One SD", qualityRank = 100))

        assertThat(sd).isNotEqualTo(hd)
        assertThat(recordings.active().map { it.id }).containsExactly(hd, sd)
    }

    @Test
    fun `recording a channel again once its recording has finished starts a new one`() = testing {
        val first = engine.startChannel(bbcOne)
        recordings.setStatus(first, RecordingStatus.COMPLETED)

        val second = engine.startChannel(bbcOne)

        assertThat(second).isNotEqualTo(first)
        assertThat(recordings.active().map { it.id }).containsExactly(second)
    }
}
