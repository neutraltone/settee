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
import app.opentv.data.model.ShowEpisode
import app.opentv.data.model.knownTmdbId
import app.opentv.data.remote.IntroDbClient
import app.opentv.data.remote.TheIntroDbClient
import app.opentv.data.remote.TmdbClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
    private val shows: Shows,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Where an episode's series and the series' episodes are found: the catalogue. */
    interface Shows {
        suspend fun seriesOf(episode: Episode): Series?

        suspend fun episodesOf(series: Series): List<Episode>
    }

    /**
     * What's known for an episode: its own [timings], if any were found, and the [restOfShow]'s looked
     * up so far, for the show's usual credits length when it has none.
     */
    data class Found(val timings: EpisodeTimings?, val restOfShow: List<ShowEpisode>)

    private data class ShowIds(val tmdbId: String?, val imdbId: String?) {
        val key: String? get() = EpisodeTimings.showKey(tmdbId, imdbId)
    }

    // Finding a show's ids can take two TMDB requests; an episode's start shouldn't repeat them.
    private val ids = ConcurrentHashMap<Pair<Long, String>, ShowIds>()

    /** [episode]'s timings: kept ones while they're fresh, else asked for and kept. */
    suspend fun timingsFor(episode: Episode): Found = withContext(Dispatchers.IO) {
        val series = shows.seriesOf(episode)
        val show = series?.let(::showIds)
        val key = show?.key
        if (series == null || key == null) return@withContext Found(null, emptyList())
        val kept = dao.get(key, episode.season, episode.episodeNumber)
        val timings = if (kept != null && kept.isFresh()) kept else lookUp(series, show, key, episode) ?: kept
        Found(timings, restOfShow(series, key, episode))
    }

    /** The show's other episodes looked up so far, each with its length as the provider gave it. */
    private suspend fun restOfShow(series: Series, key: String, episode: Episode): List<ShowEpisode> {
        val lengths = shows.episodesOf(series).associate { (it.season to it.episodeNumber) to it.durationSeconds }
        return dao.forShow(key)
            .filterNot { it.season == episode.season && it.episode == episode.episodeNumber }
            .map { other ->
                val seconds = lengths[other.season to other.episode]?.takeIf { it > 0 }
                ShowEpisode(other, seconds?.let { TimeUnit.SECONDS.toMillis(it.toLong()) })
            }
    }

    /**
     * Asks TheIntroDB and, for credits it doesn't have, IntroDB. What they said is kept once it's settled:
     * the credits known, or every database asked having answered, so a failure is never kept as nothing
     * timed. Null when no database could be asked: the next start asks again.
     */
    // The clients block; this runs on the IO dispatcher [timingsFor] moved to.
    private suspend fun lookUp(series: Series, show: ShowIds, key: String, episode: Episode): EpisodeTimings? {
        val season = episode.season
        val number = episode.episodeNumber
        val theIntroDbAnswer = show.tmdbId?.let { theIntroDb.timings(it, season, number) }
        val fromTheIntroDb = theIntroDbAnswer?.getOrNull()
        val introDbAnswer = if (fromTheIntroDb?.creditsStartMillis != null) {
            null
        } else {
            imdbIdOf(series, show)?.let { introDb.timings(it, season, number) }
        }
        val answers = listOfNotNull(theIntroDbAnswer, introDbAnswer)
        if (answers.none { it.isSuccess }) return null
        val timings = (merge(fromTheIntroDb, introDbAnswer?.getOrNull()) ?: EpisodeTimings(key, season, number))
            .copy(show = key, fetchedAtMillis = clock())
        if (timings.creditsKnown || answers.all { it.isSuccess }) dao.upsert(timings)
        return timings
    }

    /** TheIntroDB's timings with IntroDB's credits in place of credits it didn't have. */
    private fun merge(fromTheIntroDb: EpisodeTimings?, fromIntroDb: EpisodeTimings?): EpisodeTimings? = when {
        fromTheIntroDb == null -> fromIntroDb
        fromIntroDb?.creditsKnown != true -> fromTheIntroDb
        else -> fromTheIntroDb.copy(
            creditsStartMillis = fromIntroDb.creditsStartMillis,
            creditsEndMillis = fromIntroDb.creditsEndMillis,
            sceneAfterCredits = fromIntroDb.sceneAfterCredits,
            noCredits = fromIntroDb.noCredits,
            source = fromIntroDb.source,
        )
    }

    /** Timed credits, or credits confirmed not to be there, don't change; nothing timed is asked again in a week. */
    private fun EpisodeTimings.isFresh(): Boolean = creditsKnown || clock() - fetchedAtMillis < REASK_MILLIS

    private fun showIds(series: Series): ShowIds? = ids[series.memo] ?: run {
        // Stremio add-ons name a series by its IMDb id.
        val imdbId = series.seriesId.takeIf { it.startsWith("tt") }
        val tmdbId = knownTmdbId(series.tmdbId) ?: tmdb.seriesId(series.name, series.year)
        // Kept only once the TMDB id is found: without it TheIntroDB can't be asked, so the next
        // episode tries TMDB again rather than settling for IntroDB all session.
        val found = ShowIds(tmdbId, imdbId)
        if (tmdbId != null) ids[series.memo] = found
        found.takeIf { it.key != null }
    }

    /** The show's IMDb id, from TMDB when the series didn't come with one, kept once found. */
    private fun imdbIdOf(series: Series, show: ShowIds): String? = show.imdbId
        ?: show.tmdbId
            ?.let { tmdb.imdbId(title = series.name, year = series.year, isMovie = false, tmdbId = it) }
            ?.also { ids[series.memo] = show.copy(imdbId = it) }

    private val Series.memo: Pair<Long, String> get() = sourceId to seriesId

    private companion object {
        const val REASK_MILLIS = 7 * 24 * 60 * 60_000L
    }
}
