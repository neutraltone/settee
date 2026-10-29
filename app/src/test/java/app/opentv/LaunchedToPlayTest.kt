/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Whether the app was opened to play something, which skips Who's watching?. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LaunchedToPlayTest {

    private val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Test
    fun `opening from the launcher is not a launch to play`() {
        assertThat(launchedToPlay(launcher, restored = false)).isFalse()
    }

    @Test
    fun `a tapped reminder opens the app to play its channel`() {
        val reminder = Intent(launcher).putExtra(MainActivity.EXTRA_PLAY_CHANNEL, 7L)

        assertThat(launchedToPlay(reminder, restored = false)).isTrue()
    }

    @Test
    fun `a recording switching the screen to it opens the app to play it`() {
        val recording = Intent(launcher).putExtra(MainActivity.EXTRA_WATCH_RECORDING, 4L)

        assertThat(launchedToPlay(recording, restored = false)).isTrue()
    }

    @Test
    fun `Android restoring an app a reminder once opened asks again, as any restore does`() {
        val reminder = Intent(launcher).putExtra(MainActivity.EXTRA_PLAY_CHANNEL, 7L)

        assertThat(launchedToPlay(reminder, restored = true)).isFalse()
    }
}
