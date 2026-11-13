/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import app.opentv.data.model.Channel
import app.opentv.player.PlayerController
import app.opentv.ui.LivePreviewPlayer
import app.opentv.ui.channels.rememberGuidePreviewController
import app.opentv.ui.rememberLivePreviewPlayer
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Both previews read their streams through a client with no overall call timeout, as the full-screen
 * players do, so a channel sent as one endless response isn't cut off after five minutes (#268).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PreviewStreamClientTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the guide preview's stream is never cut off by a call timeout`() {
        lateinit var controller: PlayerController
        compose.setContent { controller = rememberGuidePreviewController() }
        compose.waitForIdle()

        assertThat(controller.httpClient.callTimeoutMillis).isEqualTo(0)
    }

    @Test
    fun `Home's Live Preview stream is never cut off by a call timeout`() {
        lateinit var preview: LivePreviewPlayer
        compose.setContent { preview = rememberLivePreviewPlayer(channel) }
        compose.waitForIdle()

        assertThat(preview.httpClient!!.callTimeoutMillis).isEqualTo(0)
    }

    private val channel = Channel(
        id = 40,
        sourceId = 1,
        streamId = "40",
        name = "Channel 4",
        streamUrl = "https://example.com/40.ts",
        categoryId = null,
        logoUrl = "https://example.com/40.png",
        epgChannelId = null,
        number = null,
    )
}
