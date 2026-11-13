/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.remote.SubtitleResult
import app.opentv.data.remote.SubtitleSearch

/** A film or episode as OpenSubtitles and the Downloaded Subtitles see it. */
data class SubtitleSubject(val title: SubtitleTitle, val search: SubtitleSearch)

/**
 * OpenSubtitles for the players: finding subtitles for a title, downloading one and keeping it as a
 * Downloaded Subtitle. Exists only in a build with Settee's OpenSubtitles key (see ServiceLocator);
 * without one there's no OpenSubtitles entry anywhere.
 */
class OpenSubtitles(
    val account: OpenSubtitlesAccount,
    val store: DownloadedSubtitles,
    private val catalog: CatalogRepository,
    private val sources: SourceRepository,
) {
    /** What to look for when [key] plays, or null for anything that isn't a film or an episode. */
    suspend fun subjectFor(key: MediaKey): SubtitleSubject? = when (key) {
        is MediaKey.Movie -> movieSubject(key.movieId)
        is MediaKey.AddonStream -> movieSubject(key.movieId)
        is MediaKey.Episode -> {
            val episode = catalog.episode(key.episodeId)
            val source = episode?.let { sources.byId(it.sourceId) }
            if (episode == null || source == null) {
                null
            } else {
                val series = catalog.seriesOf(episode)
                SubtitleSubject(
                    SubtitleTitle.of(episode, source.name),
                    SubtitleSearch.Episode(
                        show = series?.name.orEmpty(),
                        showTmdbId = series?.tmdbId?.takeIf { it.isNotBlank() },
                        season = episode.season,
                        episode = episode.episodeNumber,
                    ),
                )
            }
        }
        else -> null
    }

    private suspend fun movieSubject(movieId: Long): SubtitleSubject? {
        val movie = catalog.movie(movieId) ?: return null
        return sources.byId(movie.sourceId)?.let { source ->
            SubtitleSubject(
                SubtitleTitle.of(movie, source.name),
                SubtitleSearch.Film(movie.name, movie.year, movie.tmdbId?.takeIf { it.isNotBlank() }),
            )
        }
    }

    suspend fun search(subject: SubtitleSubject): Result<List<SubtitleResult>> = account.search(subject.search)

    /** Downloads [result] and keeps it for [subject]'s title, so it plays from then on with no new download. */
    suspend fun fetch(subject: SubtitleSubject, result: SubtitleResult): Result<DownloadedSubtitle> =
        account.download(result.fileId).map { file ->
            store.save(subject.title, result, file.text)
        }
}
