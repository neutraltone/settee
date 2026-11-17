/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.EpisodeTimingsDao
import app.opentv.data.model.Episode
import app.opentv.data.model.EpisodeTimings
import app.opentv.data.model.Series
import app.opentv.data.model.knownTmdbId
import app.opentv.data.remote.IntroDbClient
import app.opentv.data.remote.TheIntroDbClient
import app.opentv.data.remote.TmdbClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where episodes' intros, recaps and credits fall (#517), looked up from TheIntroDB, then IntroDB for
 * credits TheIntroDB hasn't timed, and kept on the device.
 *
 * TheIntroDB goes first: on the household's measured episodes it matched the credits as well as IntroDB
 * or better, and once by three minutes. Every failure is quiet: the player then puts the card 30
 * seconds before the end, as it always could.
 */
class EpisodeTimingsRepository(
    private val dao: EpisodeTimingsDao,
    private val theIntroDb: TheIntroDbClient,
    private val introDb: IntroDbClient,
    private val tmdb: TmdbClient,
    private val seriesOf: suspend (Episode) -> Series?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** An episode's [timings], if any were found, and its show's [others] looked up so far. */
    data class Known(val timings: EpisodeTimings?, val others: List<EpisodeTimings>)

    private data class ShowIds(val tmdbId: String?, val imdbId: String?) {
        val key: String? get() = EpisodeTimings.showKey(tmdbId, imdbId)
    }

    // Finding a show's ids can take two TMDB requests; an episode's start shouldn't repeat them.
    private val ids = ConcurrentHashMap<Pair<Long, String>, ShowIds>()

    /** [episode]'s timings: kept ones while they're fresh, else asked for and kept. */
    suspend fun timingsFor(episode: Episode): Known = withContext(Dispatchers.IO) {
        val show = seriesOf(episode)?.let(::showIds)
        val key = show?.key ?: return@withContext Known(null, emptyList())
        val kept = dao.get(key, episode.season, episode.episodeNumber)
        val timings = if (kept != null && kept.isFresh()) kept else lookUp(show, key, episode) ?: kept
        val others = dao.forShow(key).filterNot { it.season == episode.season && it.episode == episode.episodeNumber }
        Known(timings, others)
    }

    /**
     * Asks TheIntroDB and, for credits it doesn't have, IntroDB; keeps what they said, nothing timed
     * included. Null, keeping nothing, when neither could be asked: the next start asks again.
     */
    // The clients block; this runs on the IO dispatcher [timingsFor] moved to.
    private suspend fun lookUp(show: ShowIds, key: String, episode: Episode): EpisodeTimings? {
        val season = episode.season
        val number = episode.episodeNumber
        val first = show.tmdbId?.let { theIntroDb.timings(it, season, number) }
        val primary = first?.getOrNull()
        val fallback = if (primary?.creditsStartMillis != null) {
            null
        } else {
            (show.imdbId ?: imdbIdFor(show))?.let { introDb.timings(it, season, number) }
        }
        val reached = first?.isSuccess == true || fallback?.isSuccess == true
        if (!reached) return null
        val secondary = fallback?.getOrNull()
        val merged = when {
            primary == null -> secondary
            secondary?.creditsStartMillis != null || secondary?.noCredits == true -> primary.copy(
                creditsStartMillis = secondary.creditsStartMillis,
                creditsEndMillis = secondary.creditsEndMillis,
                sceneAfterCredits = secondary.sceneAfterCredits,
                noCredits = secondary.noCredits,
                source = secondary.source,
            )
            else -> primary
        }
        val timings = (merged ?: EpisodeTimings(show = key, season = season, episode = number))
            .copy(show = key, fetchedAtMillis = clock())
        dao.upsert(timings)
        return timings
    }

    /** Timed credits, or credits confirmed not to be there, don't change; nothing timed is asked again in a week. */
    private fun EpisodeTimings.isFresh(): Boolean =
        creditsStartMillis != null || noCredits || clock() - fetchedAtMillis < REASK_MILLIS

    private fun showIds(series: Series): ShowIds? = ids[series.sourceId to series.seriesId] ?: run {
        // Stremio add-ons name a series by its IMDb id.
        val imdbId = series.seriesId.takeIf { it.startsWith("tt") }
        val tmdbId = knownTmdbId(series.tmdbId) ?: tmdb.seriesId(series.name, series.year)
        // Not kept when TMDB didn't answer, so the next episode tries again.
        ShowIds(tmdbId, imdbId).takeIf { it.key != null }?.also { ids[series.sourceId to series.seriesId] = it }
    }

    private fun imdbIdFor(show: ShowIds): String? =
        show.tmdbId?.let { tmdb.imdbId(title = "", year = null, isMovie = false, tmdbId = it) }

    private companion object {
        const val REASK_MILLIS = 7 * 24 * 60 * 60_000L
    }
}
