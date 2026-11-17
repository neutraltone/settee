/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

/** Where the Next Episode card comes up in a stream of a given length. */
typealias CuePlacement = (streamMillis: Long) -> NextEpisodeCountdown.Cue

/**
 * Decides when the episode player offers the Next Episode and when it plays it.
 *
 * The card comes up at the episode's [cue] and counts down 15 seconds from there, or from wherever a
 * seek lands in the credits. The countdown is worked out from the position rather than kept as a timer
 * of its own, so pausing holds it. [autoPlay] is read on every update, so the setting applies at once.
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

    /**
     * Where the card comes up, [cardAtMillis] into the stream, and whether it [countsDown] from there:
     * not when a scene follows the credits, which the countdown would skip.
     */
    data class Cue(val cardAtMillis: Long, val countsDown: Boolean = true)

    /** What the player draws over the picture. */
    sealed interface Overlay {
        data object None : Overlay

        /** The Next Episode card, [secondsLeft] before it plays; [counting] when it will play on its own. */
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

    /**
     * Where the card comes up in a stream [durationMillis] long. A function of the length because the
     * timings are placed against the stream, whose length isn't known until it plays; replaced when the
     * episode's timings arrive, and put back to [UpNextCue.fallback] by [newEpisode].
     */
    var cue: CuePlacement = UpNextCue::fallback

    private var hasNext = false

    // Cancel holds only while playback stays past the card: a viewer who seeks back before it and
    // lets it reach the credits again gets the card again.
    private var cancelled = false

    private val start = CountdownStart()

    /** Episodes in a row the countdown started with nobody touching the remote. */
    private var unattended = 0

    private var askedAtMillis = 0L

    /** A new episode started; [hasNext] is whether it has a Next Episode. */
    fun newEpisode(hasNext: Boolean) {
        this.hasNext = hasNext
        cue = UpNextCue::fallback
        cancelled = false
        start.clear()
        overlay = Overlay.None
    }

    /** Takes where playback is now, polled while the episode plays; returns what to do, if anything. */
    fun update(playback: Playback): Decision? {
        (overlay as? Overlay.StillWatching)?.let { return whileAsking(it) }
        return if (showCard(playback)) runOut() else null
    }

    /** Puts the card up or takes it down for [playback]; true when its time has run out. */
    private fun showCard(playback: Playback): Boolean {
        // Without a known length there is nothing to place the card against, and a card that comes up
        // almost as soon as a short clip starts would be in the way rather than helpful.
        val cue = playback.durationMillis.takeIf { it > 0 }?.let(cue)
        if (!hasNext || cue == null || cue.cardAtMillis < MIN_PLAY_MILLIS) {
            overlay = Overlay.None
            return false
        }
        // A stream can stop short of its cue, when the timings were for a longer cut: it still moves on.
        val inCredits = playback.positionMillis >= cue.cardAtMillis || playback.ended
        if (!inCredits) cancelled = false
        val countFrom = start.update(cue.cardAtMillis, playback, inCredits)
        if (start.landedAtEnd) cancelled = true
        val leftMillis = (countFrom + COUNTDOWN_MILLIS - playback.positionMillis).coerceIn(0, COUNTDOWN_MILLIS)
        val counting = autoPlay() && cue.countsDown
        val showing = inCredits && !cancelled
        overlay = if (showing) {
            Overlay.Card(secondsLeft = ((leftMillis + 999) / 1_000).toInt(), counting = counting)
        } else {
            Overlay.None
        }
        // Without a countdown, as when a scene follows the credits, the episode's end is when it moves on.
        return showing && ((playback.ended && autoPlay()) || (counting && leftMillis == 0L))
    }

    /** The card's time ran out: on to the Next Episode, unless nobody seems to be watching. */
    private fun runOut(): Decision = if (unattended >= UNATTENDED_RUN) {
        overlay = Overlay.StillWatching(streamStopped = false)
        askedAtMillis = clock()
        Decision.Pause
    } else {
        unattended++
        moveOn()
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

    /**
     * Where the countdown runs from while playback is in the credits. Played into, that's the cue, or
     * where playback was if late timings moved the cue behind it. Seeked or resumed into, it's where
     * playback landed: counting from the cue would move on before the viewer saw any of what they chose,
     * at once if they landed 15 seconds past it. Landing in the last 15 seconds is taken as choosing the
     * end, so it plays out with no card, as after Watch credits.
     */
    private class CountdownStart {
        /** Where the last update found playback, to tell playing into the credits from a seek into them. */
        private var lastPositionMillis: Long? = null
        private var fromMillis: Long? = null

        /** Whether the last update was a seek into the last 15 seconds. */
        var landedAtEnd = false
            private set

        fun clear() {
            lastPositionMillis = null
            fromMillis = null
        }

        /** Where the countdown runs from at [playback], with the card's cue at [cueMillis]. */
        fun update(cueMillis: Long, playback: Playback, inCredits: Boolean): Long {
            val previous = lastPositionMillis
            lastPositionMillis = playback.positionMillis
            landedAtEnd = false
            fromMillis = if (inCredits) fromMillis ?: arrive(cueMillis, previous, playback) else null
            return fromMillis ?: cueMillis
        }

        private fun arrive(cueMillis: Long, previous: Long?, playback: Playback): Long {
            val position = playback.positionMillis
            val playedFrom = previous?.takeIf { position - it in 0..JUMP_MILLIS }
            landedAtEnd = playedFrom == null &&
                !playback.ended &&
                playback.durationMillis - position < COUNTDOWN_MILLIS
            return maxOf(cueMillis, playedFrom ?: position)
        }
    }

    private companion object {
        const val COUNTDOWN_MILLIS = 15_000L

        /** More than playback moves between two polls, half a second apart, even on a busy Stick. */
        const val JUMP_MILLIS = 2_000L
        const val MIN_PLAY_MILLIS = 30_000L
        const val UNATTENDED_RUN = 3
        const val STOP_STREAM_AFTER_MILLIS = 5 * 60_000L
    }
}
