/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.ui.FirstScreen
import app.opentv.ui.firstScreen
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which screen the app opens on: setup, Who's watching? or Home. */
class FirstScreenTest {

    @Test
    fun `a cold start with two Profiles asks who's watching`() {
        val screen = firstScreen(hasProvider = true, profileCount = 2, askedThisProcess = false, launchedToPlay = false)

        assertThat(screen).isEqualTo(FirstScreen.WHOS_WATCHING)
    }

    @Test
    fun `with one Profile there is nobody else to be, so it opens on Home`() {
        val screen = firstScreen(hasProvider = true, profileCount = 1, askedThisProcess = false, launchedToPlay = false)

        assertThat(screen).isEqualTo(FirstScreen.HOME)
    }

    @Test
    fun `with no provider yet it opens on setup, however many Profiles there are`() {
        val screen =
            firstScreen(hasProvider = false, profileCount = 2, askedThisProcess = false, launchedToPlay = false)

        assertThat(screen).isEqualTo(FirstScreen.ADD_SOURCE)
    }

    @Test
    fun `coming back to an app that is still running carries on as whoever was watching`() {
        val screen = firstScreen(hasProvider = true, profileCount = 2, askedThisProcess = true, launchedToPlay = false)

        assertThat(screen).isEqualTo(FirstScreen.HOME)
    }

    @Test
    fun `a reminder or recording that opens the app plays without asking`() {
        val screen = firstScreen(hasProvider = true, profileCount = 2, askedThisProcess = false, launchedToPlay = true)

        assertThat(screen).isEqualTo(FirstScreen.HOME)
    }
}
