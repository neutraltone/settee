/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.core.AppDispatchers
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Episode
import app.opentv.data.model.Series
import app.opentv.data.model.StremioAddon
import app.opentv.data.model.knownTmdbId
import app.opentv.data.remote.TmdbClient
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext

/**
 * The seasons of a Provider's show that TMDB lists and the Provider doesn't carry (#683), listed after
 * the Provider's on its page and played through the add-on stream picker.
 *
 * They're kept as episodes of an Add-ons copy of the show (ADR 0011): a series of the Add-ons source,
 * keyed by IMDb id, with the Provider's TMDB id, so ADR 0007 groups the two and the page lists the
 * copy's episodes for the seasons the Provider's lack. Never rows under the Provider's source, which a
 * Catalogue Refresh or the panel's next episode list would retire. The copy is made only once TMDB
 * shows a season the Provider lacks, so a show with nothing more gains no Version.
 */
class AddonSeasons(
    private val db: OpenTvDatabase,
    private val tmdb: TmdbClient,
    private val titles: AddonTitles,
    private val catalog: CatalogRepository,
    private val dispatchers: AppDispatchers,
    /** The viewer's add-ons: only one with streams for shows could play the seasons. */
    private val addons: () -> List<StremioAddon>,
) {
    /**
     * Stores [series]' seasons TMDB lists that none of its Provider Versions carries, as episodes of its
     * Add-ons copy, kept now if it has none; once the Provider's own episodes are stored, which is what
     * they're compared with. Nothing for a show kept from an add-on, one without a TMDB id, or without an
     * add-on that has show streams or a TMDB Key. A failure when TMDB didn't answer, which the page
     * doesn't show: the Provider's seasons are all there.
     */
    suspend fun fill(series: Series, nowUtcMillis: Long = System.currentTimeMillis()): Result<Unit> =
        withContext(dispatchers.io) {
            val sources = db.sources()
            val kept = sources.addonsSourceId()
            val tmdbId = knownTmdbId(series.tmdbId)
            val wanted = tmdbId != null && tmdb.isConfigured() && addons().any { it.servesStreams(SERIES) }
            if (kept.keeps(series.sourceId) || !wanted) return@withContext Result.success(Unit)
            val copy = db.versionsOf(series).firstOrNull { kept.keeps(it.sourceId) }
                ?: newCopy(series, nowUtcMillis).getOrElse { return@withContext Result.failure(it) }
                ?: return@withContext Result.success(Unit)
            val source = checkNotNull(sources.addons())
            catalog.fetchEpisodes(source, copy.seriesId, nowUtcMillis)
        }

    /** [series]' Add-ons copy, kept now when TMDB lists a season its Versions lack; null when it doesn't. */
    private suspend fun newCopy(series: Series, now: Long): Result<Series?> = runCatching {
        val listed = tmdb.addonEpisodes(series, now).getOrThrow().mapTo(HashSet()) { it.season }
        val carried = db.providerSeasons(series, db.sources().addonsSourceId())
        if (listed.none { it !in carried }) return@runCatching null
        val imdbId = titles.imdbIdOf(series).getOrThrow() ?: return@runCatching null
        titles.keepShow(series.asAddonTitle(imdbId))
    }.onFailure { if (it is CancellationException) throw it }

    private companion object {
        const val SERIES = "series"
    }
}

/**
 * Which of TMDB's seasons the Add-ons copy [series] keeps: those no other Version of the show carries,
 * plus any it kept before, so a season the Provider adds later stays as the copy's Version of it, with
 * its progress, rather than vanishing from under a resume point. A show only an add-on has keeps them all.
 */
internal suspend fun OpenTvDatabase.addonSeasonsKept(series: Series): (Int) -> Boolean {
    val own = episodes().forSeries(series.sourceId, series.seriesId).mapTo(HashSet()) { it.season }
    val carried = providerSeasons(series, sources().addonsSourceId())
    return { it in own || it !in carried }
}

/**
 * The episodes [episode]'s show has from its Add-ons copy in the seasons [own], its Provider series'
 * episodes, lacks (#683): what the show's page lists after the Provider's seasons, so the Next Episode
 * carries on into them. None for an episode kept from an add-on, or a show with no such copy.
 */
internal suspend fun OpenTvDatabase.addonSeasonsAfter(
    episode: Episode,
    own: List<Episode>,
    ids: VersionIds,
): List<Episode> {
    // Before any title is kept from an add-on there's no copy to look for: the usual case, kept to one read.
    val addons = sources().addons()?.id?.let(::AddonsSourceId)?.takeUnless { it.keeps(episode.sourceId) }
    val series = addons?.let { series().bySeriesId(episode.sourceId, episode.seriesId) } ?: return emptyList()
    val carried = own.mapTo(HashSet()) { it.season }
    return versionsOf(series, ids).filter { addons.keeps(it.sourceId) }
        .flatMap { copy -> episodes().forSeries(copy.sourceId, copy.seriesId) }
        .filter { it.season !in carried }
}

/** The seasons stored for [series]' Versions from a Provider, itself included when it's one. */
private suspend fun OpenTvDatabase.providerSeasons(series: Series, addons: AddonsSourceId): Set<Int> =
    versionsOf(series).filterNot { addons.keeps(it.sourceId) }
        .flatMapTo(HashSet()) { version -> episodes().forSeries(version.sourceId, version.seriesId).map { it.season } }

private fun Series.asAddonTitle(imdbId: String) = AddonTitle(
    tmdbId = checkNotNull(tmdbId),
    imdbId = imdbId,
    name = name,
    year = year,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    overview = plot,
    isMovie = false,
)
