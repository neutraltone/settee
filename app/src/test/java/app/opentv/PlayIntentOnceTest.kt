/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Intent
import app.opentv.core.PlayRequests
import app.opentv.core.RecordingSignals
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
    private val recordingsAsked = mutableListOf<Long>()

    @Before
    fun recordRequests() {
        PlayRequests.consume()
        RecordingSignals.consumeWatch()
        recording.launch { PlayRequests.channelId.filterNotNull().collect { channelsAsked += it } }
        recording.launch { RecordingSignals.watch.filterNotNull().collect { recordingsAsked += it.recordingId } }
    }

    @After
    fun stopRecording() {
        recording.cancel()
        PlayRequests.consume()
        RecordingSignals.consumeWatch()
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

    @Test
    fun `changing the language after a recording took over the screen doesn't open it again`() {
        val started = Intent(launcher).putExtra(MainActivity.EXTRA_WATCH_RECORDING, 4L)
        val screen = Robolectric.buildActivity(MainActivity::class.java, started).setup()
        assertThat(recordingsAsked).containsExactly(4L)
        RecordingSignals.consumeWatch()

        screen.recreate()

        assertThat(recordingsAsked).containsExactly(4L)
    }

    @Test
    fun `a reminder tapped while the app is open opens its channel once, and not again on a language change`() {
        val screen = Robolectric.buildActivity(MainActivity::class.java, launcher).setup()
        assertThat(channelsAsked).isEmpty()

        screen.newIntent(Intent(launcher).putExtra(MainActivity.EXTRA_PLAY_CHANNEL, 9L))
        assertThat(channelsAsked).containsExactly(9L)
        PlayRequests.consume()
        screen.recreate()

        assertThat(channelsAsked).containsExactly(9L)
    }

    @Test
    fun `reopening from Recents an app a reminder once opened doesn't open its channel`() {
        // Android hands back the intent the task started with, extras and all, marked as from history.
        val fromRecents = Intent(launcher)
            .putExtra(MainActivity.EXTRA_PLAY_CHANNEL, 7L)
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)

        Robolectric.buildActivity(MainActivity::class.java, fromRecents).setup()

        assertThat(channelsAsked).isEmpty()
    }
}
