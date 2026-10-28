/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.ui.Tab
import app.opentv.ui.visibleTabs
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MainTabsTest {

    @Test
    fun `Home is the first tab, so the app opens on it`() {
        assertThat(visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true))
            .containsExactly(Tab.HOME, Tab.LIVE, Tab.MOVIES, Tab.SHOWS, Tab.RECORDINGS)
            .inOrder()
    }

    @Test
    fun `Home stays first with every content type switched off`() {
        assertThat(visibleTabs(liveEnabled = false, moviesEnabled = false, seriesEnabled = false))
            .containsExactly(Tab.HOME, Tab.RECORDINGS)
            .inOrder()
    }
}
