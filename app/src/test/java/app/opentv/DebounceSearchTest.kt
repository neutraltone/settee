/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.ui.debounceSearch
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DebounceSearchTest {

    @Test
    fun `typing waits for a pause, and an emptied box doesn't`() = runTest {
        val seen = mutableListOf<Pair<String, Long>>()

        flow {
            emit("b")
            delay(100)
            emit("bbc")
            delay(500)
            emit("")
            delay(500)
        }.debounceSearch().collect { seen += it to currentTime }

        assertThat(seen).containsExactly("bbc" to 300L, "" to 600L).inOrder()
    }
}
