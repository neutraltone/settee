/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Intent
import app.opentv.core.PlayRequests
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tapped reminder opens its channel once: the screen being made again later (a language change,
 * Android restoring the app) never opens it again (#357).
 *
 * Every request is recorded as it's made, so the nav graph clearing one doesn't hide a second. Each
 * test clears a request itself, as the nav graph does once it has opened it, since asking again for
 * the channel still waiting wouldn't show as a new request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CountingOpenTvApp::class)
class PlayIntentOnceTest {

    private val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    private val recording = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val channelsAsked = mutableListOf<Long>()

    @Before
    fun recordRequests() {
        PlayRequests.consume()
        recording.launch { PlayRequests.channelId.filterNotNull().collect { channelsAsked += it } }
    }

    @After
    fun stopRecording() {
        recording.cancel()
        PlayRequests.consume()
    }

    @Test
    fun `changing the language after a reminder opened its channel doesn't open it again`() {
        val reminder = Intent(launcher).putExtra(MainActivity.EXTRA_PLAY_CHANNEL, 7L)
        val screen = Robolectric.buildActivity(MainActivity::class.java, reminder).setup()
        assertThat(channelsAsked).containsExactly(7L)
        PlayRequests.consume()

        screen.recreate()

        assertThat(channelsAsked).containsExactly(7L)
    }
}
