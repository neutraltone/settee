/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.core.AppDispatchers
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
import kotlinx.coroutines.withContext

/**
 * Where episodes' intros, recaps and credits fall (#517), looked up from TheIntroDB, then IntroDB for
 * credits TheIntroDB hasn't timed, and kept on the device.
 *
 * TheIntroDB goes first: on the household's measured episodes it matched the credits as well as IntroDB
 * or better, and once by three minutes. Every failure is quiet: the player then puts the card 30
 * seconds before the end, as it always could.
 */
@Suppress("LongParameterList") // clock and dispatchers are only passed by ServiceLocator and tests.
class EpisodeTimingsRepository(
    private val dao: EpisodeTimingsDao,
    private val theIntroDb: TheIntroDbClient,
    private val introDb: IntroDbClient,
    private val tmdb: TmdbClient,
    private val shows: Shows,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatchers: AppDispatchers = AppDispatchers(),
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
    suspend fun timingsFor(episode: Episode): Found = withContext(dispatchers.io) {
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
     * Asks TheIntroDB and, for credits or an intro it doesn't have, IntroDB. What they said is kept when
     * the credits are known or every database asked answered, so a failure is never kept as nothing
     * timed; kept after a failure, it's asked about again on the next start. Null when no database could
     * be asked: the next start asks again.
     */
    // The clients block; this runs on the IO dispatcher [timingsFor] moved to.
    private suspend fun lookUp(series: Series, show: ShowIds, key: String, episode: Episode): EpisodeTimings? {
        val season = episode.season
        val number = episode.episodeNumber
        val theIntroDbAnswer = show.tmdbId?.let { theIntroDb.timings(it, season, number) }
        val fromTheIntroDb = theIntroDbAnswer?.getOrNull()
        // An intro matters as much as credits now Skip intro uses it (#518), and on the household's
        // episodes the intros timed were IntroDB's.
        val introDbAnswer = if (fromTheIntroDb?.creditsStartMillis != null && fromTheIntroDb.introKnown) {
            null
        } else {
            imdbIdOf(series, show)?.let { introDb.timings(it, season, number) }
        }
        val answers = listOfNotNull(theIntroDbAnswer, introDbAnswer)
        if (answers.none { it.isSuccess }) return null
        val answered = answers.all { it.isSuccess }
        val timings = (merge(fromTheIntroDb, introDbAnswer?.getOrNull()) ?: EpisodeTimings(key, season, number))
            .copy(show = key, fetchedAtMillis = if (answered) clock() else ASK_AGAIN)
        if (timings.creditsKnown || answered) dao.upsert(timings)
        return timings
    }

    /** TheIntroDB's timings with IntroDB's credits, intro and recap in place of ones it didn't have. */
    private fun merge(fromTheIntroDb: EpisodeTimings?, fromIntroDb: EpisodeTimings?): EpisodeTimings? = when {
        fromTheIntroDb == null -> fromIntroDb
        fromIntroDb == null -> fromTheIntroDb
        else -> fromTheIntroDb.withCreditsOf(fromIntroDb).withIntroAndRecapOf(fromIntroDb)
    }

    private fun EpisodeTimings.withCreditsOf(other: EpisodeTimings): EpisodeTimings =
        if (creditsKnown || !other.creditsKnown) {
            this
        } else {
            copy(
                creditsStartMillis = other.creditsStartMillis,
                creditsEndMillis = other.creditsEndMillis,
                sceneAfterCredits = other.sceneAfterCredits,
                noCredits = other.noCredits,
                source = other.source,
            )
        }

    private fun EpisodeTimings.withIntroAndRecapOf(other: EpisodeTimings): EpisodeTimings {
        val intro = if (introKnown) this else other
        val recap = if (recapStartMillis != null || noRecap) this else other
        return copy(
            introStartMillis = intro.introStartMillis,
            introEndMillis = intro.introEndMillis,
            noIntro = intro.noIntro,
            recapStartMillis = recap.recapStartMillis,
            recapEndMillis = recap.recapEndMillis,
            noRecap = recap.noRecap,
        )
    }

    /**
     * Credits and intro both timed, or confirmed not to be there, don't change. Anything less is asked
     * again in a week, since the community keeps adding timings; sooner after a failure.
     */
    private fun EpisodeTimings.isFresh(): Boolean =
        (creditsKnown && introKnown) || clock() - fetchedAtMillis < REASK_MILLIS

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
            ?.let { tmdb.imdbId(title = series.name, year = series.year, isMovie = false, tmdbId = it).getOrNull() }
            ?.also { ids[series.memo] = show.copy(imdbId = it) }

    private val Series.memo: Pair<Long, String> get() = sourceId to seriesId

    private companion object {
        const val REASK_MILLIS = 7 * 24 * 60 * 60_000L

        /** When timings kept after a failure count as fetched: long enough ago to be asked again at once. */
        const val ASK_AGAIN = 0L
    }
}
