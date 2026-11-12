/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

/**
 * Setup's steps, in the order they run, each with its share of the bar. The shares are how long
 * each took on a Fire TV Stick with a big Xtream provider (#224: 16,830 channels, 90,000 guide
 * programmes, 29,729 movies, 9,261 shows; about 89 seconds in all), not equal parts: the guide
 * alone took 61% of the time, and equal parts would hold the bar still for most of setup.
 */
enum class SetupStep(internal val share: Float, internal val savingFrom: Float = 0f) {
    CHANNELS(CHANNELS_SHARE),
    GUIDE(GUIDE_SHARE),

    /**
     * The categories and the download come first, and can't tell how far they've got (panels don't
     * say how big the list is): on the Stick they were 30% of the step, so saving moves the rest.
     */
    MOVIES(MOVIES_SHARE, savingFrom = MOVIES_DOWNLOAD),

    /** As [MOVIES]; the show list's download was half of its step. */
    SHOWS(SHOWS_SHARE, savingFrom = SHOWS_DOWNLOAD),
}

// Percentages of setup's time on the Stick, and the share of a list's step its download took.
private const val CHANNELS_SHARE = 15f
private const val GUIDE_SHARE = 61f
private const val MOVIES_SHARE = 16f
private const val SHOWS_SHARE = 8f
private const val MOVIES_DOWNLOAD = 0.3f
private const val SHOWS_DOWNLOAD = 0.5f

/** Where Setup's bar stands, shared between the [steps] that run for this provider. */
class SetupBar(private val steps: List<SetupStep>) {
    private val total = steps.sumOf { it.share.toDouble() }.toFloat()

    /** The bar once [step] is [done] (0 to 1) of the way through. */
    fun reached(step: SetupStep, done: Float): Float {
        val before = steps.takeWhile { it != step }.sumOf { it.share.toDouble() }.toFloat()
        return (before + step.share * done) / total
    }

    private var position = 0f

    /**
     * Moves the bar to [step], [done] of the way through, and returns where it stands. It never
     * goes back, and stops short of full: the last step finishing isn't setup finishing.
     */
    fun advance(step: SetupStep, done: Float): Float {
        position = maxOf(position, minOf(reached(step, done.coerceIn(0f, 1f)), ALMOST_FULL))
        return position
    }

    /** Moves the bar to [step] with its list [saved] (0 to 1) of the way through saving. */
    fun saving(step: SetupStep, saved: Float): Float =
        advance(step, step.savingFrom + (1 - step.savingFrom) * saved.coerceIn(0f, 1f))

    fun finish(): Float {
        position = 1f
        return position
    }

    private companion object {
        const val ALMOST_FULL = 0.99f
    }
}
