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
    fun `on an error trying again could fix, Back leaves the player itself`() {
        assertThat(liveKey(Key.Back, retryableError)).isEqualTo(LiveKey.Leave)
        assertThat(liveKey(Key.Escape, retryableError)).isEqualTo(LiveKey.Leave)
    }

    @Test
    fun `with nothing showing, Back is left to the back handler`() {
        assertThat(liveKey(Key.Back, nothingShowing)).isEqualTo(LiveKey.PassThrough)
    }

    @Test
    fun `on an error trying again could fix, digits still start a channel number`() {
        assertThat(liveKey(Key.Seven, retryableError)).isEqualTo(LiveKey.Digit('7'))
        assertThat(liveKey(Key.NumPad0, retryableError)).isEqualTo(LiveKey.Digit('0'))
    }

    @Test
    fun `with the controls up over an error, OK presses the control and Back closes them`() {
        val barUp = retryableError.copy(controlsVisible = true)

        assertThat(liveKey(Key.DirectionCenter, barUp)).isEqualTo(LiveKey.ToFocused)
        assertThat(liveKey(Key.Back, barUp)).isEqualTo(LiveKey.PassThrough)
        assertThat(liveKey(Key.ChannelDown, barUp)).isEqualTo(LiveKey.Zap(1))
    }

    @Test
    fun `with the channel list over an error, it keeps the d-pad and Back closes it`() {
        val listOpen = retryableError.copy(overlayOpen = true)

        assertThat(liveKey(Key.DirectionCenter, listOpen)).isEqualTo(LiveKey.ToFocused)
        assertThat(liveKey(Key.Back, listOpen)).isEqualTo(LiveKey.PassThrough)
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
