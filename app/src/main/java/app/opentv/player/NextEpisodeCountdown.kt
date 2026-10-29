/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

/**
 * Decides when the episode player offers the Next Episode and when it plays it.
 *
 * The countdown is the time left in the episode rather than a timer of its own, so pausing holds
 * it and seeking moves it with no extra bookkeeping, and "the countdown ran out" and "the episode
 * ended" are the same moment. [autoPlay] is read on every update, so the setting applies at once.
 */
class NextEpisodeCountdown(
    private val autoPlay: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Where the episode playing is; [durationMillis] is 0 when it isn't known. [ended] because a
     * stream can stop a little short of the length its container claims.
     */
    data class Playback(val positionMillis: Long, val durationMillis: Long, val ended: Boolean = false)

    /** What the player draws over the picture. */
    sealed interface Overlay {
        data object None : Overlay

        /** The Next Episode card, [secondsLeft] before the episode ends; [counting] when it will play on its own. */
        data class Card(val secondsLeft: Int, val counting: Boolean) : Overlay

        /** "Are you still watching?", with playback paused; [streamStopped] once the provider connection is let go. */
        data class StillWatching(val streamStopped: Boolean) : Overlay
    }

    /** What the player should do now. */
    sealed interface Decision {
        /** Move on to the Next Episode. */
        data object PlayNext : Decision

        /** Hold the picture at the end while "Are you still watching?" waits for an answer. */
        data object Pause : Decision

        /** Nobody answered: let go of the provider connection, which may be the account's only one. */
        data object StopStream : Decision
    }

    var overlay: Overlay = Overlay.None
        private set

    private var hasNext = false

    // Cancel holds only while playback stays in the last 15 seconds: a viewer who seeks back out
    // and lets it reach the end again gets the card again.
    private var cancelled = false

    /** Episodes in a row the countdown started with nobody touching the remote. */
    private var unattended = 0

    private var askedAtMillis = 0L

    /** A new episode started; [hasNext] is whether it has a Next Episode. */
    fun newEpisode(hasNext: Boolean) {
        this.hasNext = hasNext
        cancelled = false
        overlay = Overlay.None
    }

    /** Takes where playback is now, polled while the episode plays; returns what to do, if anything. */
    fun update(playback: Playback): Decision? {
        (overlay as? Overlay.StillWatching)?.let { return whileAsking(it) }
        val leftMillis = playback.durationMillis - playback.positionMillis
        // Without a known length there is no "15 seconds before the end"; and a card that comes
        // up almost as soon as a short clip starts would be in the way rather than helpful.
        val offered = hasNext && playback.durationMillis >= MIN_PLAY_MILLIS + CARD_MILLIS
        val inCredits = leftMillis <= CARD_MILLIS
        if (!inCredits) cancelled = false
        overlay = if (offered && inCredits && !cancelled) {
            Overlay.Card(secondsLeft = ((leftMillis + 999) / 1_000).toInt(), counting = autoPlay())
        } else {
            Overlay.None
        }
        val card = overlay as? Overlay.Card
        return when {
            card?.counting != true || (leftMillis > 0 && !playback.ended) -> null
            unattended >= UNATTENDED_RUN -> {
                overlay = Overlay.StillWatching(streamStopped = false)
                askedAtMillis = clock()
                Decision.Pause
            }
            else -> {
                unattended++
                moveOn()
            }
        }
    }

    /** While "Are you still watching?" waits: nothing plays, and after a while the stream goes. */
    private fun whileAsking(asked: Overlay.StillWatching): Decision? {
        if (asked.streamStopped || clock() - askedAtMillis < STOP_STREAM_AFTER_MILLIS) return null
        overlay = Overlay.StillWatching(streamStopped = true)
        return Decision.StopStream
    }

    /** OK on the card: skip the credits. Someone is plainly watching, so the run starts again. */
    fun ok(): Decision? {
        if (overlay !is Overlay.Card) return null
        unattended = 0
        return moveOn()
    }

    /** Continue watching on "Are you still watching?": play on, counting a new run from here. */
    fun continueWatching(): Decision? {
        if (overlay !is Overlay.StillWatching) return null
        unattended = 0
        return moveOn()
    }

    /** A key press or touch during playback: someone is there, so the run starts again. */
    fun input() {
        unattended = 0
    }

    /** Cancel ("Watch credits") or Back on the card: let this episode play out. */
    fun cancel() {
        cancelled = true
        overlay = Overlay.None
    }

    // Starting the Next Episode takes a moment, and until it does the old episode's end is still
    // what gets polled; standing down until newEpisode() keeps that from asking for it twice.
    private fun moveOn(): Decision {
        hasNext = false
        overlay = Overlay.None
        return Decision.PlayNext
    }

    private companion object {
        const val CARD_MILLIS = 15_000L
        const val MIN_PLAY_MILLIS = 30_000L
        const val UNATTENDED_RUN = 3
        const val STOP_STREAM_AFTER_MILLIS = 5 * 60_000L
    }
}
