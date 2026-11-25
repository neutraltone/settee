/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.ServerProblem
import app.opentv.sync.SyncServer
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How long Settings > Devices' Share keeps a code open for another TV to type. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncServerTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val server = SyncServer(scope, timerDispatcher = dispatcher)

    @After
    fun close() {
        server.stop()
    }

    private fun share() {
        server.start("{}")
        assumeTrue("needs a private LAN address to serve on", server.state.value is SyncServer.State.Sharing)
    }

    private fun advanceMinutes(minutes: Double) {
        scope.testScheduler.advanceTimeBy((minutes * TimeUnit.MINUTES.toMillis(1)).toLong())
        scope.testScheduler.runCurrent()
    }

    @Test
    fun `a share left open times out after five minutes`() {
        share()

        advanceMinutes(4.9)
        assertThat(server.state.value).isInstanceOf(SyncServer.State.Sharing::class.java)

        advanceMinutes(0.2)
        assertThat(server.state.value).isEqualTo(SyncServer.State.Failed(ServerProblem.TIMED_OUT))
    }

    @Test
    fun `sharing again after stopping gets its own full five minutes`() {
        share()
        server.stop()
        advanceMinutes(1.0)
        share()

        // Past the first share's five minutes: that share's timer must not end this one.
        advanceMinutes(4.5)
        assertThat(server.state.value).isInstanceOf(SyncServer.State.Sharing::class.java)

        advanceMinutes(0.6)
        assertThat(server.state.value).isEqualTo(SyncServer.State.Failed(ServerProblem.TIMED_OUT))
    }
}
