/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.remote.AddonFailure
import app.opentv.data.repo.AddonOutcome
import app.opentv.data.repo.AddonStreamResults
import app.opentv.data.repo.MediaKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * What a title's Add-on sources picker shows, growing as each add-on answers: for the title pages, and for
 * the player, which asks when it's opened on a title kept from an add-on (ADR 0011).
 */
class AddonSearches(private val graph: ServiceLocator.Graph) {

    /**
     * What the viewer's add-ons offer for [movie]. The film is matched to IMDb through TMDB first, since
     * that's what add-ons are asked by; with no match they can't be asked.
     */
    fun forFilm(movie: Movie): Flow<AddonSearch> =
        search(aboutShow = false, { graph.addonTitles.imdbIdOf(movie) }) { graph.addonStreams.forFilm(it) }

    /**
     * What the viewer's add-ons offer for [episode] of [series], as for a film: add-ons are asked by the
     * show's IMDb id plus the episode's season and number, so with no IMDb match they can't be asked.
     */
    fun forEpisode(series: Series, episode: Episode): Flow<AddonSearch> =
        search(aboutShow = true, { graph.addonTitles.imdbIdOf(series) }) {
            graph.addonStreams.forEpisode(it, episode.season, episode.episodeNumber)
        }

    /** [forFilm] or [forEpisode] for the title [title] names; nothing when it's no longer in the catalogue. */
    fun forTitle(title: MediaKey.Title): Flow<AddonSearch> = flow {
        val catalog = graph.catalogRepository
        when (title) {
            is MediaKey.Movie -> catalog.movie(title.movieId)?.let { emitAll(forFilm(it)) }
            is MediaKey.Episode -> catalog.episode(title.episodeId)?.let { episode ->
                catalog.seriesOf(episode)?.let { emitAll(forEpisode(it, episode)) }
            }
        }
    }

    private fun search(
        aboutShow: Boolean,
        imdbId: suspend () -> Result<String?>,
        ask: (imdbId: String) -> Flow<AddonStreamResults>,
    ): Flow<AddonSearch> = flow {
        imdbId().fold(
            onSuccess = { imdb ->
                if (imdb == null) {
                    // IMDb's id is what add-ons are asked by, so with none they can't be asked at all.
                    val sentence = if (aboutShow) R.string.error_addon_no_match_show else R.string.error_addon_no_match
                    val noMatch = graph.errorWording.of(sentence, "NoImdbMatch")
                    emit(AddonSearch.CantAsk(noMatch, canRetry = false))
                } else {
                    emitAll(ask(imdb).map { it.toSearch() })
                }
            },
            onFailure = {
                val failure = graph.errorWording.ofAddon(AddonFailure.NoLookup(it), aboutShow)
                emit(AddonSearch.CantAsk(failure, canRetry = true))
            },
        )
    }

    private fun AddonStreamResults.toSearch() = AddonSearch.Results(
        streams = streams,
        finished = finished,
        // Read as each answer lands rather than watched: the switch can't change under an open picker.
        torrentsOn = graph.settings.torrentsEnabled.value,
        failure = outcomes.firstNotNullOfOrNull { (it as? AddonOutcome.Unreachable)?.failure }
            ?.let(graph.errorWording::ofAddon),
    )
}
