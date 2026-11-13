/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.model.Episode

/**
 * A show's episodes across its Versions, in season and episode order: every one of the Version
 * [opened], repeats included, and from [others] each season and episode number it doesn't have. Which
 * Version an episode plays from is the Plan's to say, not this list's (ADR 0007).
 */
fun showEpisodes(opened: List<Episode>, others: List<List<Episode>>): List<Episode> {
    val have = opened.mapTo(HashSet()) { it.season to it.episodeNumber }
    val added = others.flatten().filter { have.add(it.season to it.episodeNumber) }
    return (opened + added).sortedWith(compareBy({ it.season }, { it.episodeNumber }))
}

/**
 * A show's episodes for its page: [shown], one row per episode across its Versions ([showEpisodes]), and
 * [all] of every Version, which its main button and status read, so an episode Watched in one Version
 * moves Up Next on in all of them.
 */
data class ShowEpisodes(val shown: List<Episode> = emptyList(), val all: List<Episode> = emptyList()) {
    /** [statuses] as the page shows them: an episode is Watched when any other Version of it is. */
    fun statusesFrom(statuses: EpisodeStatuses): EpisodeStatuses {
        val bySeasonAndNumber = all.groupBy { it.season to it.episodeNumber }
        return EpisodeStatuses(
            shown.associate { episode ->
                val inOtherVersions = bySeasonAndNumber[episode.season to episode.episodeNumber].orEmpty()
                    .filter { it.sourceId != episode.sourceId || it.seriesId != episode.seriesId }
                episode.id to statuses.anyOf(inOtherVersions.map { it.id } + episode.id)
            },
        )
    }

    /** [action] with its episode as the page lists it, which may be another Version's. */
    fun shownAs(action: SeriesMainAction): SeriesMainAction = when (action) {
        is SeriesMainAction.Resume -> SeriesMainAction.Resume(shownAs(action.episode))
        is SeriesMainAction.Play -> SeriesMainAction.Play(shownAs(action.episode))
    }

    /** The row [episode] is shown as: itself, or the same episode of the Version listed. */
    fun shownAs(episode: Episode): Episode = shown.firstOrNull { it.id == episode.id }
        ?: shown.firstOrNull { it.season == episode.season && it.episodeNumber == episode.episodeNumber }
        ?: episode

    companion object {
        fun of(opened: List<Episode>, others: List<List<Episode>>) =
            ShowEpisodes(showEpisodes(opened, others), opened + others.flatten())
    }
}
