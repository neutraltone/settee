/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.model

import androidx.room.Entity

/**
 * Where an episode's intro, recap and credits fall, as a community database timed them against its
 * own copy of the episode (#517). Times are millis from the start of that copy, which a provider's can
 * differ from at its end: the player checks them against the stream before using them.
 *
 * A cache, not something a viewer made, so it isn't synced. A row with nothing timed is still kept,
 * so the episode isn't asked about on every start; [fetchedAtMillis] says when to ask again.
 */
@Entity(tableName = "episode_timings", primaryKeys = ["show", "season", "episode"])
data class EpisodeTimings(
    /** The show as the timings were asked for: `tmdb:<id>`, or `imdb:<tt…>` when there's no TMDB id. */
    val show: String,
    val season: Int,
    val episode: Int,
    val introStartMillis: Long? = null,
    val introEndMillis: Long? = null,
    val recapStartMillis: Long? = null,
    val recapEndMillis: Long? = null,
    val creditsStartMillis: Long? = null,
    /** Null when the credits run to the end of the episode, as TheIntroDB marks them. */
    val creditsEndMillis: Long? = null,
    /** Whether a scene comes after the credits, which counting down to the Next Episode would skip. */
    val sceneAfterCredits: Boolean = false,
    /** Someone has confirmed the episode has no intro, as opposed to nobody having timed one. */
    val noIntro: Boolean = false,
    val noRecap: Boolean = false,
    val noCredits: Boolean = false,
    /**
     * Which database the credits came from, or the rest when it has no credits; null when neither had
     * the episode.
     */
    val source: TimingsSource? = null,
    val fetchedAtMillis: Long = 0,
) {
    /** How long the credits run, where both ends are timed. */
    val creditsMillis: Long?
        get() = creditsStartMillis?.let { start -> creditsEndMillis?.let { it - start } }

    /**
     * Whether the credits are settled: timed, or confirmed not to be there. Neither changes, so neither
     * is asked again.
     */
    val creditsKnown: Boolean get() = creditsStartMillis != null || noCredits

    /** Whether the intro is settled the same way, for Skip intro (#518). */
    val introKnown: Boolean get() = introStartMillis != null || noIntro

    companion object {
        /** The [show] key of a show TMDB knows as [id]. */
        fun tmdbShow(id: String): String = "tmdb:$id"

        /** The [show] key of a show IMDb knows as [id], for one with no TMDB id. */
        fun imdbShow(id: String): String = "imdb:$id"

        /** The [show] key for a series, from whichever id it has, TMDB's first. */
        fun showKey(tmdbId: String?, imdbId: String?): String? = tmdbId?.let(::tmdbShow) ?: imdbId?.let(::imdbShow)
    }
}

/** The community databases timings come from (#517). Stored by name. */
enum class TimingsSource { THE_INTRO_DB, INTRO_DB }

/**
 * Another episode of the same show, for its usual credits length: its [timings], and its length as the
 * provider gave it, [lengthMillis], to check them against when it's known.
 */
data class ShowEpisode(val timings: EpisodeTimings, val lengthMillis: Long?)
