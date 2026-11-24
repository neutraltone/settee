/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.ui.input.key.Key

/** What the live player has up when a key comes in, as far as where the key goes. */
internal data class LiveScreen(
    /** The channel list or a picker, which owns the whole d-pad while it's up. */
    val overlayOpen: Boolean,
    val controlsVisible: Boolean,
    /** A failure trying again could fix, so Try again is on screen. */
    val retryableError: Boolean,
    /** Where Report is, when the error offers it beside Try again (ADR 0010). */
    val report: ReportPlace = ReportPlace.NONE,
)

/** Report beside a live channel's Try again, as far as where a key goes. */
internal enum class ReportPlace {
    NONE,

    /** Offered, with the Highlight on Try again to its left: Right goes to it. */
    BESIDE_TRY_AGAIN,

    /** The Highlight is on it: OK presses it and Left goes back to Try again. */
    HIGHLIGHTED,
}

/** What a key press does in the live player. */
internal sealed interface LiveKey {
    /** Not this handler's: it goes on to whatever has focus, and Back then to the player's [backOnRelease]. */
    data object PassThrough : LiveKey

    /** The channel list, a picker or the control bar takes it: it moves their highlight or presses a row. */
    data object ToFocused : LiveKey

    data class Digit(val digit: Char) : LiveKey
    data class Zap(val delta: Int) : LiveKey
    data object OpenChannelList : LiveKey
    data object OpenQualities : LiveKey
    data object OpenOptions : LiveKey
    data object Reveal : LiveKey
    data object Retry : LiveKey
}

/**
 * Where a key pressed down in the live player goes. Media keys and a held OK are dealt with before
 * this.
 */
internal fun liveKey(key: Key, screen: LiveScreen): LiveKey {
    val digit = keyToDigit(key)
    return when {
        // Whatever has focus gets the first say, a picker above all (BackOnRelease).
        key == Key.Back || key == Key.Escape -> LiveKey.PassThrough
        // Typing a channel number jumps to it, TiviMate-style.
        digit != null -> LiveKey.Digit(digit)
        screen.overlayOpen -> LiveKey.ToFocused
        key == Key.Menu -> LiveKey.OpenOptions
        // With the controls up, the d-pad moves around them: Down reaches Subtitles and the pills, and
        // Up reaches close (docs/LOOK.md, Player). The channel keys still zap.
        screen.controlsVisible && key != Key.ChannelUp && key != Key.ChannelDown -> LiveKey.ToFocused
        else -> aroundReport(key, screen.report) ?: overPicture(key, screen.retryableError)
    }
}

/**
 * The keys Report beside Try again takes (where [report] says), or null for the rest. It's in the
 * error's row, as on every error that offers it, so only Right from Try again and Left back from it
 * move between them: Up and Down still change channel, and Right from Report opens the qualities.
 */
private fun aroundReport(key: Key, report: ReportPlace): LiveKey? = when {
    report == ReportPlace.BESIDE_TRY_AGAIN && key == Key.DirectionRight -> LiveKey.ToFocused
    report == ReportPlace.HIGHLIGHTED && (key == Key.DirectionLeft || key in OK_KEYS) -> LiveKey.ToFocused
    else -> null
}

/** With nothing over the picture, or Try again alone. */
private fun overPicture(key: Key, retryableError: Boolean): LiveKey = when {
    // This remote has no CH+/CH- keys, so up and down are the channel changer, a dead channel's too:
    // zapping away is how the viewer leaves it.
    key == Key.DirectionUp || key == Key.ChannelUp -> LiveKey.Zap(-1)
    key == Key.DirectionDown || key == Key.ChannelDown -> LiveKey.Zap(1)
    key == Key.DirectionLeft -> LiveKey.OpenChannelList
    key == Key.DirectionRight -> LiveKey.OpenQualities
    // The highlight's on Try again, but this handler sees OK before the button does.
    key in OK_KEYS -> if (retryableError) LiveKey.Retry else LiveKey.Reveal
    else -> LiveKey.PassThrough
}

/** Maps a remote's number keys (top row and numeric keypad) to a digit, or null for other keys. */
private fun keyToDigit(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}
