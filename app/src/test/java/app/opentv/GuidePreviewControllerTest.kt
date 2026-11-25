/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.opentv.data.model.Channel
import app.opentv.data.model.GuideProgramme
import app.opentv.data.repo.LineupChannel
import app.opentv.player.PlayerController
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.channels.GuideBand
import app.opentv.ui.channels.rememberGuidePreviewController
import app.opentv.ui.design.ScreenFrame
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The guide's preview has a sound track only while its sound setting is on (#110), and its band of
 * text fits at a larger font size (#507).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GuidePreviewControllerTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose)

    private val settings = testGraph.graph.settings

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

    @Test
    @Config(qualifiers = TV_1080P)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `at a larger font size the preview band's synopsis shows all three of its lines`() {
        val channel = Channel(
            id = 1,
            sourceId = 1,
            streamId = "1",
            name = "BBC Four",
            categoryId = null,
            logoUrl = null,
            epgChannelId = "four",
            number = null,
            streamUrl = "http://example.com/live/u/p/1.ts",
        )
        val programme = GuideProgramme("four", startUtcMillis = 0, endUtcMillis = HOUR_MILLIS, title = LONG_TITLE)
        compose.setTvContent {
            AtFontScale(1.3f) {
                ScreenFrame {
                    GuideBand(
                        row = ChannelsViewModel.Row(LineupChannel(channel, listOf(channel))),
                        programme = programme,
                        previewPlayer = null,
                        inFull = { it.withoutDetails().copy(description = SYNOPSIS) },
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)

        // All three of the synopsis's lines: squeezed for height, it ends its last whole line early.
        val synopsis = compose.onNodeWithText(SYNOPSIS, useUnmergedTree = true).assertNoLineCut()
        assertThat(synopsis.textLayout().lineCount).isEqualTo(SYNOPSIS_LINES)
        captureScreen("guide_preview_font_1_3")
    }

    private companion object {
        const val SYNOPSIS_LINES = 3
        const val HOUR_MILLIS = 3_600_000L
        const val SETTLE_MILLIS = 1_000L
        const val LONG_TITLE = "The Sky at Night: a Long Look at the Rings of Saturn and Their Moons"
        const val SYNOPSIS = "Maggie and Chris go looking for the moons that shepherd Saturn's rings, " +
            "and ask what the Cassini probe's last dive through the gap between the rings and the planet " +
            "told us about how old they are, and how long they have left."
    }
}
