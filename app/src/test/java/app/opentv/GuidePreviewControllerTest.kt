/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.player.PlayerController
import app.opentv.ui.channels.rememberGuidePreviewController
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The guide's preview has a sound track only while its sound setting is on (#110). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GuidePreviewControllerTest {

    @get:Rule
    val compose = createComposeRule()

    private val settings = ServiceLocator.get(ApplicationProvider.getApplicationContext<Application>()).settings

    // The settings are process-wide, so they outlive each test.
    @After
    fun restore() = settings.setGuidePreviewSound(false)

    private fun show(): PlayerController {
        lateinit var controller: PlayerController
        compose.setContent { controller = rememberGuidePreviewController() }
        compose.waitForIdle()
        return controller
    }

    @Test
    fun `with the sound setting off the guide preview has no sound`() {
        settings.setGuidePreviewSound(false)

        assertThat(show().audioEnabled).isFalse()
    }

    @Test
    fun `with the sound setting on the guide preview has sound`() {
        settings.setGuidePreviewSound(true)

        assertThat(show().audioEnabled).isTrue()
    }

    @Test
    fun `toggling the sound setting takes effect`() {
        settings.setGuidePreviewSound(false)
        val controller = show()

        settings.setGuidePreviewSound(true)
        compose.waitForIdle()
        assertThat(controller.audioEnabled).isTrue()

        settings.setGuidePreviewSound(false)
        compose.waitForIdle()
        assertThat(controller.audioEnabled).isFalse()
    }
}
