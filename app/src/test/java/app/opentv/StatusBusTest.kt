/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.StatusBus
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/** The app has one status line, so a job that stops takes down its own line and nobody else's (#359). */
class StatusBusTest {

    @After
    fun tearDown() = StatusBus.set(null)

    @Test
    fun `a job's line goes, bar and all, when it clears it`() {
        StatusBus.set("Loading 20 channels", 0.4f)

        StatusBus.clear("Loading 20 channels")

        assertThat(StatusBus.message.value).isNull()
        assertThat(StatusBus.progress.value).isNull()
    }

    @Test
    fun `a line another job put up since stays, bar and all`() {
        StatusBus.set("Loading 20 channels", 0.4f)
        StatusBus.set("Refreshing the TV guide", 0.7f)

        StatusBus.clear("Loading 20 channels")

        assertThat(StatusBus.message.value).isEqualTo("Refreshing the TV guide")
        assertThat(StatusBus.progress.value).isEqualTo(0.7f)
    }
}
