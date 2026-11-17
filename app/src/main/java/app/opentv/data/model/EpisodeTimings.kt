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
    /** Which database answered, [THE_INTRO_DB] or [INTRO_DB]; null when neither had the episode. */
    val source: String? = null,
    val fetchedAtMillis: Long = 0,
) {
    /** How long the credits run, where both ends are timed. */
    val creditsMillis: Long?
        get() = creditsStartMillis?.let { start -> creditsEndMillis?.let { it - start } }

    companion object {
        const val THE_INTRO_DB = "theintrodb"
        const val INTRO_DB = "introdb"

        /** The [show] key for a series, from whichever id it has. */
        fun showKey(tmdbId: String?, imdbId: String?): String? =
            tmdbId?.let { "tmdb:$it" } ?: imdbId?.let { "imdb:$it" }
    }
}
