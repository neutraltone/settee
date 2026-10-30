/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.util.Log
import androidx.room.withTransaction
import app.opentv.data.db.EpisodeDao
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.db.PlaybackPositionDao
import app.opentv.data.db.SeriesDismissalDao
import app.opentv.data.model.Episode
import app.opentv.data.model.HandMark
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Series
import app.opentv.data.model.SeriesDismissal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Where the active Profile is with one movie or episode. */
sealed interface WatchStatus {
    data object Unwatched : WatchStatus

    /**
     * [progress] is how far through, 0..1; [remainingMillis] is how long is left, null when the
     * duration is unknown.
     */
    data class InProgress(val progress: Float, val remainingMillis: Long?) : WatchStatus

    data object Watched : WatchStatus
}

/** The active Profile's status for each episode, by local episode id; any episode not in it is Unwatched. */
data class EpisodeStatuses(private val byEpisodeId: Map<Long, WatchStatus> = emptyMap()) {
    operator fun get(episodeId: Long): WatchStatus = byEpisodeId[episodeId] ?: WatchStatus.Unwatched
}

/** One card in Continue Watching. */
sealed interface ContinueWatchingCard {
    /** A movie the Profile stopped part way through; [progress] is how far, 0..1. */
    data class Movie(val movieId: Long, val progress: Float) : ContinueWatchingCard

    /**
     * A series, picked back up at [episode]; [progress] is how far through it is, 0..1, or null for
     * an Up Next the Profile has never played. [newEpisode] is whether [episode] is a New Episode.
     */
    data class Series(val episode: Episode, val progress: Float?, val newEpisode: Boolean = false) :
        ContinueWatchingCard
}

/** What a series page's main button does for the active Profile. */
sealed interface SeriesMainAction {
    val episode: Episode

    /** Pick [episode] back up where the Profile stopped. */
    data class Resume(override val episode: Episode) : SeriesMainAction

    /** Start [episode]: Up Next, or the first episode when there is no Up Next. */
    data class Play(override val episode: Episode) : SeriesMainAction
}

/**
 * Everything a Profile has watched and kept: the one owner of per-Profile watch state and
 * Favourites. Screens ask it for statuses, resume points and Favourites instead of reading those
 * tables themselves.
 */
class ViewerLibrary(
    db: OpenTvDatabase,
    private val activeProfileId: StateFlow<Long>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // The database rather than a DAO each: the library owns every per-Profile table, and the list
    // only grows as watch state does.
    private val positions = db.positions()
    private val episodes = db.episodes()
    private val dismissals = db.seriesDismissals()

    /** The active Profile's Favourites, and every Profile's for sync. */
    val favourites = Favourites(db.favourites(), activeProfileId)

    private val cards = ContinueWatchingCards(positions, episodes, dismissals)

    /** The live channel the active Profile last settled on, and its Live Preview. */
    val live = LiveWatching(db.livePlayback(), activeProfileId, clock)

    /** Each episode's Next Episode, in the same episode order as Up Next. */
    val nextEpisodes = NextEpisodes(episodes)

    /** Looks for New Episodes in the series a catalogue refresh says the provider updated. */
    val newEpisodes = NewEpisodes(positions, episodes)

    /** What the active Profile sets by hand: Watched and Unwatched marks, and Continue Watching removals. */
    val marks = HandMarks(positions, episodes, dismissals, activeProfileId, clock)

    /** Removing a Profile's watch state and Favourites, for the Profiles module. */
    val profileRemoval = ProfileRemoval(db)

    suspend fun recordProgress(mediaKey: String, positionMillis: Long, durationMillis: Long) {
        positions.upsert(
            PlaybackPosition(
                profileId = activeProfileId.value,
                mediaKey = mediaKey,
                positionMillis = positionMillis,
                durationMillis = durationMillis,
                updatedAtMillis = clock(),
            ),
        )
        live.playedOther()
    }

    /**
     * Takes progress synced from another device if it is newer than this device's for the same
     * Profile and title (newest wins, so watching on either device resumes on the other).
     * Returns whether it was taken.
     */
    suspend fun mergeSynced(position: PlaybackPosition): Boolean {
        val existing = positions.get(position.profileId, position.mediaKey)
        if (existing != null && position.updatedAtMillis <= existing.updatedAtMillis) return false
        positions.upsert(position)
        return true
    }

    /** Every Profile's progress on this device — what sync sends to the other side. */
    suspend fun everyProfilesProgress(): List<PlaybackPosition> = positions.all()

    /** Where to start [mediaKey] for the active Profile: where they stopped, or 0 if Watched or never played. */
    suspend fun resumeFrom(mediaKey: String): Long {
        val pos = positions.get(activeProfileId.value, mediaKey) ?: return 0
        return if (statusOf(pos) is WatchStatus.InProgress) pos.positionMillis.coerceAtLeast(0) else 0
    }

    /**
     * Every movie [profileId] has started, Watched or not: genre-affinity recommendations count a
     * film abandoned half way as a taste signal too, but not one marked Unwatched (taken out of
     * Continue Watching, say), which was given up on deliberately or never seen. Takes the Profile
     * explicitly because the home feed computes it for whichever Profile it is building for.
     */
    suspend fun startedMovieIds(profileId: Long): Set<Long> = positions.forProfile(profileId)
        .filter { it.mediaKey.startsWith(MOVIE_PREFIX) && it.mark != HandMark.UNWATCHED }
        .mapNotNull { it.mediaKey.removePrefix(MOVIE_PREFIX).toLongOrNull() }
        .toSet()

    /** The active Profile's Continue Watching for movies: each In Progress one, most recently played first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun continueWatchingMovies(): Flow<List<ContinueWatchingCard>> = activeProfileId
        .flatMapLatest { pid -> cards.movies(pid) }
        .map { list -> list.map { it.card } }

    /**
     * The active Profile's Continue Watching for series: one card per series, at the episode it
     * played most recently if that is In Progress, or else its Up Next.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun continueWatchingSeries(): Flow<List<ContinueWatchingCard>> = activeProfileId
        .flatMapLatest { pid -> cards.series(pid) }
        .map { list -> list.map { it.card } }

    /** Home's Continue Watching: movies and series together, most recently played first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun continueWatching(): Flow<List<ContinueWatchingCard>> = activeProfileId
        .flatMapLatest { pid ->
            combine(cards.movies(pid), cards.series(pid)) { movies, series ->
                (movies + series).sortedByDescending { it.playedAtMillis }.take(IN_PROGRESS_LIMIT).map { it.card }
            }
        }

    /** Every episode's status for the active Profile; re-emits only when a status actually changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun episodeStatuses(): Flow<EpisodeStatuses> = activeProfileId
        .flatMapLatest { pid -> positions.observeKind(pid, EPISODE_PREFIX) }
        .map { list ->
            EpisodeStatuses(
                list.mapNotNull { pos ->
                    pos.mediaKey.removePrefix(EPISODE_PREFIX).toLongOrNull()?.let { it to statusOf(pos) }
                }.toMap(),
            )
        }
        .distinctUntilChanged()

    /** The series page's main action over [episodes] (one series' episodes, any order); null when there are none. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun seriesMainAction(episodes: List<Episode>): Flow<SeriesMainAction?> {
        val ordered = episodes.sortedWith(EPISODE_ORDER)
        val indexByKey = ordered.withIndex().associate { (i, ep) -> "$EPISODE_PREFIX${ep.id}" to i }
        // Season 0 is where providers put specials: starting a series there would open on a
        // trailer or a behind-the-scenes, so the start is S1 E1 unless specials are all there is.
        val start = ordered.firstOrNull { it.season > 0 } ?: ordered.firstOrNull() ?: return flowOf(null)
        return activeProfileId
            .flatMapLatest { pid -> positions.observeKind(pid, EPISODE_PREFIX) }
            .map<List<PlaybackPosition>, SeriesMainAction?> { list ->
                val latest = list.filter { it.mediaKey in indexByKey && it.mark != HandMark.UNWATCHED }
                    .maxByOrNull { it.updatedAtMillis }
                val at = latest?.let { indexByKey.getValue(it.mediaKey) }
                when {
                    at == null -> SeriesMainAction.Play(start)
                    statusOf(latest) is WatchStatus.InProgress -> SeriesMainAction.Resume(ordered[at])
                    // No Up Next after the last episode: offer the series from the start.
                    else -> SeriesMainAction.Play(ordered.getOrNull(at + 1) ?: start)
                }
            }
            .distinctUntilChanged()
    }
}

/** Fetches the episodes a New Episode could be among; Continue Watching then shows what it finds. */
/**
 * Removes a Profile from every table the library owns, in one transaction so a removal cut short
 * leaves the Profile whole rather than half forgotten. Reached as `viewerLibrary.profileRemoval`.
 */
class ProfileRemoval internal constructor(private val db: OpenTvDatabase) {
    suspend fun forget(profileId: Long) = db.withTransaction {
        db.positions().deleteFor(profileId)
        db.seriesDismissals().deleteFor(profileId)
        db.favourites().deleteFor(profileId)
        db.livePlayback().deleteFor(profileId)
    }
}

class NewEpisodes internal constructor(private val positions: PlaybackPositionDao, private val episodes: EpisodeDao) {
    /**
     * Fetches the episodes of each of [updated], series the provider says changed, to find New
     * Episodes. [fetchEpisodes] loads and stores one series' episodes from its provider.
     */
    suspend fun seriesUpdated(updated: List<Series>, fetchEpisodes: suspend (Series) -> Unit) {
        if (updated.isEmpty()) return
        // Only a series some Profile is caught up on can gain a New Episode; fetching the rest
        // would be a panel call per updated show for nothing.
        // An episode marked Unwatched is as if never played, as in Continue Watching.
        val everyPosition = positions.all()
            .filter { it.mediaKey.startsWith(EPISODE_PREFIX) && it.mark != HandMark.UNWATCHED }
        for (series in updated) {
            if (!someoneIsCaughtUp(series, everyPosition)) continue
            // One show's panel timing out must not cost the others their New Episodes.
            runCatching { fetchEpisodes(series) }
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { Log.w(TAG, "Episode fetch failed for series ${series.seriesId}", it) }
        }
    }

    /** Whether, for any Profile, the episode of [series] it played last is Watched and has nothing after it. */
    private suspend fun someoneIsCaughtUp(series: Series, everyPosition: List<PlaybackPosition>): Boolean {
        val ordered = episodes.forSeries(series.sourceId, series.seriesId).sortedWith(EPISODE_ORDER)
        val last = ordered.lastOrNull() ?: return false
        val keys = ordered.mapTo(HashSet()) { "$EPISODE_PREFIX${it.id}" }
        return everyPosition.filter { it.mediaKey in keys }
            .groupBy { it.profileId }
            .values
            .any { mine ->
                val latest = mine.maxBy { it.updatedAtMillis }
                latest.mediaKey == "$EPISODE_PREFIX${last.id}" && statusOf(latest) == WatchStatus.Watched
            }
    }
}

/** Answers the Next Episode, kept beside Up Next so the two can't drift apart in how they order episodes. */
class NextEpisodes internal constructor(private val episodes: EpisodeDao) {
    /**
     * The Next Episode after [episode]: the one that follows it in season then episode order,
     * whatever the Profile last Watched (unlike Up Next). Null after a series' last episode, or
     * when the series' episodes aren't loaded.
     */
    suspend fun after(episode: Episode): Episode? {
        val ordered = episodes.forSeries(episode.sourceId, episode.seriesId).sortedWith(EPISODE_ORDER)
        val at = ordered.indexOfFirst { it.id == episode.id }
        return if (at < 0) null else ordered.getOrNull(at + 1)
    }
}

/**
 * The hand-set half of [ViewerLibrary], reached as `viewerLibrary.marks`: a Profile marking a title
 * Watched or Unwatched, or taking a card out of Continue Watching, and where it stands with one
 * title, which is what the options panel offers marks from. Only ViewerLibrary builds one.
 */
class HandMarks internal constructor(
    private val positions: PlaybackPositionDao,
    private val episodes: EpisodeDao,
    private val dismissals: SeriesDismissalDao,
    private val activeProfileId: StateFlow<Long>,
    private val clock: () -> Long,
) {
    /** Where the active Profile is with the movie or episode [mediaKey]. */
    suspend fun statusOf(mediaKey: String): WatchStatus =
        positions.get(activeProfileId.value, mediaKey)?.let(::statusOf) ?: WatchStatus.Unwatched

    /** Marks [mediaKey] Watched for the active Profile, whether or not it was ever played. */
    suspend fun markWatched(mediaKey: String) = mark(mediaKey, HandMark.WATCHED)

    /** Marks [mediaKey] Unwatched for the active Profile: no resume point, and Up Next worked out without it. */
    suspend fun markUnwatched(mediaKey: String) = mark(mediaKey, HandMark.UNWATCHED)

    /** Takes [card] out of the active Profile's Continue Watching. */
    suspend fun removeFromContinueWatching(card: ContinueWatchingCard) {
        when (card) {
            // Removing a movie is giving up on it, so it is not picked back up there either.
            is ContinueWatchingCard.Movie -> markUnwatched("$MOVIE_PREFIX${card.movieId}")
            is ContinueWatchingCard.Series -> {
                val ep = card.episode
                if (card.progress != null) markUnwatched("$EPISODE_PREFIX${ep.id}")
                val last = episodes.lastOfSeries(ep.sourceId, ep.seriesId) ?: ep
                dismissals.upsert(
                    SeriesDismissal(
                        profileId = activeProfileId.value,
                        sourceId = ep.sourceId,
                        seriesId = ep.seriesId,
                        dismissedAtMillis = clock(),
                        lastSeason = last.season,
                        lastEpisode = last.episodeNumber,
                    ),
                )
            }
        }
    }

    private suspend fun mark(mediaKey: String, mark: HandMark) {
        val pid = activeProfileId.value
        val existing = positions.get(pid, mediaKey)
        positions.upsert(
            PlaybackPosition(
                profileId = pid,
                mediaKey = mediaKey,
                positionMillis = existing?.positionMillis ?: 0,
                durationMillis = existing?.durationMillis ?: 0,
                updatedAtMillis = clock(),
                mark = mark,
            ),
        )
    }

    /**
     * Takes a series' removal from Continue Watching synced from another device if it is newer
     * than this device's for the same Profile and series. Returns whether it was taken.
     */
    suspend fun mergeSynced(dismissal: SeriesDismissal): Boolean {
        val existing = dismissals.get(dismissal.profileId, dismissal.sourceId, dismissal.seriesId)
        if (existing != null && dismissal.dismissedAtMillis <= existing.dismissedAtMillis) return false
        dismissals.upsert(dismissal)
        return true
    }

    /** Every Profile's series removed from Continue Watching on this device — what sync sends. */
    suspend fun everyProfiles(): List<SeriesDismissal> = dismissals.all()
}

/** A card with when the Profile last played what it stands for, so movies and series can be interleaved. */
private data class Played(val card: ContinueWatchingCard, val playedAtMillis: Long)

/**
 * Builds each kind's Continue Watching cards for one Profile, each with when it was last played;
 * ViewerLibrary picks the Profile and decides how kinds are listed.
 */
private class ContinueWatchingCards(
    private val positions: PlaybackPositionDao,
    private val episodes: EpisodeDao,
    private val dismissals: SeriesDismissalDao,
) {
    fun movies(pid: Long): Flow<List<Played>> = positions.observeNewestFirst(pid).map { list ->
        list.asSequence()
            .filter { it.mediaKey.startsWith(MOVIE_PREFIX) }
            .mapNotNull { pos ->
                val id = pos.mediaKey.removePrefix(MOVIE_PREFIX).toLongOrNull() ?: return@mapNotNull null
                (statusOf(pos) as? WatchStatus.InProgress)?.let {
                    Played(ContinueWatchingCard.Movie(id, it.progress), pos.updatedAtMillis)
                }
            }
            .take(IN_PROGRESS_LIMIT)
            .toList()
    }

    // Played episodes are observed as well as positions so a newly added Up Next shows up.
    fun series(pid: Long): Flow<List<Played>> = combine(
        positions.observeNewestFirst(pid),
        episodes.observePlayed(pid),
        dismissals.observe(pid),
    ) { list, played, dismissed ->
        val dismissalOf = dismissed.associateBy { it.sourceId to it.seriesId }
        val byKey = played.associateBy { "$EPISODE_PREFIX${it.id}" }
        val positionOf = list.associateBy { it.mediaKey }
        // One query for every played series' episodes: each card is built before the cap, and
        // a query per series on every position written would be felt on a Fire TV Stick.
        val episodesOf = episodes.ofPlayedSeries(pid)
            .groupBy { it.sourceId to it.seriesId }
            .mapValues { (_, eps) -> eps.sortedWith(EPISODE_ORDER) }
        // Newest first, so the first position seen per series is when it was last played or marked.
        val touchedAt = HashMap<Pair<Long, String>, Long>()
        for (pos in list) {
            byKey[pos.mediaKey]?.let {
                touchedAt.putIfAbsent(it.sourceId to it.seriesId, pos.updatedAtMillis)
            }
        }
        // An episode marked Unwatched is as if never played: Up Next comes from what remains.
        val latestPerSeries = list.filter { it.mark != HandMark.UNWATCHED }
            .mapNotNull { pos -> byKey[pos.mediaKey]?.let { it to pos } }
            .distinctBy { (ep, _) -> ep.sourceId to ep.seriesId }
        // Capped on cards, not series: a series with nothing left to watch takes no slot. Every
        // series is built before the cap, since a New Episode can lift one long unplayed to the top.
        latestPerSeries
            .mapNotNull { (ep, pos) ->
                val series = ep.sourceId to ep.seriesId
                val dismissal = dismissalOf[series]
                if (dismissal != null && stillDismissed(dismissal, touchedAt.getValue(series))) return@mapNotNull null
                seriesCard(ep, pos, episodesOf[series].orEmpty(), positionOf)?.let { Played(it, placedAt(it, pos)) }
            }
            .sortedByDescending { it.playedAtMillis }
            .take(IN_PROGRESS_LIMIT)
    }

    private suspend fun stillDismissed(dismissal: SeriesDismissal, touchedAtMillis: Long): Boolean {
        if (touchedAtMillis > dismissal.dismissedAtMillis) return false
        val newest = episodes.lastOfSeries(dismissal.sourceId, dismissal.seriesId)
        // Any episode after the last one there was when the series was removed is a new one.
        return newest == null ||
            newest.season < dismissal.lastSeason ||
            (newest.season == dismissal.lastSeason && newest.episodeNumber <= dismissal.lastEpisode)
    }

    /** When a card counts as played for ordering: a New Episode goes by when it was noticed. */
    private fun placedAt(card: ContinueWatchingCard.Series, pos: PlaybackPosition): Long =
        if (card.newEpisode) maxOf(pos.updatedAtMillis, card.episode.firstSeenMillis) else pos.updatedAtMillis

    private fun seriesCard(
        latest: Episode,
        pos: PlaybackPosition,
        ordered: List<Episode>,
        positionOf: Map<String, PlaybackPosition>,
    ): ContinueWatchingCard.Series? = when (val status = statusOf(pos)) {
        is WatchStatus.InProgress -> ContinueWatchingCard.Series(latest, status.progress)
        else -> {
            ordered.getOrNull(ordered.indexOfFirst { it.id == latest.id } + 1)?.let { next ->
                // Up Next may have been started before an earlier episode was finished; the player
                // resumes it there, so the card shows that progress rather than none.
                val started = positionOf["$EPISODE_PREFIX${next.id}"]?.let(::statusOf) as? WatchStatus.InProgress
                // Nothing to mark it but its arriving after the Profile had Watched the one before.
                val isNew = started == null && next.firstSeenMillis > pos.updatedAtMillis
                ContinueWatchingCard.Series(next, started?.progress, newEpisode = isNew)
            }
        }
    }
}

/** The key ViewerLibrary keeps a Profile's watch state for this movie under. */
val Movie.mediaKey: String get() = MediaKey.Movie(id).stored

/** The key ViewerLibrary keeps a Profile's watch state for this episode under. */
val Episode.mediaKey: String get() = MediaKey.Episode(id).stored

private val EPISODE_ORDER = compareBy<Episode>({ it.season }, { it.episodeNumber })

// What each kind of stored key starts with, for matching in SQL and in bulk; MediaKey owns the form.
private const val EPISODE_PREFIX = "${MediaKey.EPISODE}:"
private const val MOVIE_PREFIX = "${MediaKey.MOVIE}:"
private const val IN_PROGRESS_LIMIT = 30
private const val TAG = "ViewerLibrary"

/** Past this, the credits are rolling: the title counts as Watched and is not resumed. */
private const val WATCHED_FRACTION = 0.93

private fun statusOf(pos: PlaybackPosition): WatchStatus = when (pos.mark) {
    HandMark.WATCHED -> WatchStatus.Watched
    HandMark.UNWATCHED -> WatchStatus.Unwatched
    null -> playedStatusOf(pos)
}

private fun playedStatusOf(pos: PlaybackPosition): WatchStatus {
    // Synced positions can arrive without a duration; that says nothing about how far through.
    // Nor does a negative position, which only a confused peer would send.
    val position = pos.positionMillis.coerceAtLeast(0)
    val known = pos.durationMillis > 0
    val progress = if (known) position.toDouble() / pos.durationMillis else 0.0
    if (progress > WATCHED_FRACTION) return WatchStatus.Watched
    return WatchStatus.InProgress(progress.toFloat(), if (known) pos.durationMillis - position else null)
}
