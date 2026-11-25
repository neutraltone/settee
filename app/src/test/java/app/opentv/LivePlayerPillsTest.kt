/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.pm.PackageManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.model.Channel
import app.opentv.player.PlaybackQueue
import app.opentv.ui.player.LivePlayerContent
import app.opentv.ui.player.SubtitlePicks
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The live player's pills in longer languages (#507): one row, every label readable on one line or
 * else its icon alone. Its own class, beside LivePlayerContentTest, which is already as long as a
 * class may be.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class LivePlayerPillsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val players = TestPlayers(context)
    private val controller = players.controller(LIVE, dvr = true)

    @After
    fun release() {
        controller.release()
        players.close()
    }

    @Test
    @Config(qualifiers = "+de")
    fun `in German every pill is readable on one line, in one row on the screen`() {
        showPills()

        assertPillsFit(GERMAN)
        captureScreen("live_player_pills_de")
    }

    @Test
    @Config(qualifiers = "+ru")
    fun `in Russian every pill is readable on one line, in one row on the screen`() {
        showPills()

        assertPillsFit(RUSSIAN)
        captureScreen("live_player_pills_ru")
    }

    @Test
    @Config(qualifiers = "+de")
    fun `in German at a larger font size the pills share the row, cutting labels short rather than wrapping`() {
        showPills(fontScale = 1.3f)

        assertPillsFit(GERMAN)
        captureScreen("live_player_pills_de_font_1_3")
    }

    /**
     * The bar up with every live pill showing: Record, Quality, Aspect ratio, Sleep timer, Pop out
     * and Options, the most it ever has.
     */
    private fun showPills(fontScale: Float = 1f) {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        compose.setTvContent {
            AtFontScale(fontScale) {
                LivePlayerContent(
                    controller = controller,
                    settings = AppSettings.get(context),
                    queue = CHANNELS,
                    variants = listOf(variant(11L, "HD"), variant(12L, "FHD")),
                    currentId = 11L,
                    previousId = null,
                    onNow = { emptyMap() },
                    cardChannel = null,
                    nowAndNext = { null },
                    recordingThis = false,
                    livePause = true,
                    pausePromptDismissed = false,
                    onPlayChannel = {},
                    onZap = { _, _ -> },
                    onTune = {},
                    onToggleRecord = {},
                    favourite = false,
                    onToggleFavourite = {},
                    onTurnOnLivePause = {},
                    onDontAskAgain = {},
                    subtitlePicks = SubtitlePicks(onOff = {}, onPicked = {}),
                    onBack = {},
                )
            }
        }
        players.play(controller, isLive = true)
        compose.waitForIdle()
        // OK opens the controls on the seek bar, and Down to Subtitles brings the pills up.
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)
    }

    /** Each pill shows its label on one line, or else its icon alone, and all of them are on the screen. */
    private fun assertPillsFit(labels: List<String>) {
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        for (label in labels) {
            val text = compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes()
            val pill = if (text.isNotEmpty()) {
                compose.onNodeWithText(label, useUnmergedTree = true).assertOnOneLine()
            } else {
                compose.onNodeWithContentDescription(label, useUnmergedTree = true)
            }
            val bounds = pill.fetchSemanticsNode().boundsInRoot
            assertThat(bounds.width).isGreaterThan(0f)
            assertThat(bounds.right).isAtMost(screen.right)
        }
    }

    private fun variant(id: Long, quality: String) = Channel(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = "Two $quality",
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "http://example.com/live/u/p/$id.ts",
        qualityLabel = quality,
    )

    private companion object {
        const val FOCUS_MILLIS = 100L

        // Options isn't translated yet, so it's English in every language.
        val GERMAN = listOf("Aufnehmen", "Qualität", "Seitenverhältnis", "Sleep-Timer", "Bild-im-Bild", "Options")
        val RUSSIAN = listOf("Запись", "Качество", "Соотношение сторон", "Таймер сна", "Мини-плеер", "Options")
        val CHANNELS = listOf(
            PlaybackQueue.Item(11L, "Two", logoUrl = null, number = 2),
            PlaybackQueue.Item(12L, "Twelve", logoUrl = null, number = 12),
        )
    }
}
