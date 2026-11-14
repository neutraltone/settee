/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.model.Programme
import app.opentv.data.repo.NowAndNext
import app.opentv.player.PlaybackQueue
import app.opentv.ui.player.CardChannel
import app.opentv.ui.player.LivePlayerContent
import app.opentv.ui.player.SubtitlePicks
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The card that says which channel is on, over the live picture as the channel changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class LiveChannelCardTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val players = TestPlayers(context)
    private val controller = players.controller(LIVE, dvr = true)

    private val zapped = mutableListOf<Int>()
    private var programme by mutableStateOf<String?>(null)
    private var nextProgramme by mutableStateOf<String?>(null)

    @After
    fun release() {
        controller.release()
        players.close()
    }

    private fun showPlayer() {
        compose.setTvContent {
            LivePlayerContent(
                controller = controller,
                settings = AppSettings.get(context),
                queue = CHANNELS,
                variants = emptyList(),
                currentId = TWO.id,
                previousId = null,
                onNow = { emptyMap() },
                cardChannel = CardChannel("#${TWO.id}", TWO.number, TWO.name, TWO.logoUrl),
                nowAndNext = ::guide,
                recordingThis = false,
                livePause = true,
                pausePromptDismissed = false,
                onPlayChannel = {},
                onZap = { delta, _ -> zapped += delta },
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
        players.play(controller, isLive = true)
        compose.waitForIdle()
    }

    /**
     * What's on in the guide: [programme] half an hour in, with [nextProgramme] after it. The card
     * draws next's start time, so it starts at a fixed time rather than when [programme] ends, or the
     * card would show a different time on every run and turn up as changed in every PR's screenshots.
     */
    private fun guide(now: Long): NowAndNext? {
        val on = programme?.let { programmeOn(it, now - HALF_HOUR_MILLIS) }
        val next = nextProgramme?.let { programmeOn(it, NEXT_START) }
        return if (on == null && next == null) null else NowAndNext(on, next)
    }

    private fun programmeOn(title: String, start: Long) = Programme(
        feedId = 1,
        epgChannelId = "g",
        startUtcMillis = start,
        endUtcMillis = start + 2 * HALF_HOUR_MILLIS,
        title = title,
    )

    private fun hasProgressBarRangeInfo() = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `the channel card shows how far through what's on it is, and what's on next`() {
        programme = "News"
        nextProgramme = "Quiz"

        showPlayer()

        compose.onNodeWithText("News").assertExists()
        compose.onNodeWithText("Quiz", substring = true).assertExists()
        val progress = compose.onNode(hasProgressBarRangeInfo()).fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current
        assertThat(progress).isWithin(0.05f).of(0.5f)
        captureScreen("live_player_card")
    }

    @Test
    fun `the channel card never takes the Highlight, so Up and Down still zap while it shows`() {
        programme = "News"
        showPlayer()
        compose.onNodeWithText("News").assertExists()

        // Only the full-screen catcher behind it holds focus: nothing on the card itself.
        compose.onAllNodes(isFocused() and hasText("News"), useUnmergedTree = true).assertCountEquals(0)
        press(Key.DirectionUp)
        press(Key.DirectionDown)

        assertThat(zapped).containsExactly(-1, 1).inOrder()
    }

    private companion object {
        const val HALF_HOUR_MILLIS = 1_800_000L
        val NEXT_START = Instant.parse("2099-01-01T20:00:00Z").toEpochMilli()
        val TWO = PlaybackQueue.Item(11L, "Two", logoUrl = "https://example.com/logo/11.png", number = 2)
        val CHANNELS = listOf(
            PlaybackQueue.Item(10L, "One", logoUrl = null, number = 1),
            TWO,
            PlaybackQueue.Item(12L, "Twelve", logoUrl = null, number = 12),
        )
    }
}
