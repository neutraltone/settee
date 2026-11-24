/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import app.opentv.ui.player.LiveKey
import app.opentv.ui.player.LiveScreen
import app.opentv.ui.player.OK_KEYS
import app.opentv.ui.player.ReportPlace
import app.opentv.ui.player.liveKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the live player sends a key press, by what's on screen (#370). Robolectric only so Compose's
 * [Key] has Android's key codes behind it, whatever its companion reads when it loads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LiveKeysTest {

    private val nothingShowing = LiveScreen(overlayOpen = false, controlsVisible = false, retryableError = false)
    private val retryableError = nothingShowing.copy(retryableError = true)

    @Test
    fun `with nothing showing, OK opens the controls`() {
        for (ok in OK_KEYS) assertThat(liveKey(ok, nothingShowing)).isEqualTo(LiveKey.Reveal)
    }

    @Test
    fun `on an error trying again could fix, OK tries again rather than opening the controls`() {
        for (ok in OK_KEYS) assertThat(liveKey(ok, retryableError)).isEqualTo(LiveKey.Retry)
    }

    @Test
    fun `on an error trying again could fix, up and down still change channel`() {
        assertThat(liveKey(Key.DirectionUp, retryableError)).isEqualTo(LiveKey.Zap(-1))
        assertThat(liveKey(Key.ChannelUp, retryableError)).isEqualTo(LiveKey.Zap(-1))
        assertThat(liveKey(Key.DirectionDown, retryableError)).isEqualTo(LiveKey.Zap(1))
        assertThat(liveKey(Key.ChannelDown, retryableError)).isEqualTo(LiveKey.Zap(1))
    }

    @Test
    fun `with Send report under Try again, Down reaches it, and Up and the channel keys still zap`() {
        val offered = retryableError.copy(report = ReportPlace.UNDER_TRY_AGAIN)

        assertThat(liveKey(Key.DirectionDown, offered)).isEqualTo(LiveKey.ToFocused)
        assertThat(liveKey(Key.DirectionUp, offered)).isEqualTo(LiveKey.Zap(-1))
        assertThat(liveKey(Key.ChannelDown, offered)).isEqualTo(LiveKey.Zap(1))
        for (ok in OK_KEYS) assertThat(liveKey(ok, offered)).isEqualTo(LiveKey.Retry)
    }

    @Test
    fun `on Send report, OK presses it, Up goes back to Try again, and Down zaps on`() {
        val onReport = retryableError.copy(report = ReportPlace.HIGHLIGHTED)

        for (ok in OK_KEYS) assertThat(liveKey(ok, onReport)).isEqualTo(LiveKey.ToFocused)
        assertThat(liveKey(Key.DirectionUp, onReport)).isEqualTo(LiveKey.ToFocused)
        assertThat(liveKey(Key.DirectionDown, onReport)).isEqualTo(LiveKey.Zap(1))
        assertThat(liveKey(Key.ChannelUp, onReport)).isEqualTo(LiveKey.Zap(-1))
    }

    @Test
    fun `Back is left to whatever has focus, and then the player's own Back step, whatever is showing`() {
        val screens = listOf(
            nothingShowing,
            retryableError,
            retryableError.copy(controlsVisible = true),
            retryableError.copy(overlayOpen = true),
        )
        for (screen in screens) {
            assertThat(liveKey(Key.Back, screen)).isEqualTo(LiveKey.PassThrough)
            assertThat(liveKey(Key.Escape, screen)).isEqualTo(LiveKey.PassThrough)
        }
    }

    @Test
    fun `on an error trying again could fix, digits still start a channel number`() {
        assertThat(liveKey(Key.Seven, retryableError)).isEqualTo(LiveKey.Digit('7'))
        assertThat(liveKey(Key.NumPad0, retryableError)).isEqualTo(LiveKey.Digit('0'))
    }

    @Test
    fun `with the controls up over an error, OK presses the control and the channel keys still zap`() {
        val barUp = retryableError.copy(controlsVisible = true)

        assertThat(liveKey(Key.DirectionCenter, barUp)).isEqualTo(LiveKey.ToFocused)
        assertThat(liveKey(Key.ChannelDown, barUp)).isEqualTo(LiveKey.Zap(1))
    }

    @Test
    fun `with the channel list over an error, it keeps the d-pad`() {
        val listOpen = retryableError.copy(overlayOpen = true)

        assertThat(liveKey(Key.DirectionCenter, listOpen)).isEqualTo(LiveKey.ToFocused)
    }

    @Test
    fun `with nothing showing, left opens the channel list, right the qualities, and Menu the options`() {
        assertThat(liveKey(Key.DirectionLeft, nothingShowing)).isEqualTo(LiveKey.OpenChannelList)
        assertThat(liveKey(Key.DirectionRight, nothingShowing)).isEqualTo(LiveKey.OpenQualities)
        assertThat(liveKey(Key.Menu, nothingShowing)).isEqualTo(LiveKey.OpenOptions)
    }

    @Test
    fun `another key with nothing showing passes through`() {
        assertThat(liveKey(Key.Info, nothingShowing)).isEqualTo(LiveKey.PassThrough)
    }
}
