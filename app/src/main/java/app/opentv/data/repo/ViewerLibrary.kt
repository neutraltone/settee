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
import app.opentv.data.model.RecentSearch
import app.opentv.data.model.Series
import app.opentv.data.model.SeriesDismissal
import app.opentv.data.model.SeriesRef
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

/**
 * What a Favourites or Recently Added cover says about where the active Profile is with its title;
 * a title it has never started has none.
 */
sealed interface CoverStatus {
    /** Part way through [episode] (null for a movie), with [remainingMillis] left when the duration is known. */
    data class InProgress(val progress: Float, val remainingMillis: Long?, val episode: Episode? = null) : CoverStatus

    /** Between episodes of a show the Profile is following: [episode] is its Up Next, not yet started. */
    data class UpNext(val episode: Episode) : CoverStatus

    /** A show's Up Next arrived after the Profile had Watched everything else. */
    data object NewEpisode : CoverStatus

    data object Watched : CoverStatus
}

/** The active Profile's [CoverStatus] for each title it has started. */
data class CoverStatuses internal constructor(
    private val movies: Map<Long, CoverStatus> = emptyMap(),
    private val series: Map<SeriesRef, CoverStatus> = emptyMap(),
) {
    fun ofMovie(movieId: Long): CoverStatus? = movies[movieId]

    fun ofSeries(ref: SeriesRef): CoverStatus? = series[ref]
}

/** What Home shows of the active Profile's watching: its Continue Watching and each cover's status. */
data class HomeLibrary(val continueWatching: List<ContinueWatchingCard>, val covers: CoverStatuses)

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
    val favourites = Favourites(db, activeProfileId, clock)

    private val cards = ContinueWatchingCards(positions, episodes, dismissals)

    /** The live channel the active Profile last settled on, and its Live Preview. */
    val live = LiveWatching(db.livePlayback(), activeProfileId, clock)

    /** Each episode's Next Episode, in the same episode order as Up Next. */
    val nextEpisodes = NextEpisodes(episodes, ::savePosition)

    /** Looks for New Episodes in the series a catalogue refresh says the provider updated. */
    val newEpisodes = NewEpisodes(positions, episodes)

    /** What the active Profile sets by hand: Watched and Unwatched marks, and Continue Watching removals. */
    val marks = HandMarks(positions, episodes, dismissals, activeProfileId, clock)

    /** Hand marks over many episodes at once, for catching up on a series started elsewhere. */
    val catchUp = CatchUpMarks(positions, episodes, activeProfileId, clock) { db.withTransaction(it) }

    /** Every Profile's progress, as it travels between devices. */
    val syncedProgress = SyncedProgress(positions, db.movies(), episodes, db.sources(), db.profiles()) { placed ->
        db.mergeForProfiles(placed, { it.profileId }, ::mergeSynced)
    }

    /** Every Profile's Continue Watching removals, as they travel between devices. */
    val syncedDismissals = SyncedDismissals(dismissals, db.sources(), db.profiles()) { placed ->
        db.mergeForProfiles(placed, { it.profileId }, marks::mergeSynced)
    }

    /** The active Profile's Recent Searches. */
    val recentSearches = RecentSearches(db, activeProfileId, clock)

    /** Which titles any Profile has started or given up on, for what the home feeds recommend. */
    val history = TitleHistory(positions, episodes, dismissals)

    /** Removing a Profile's watch state and Favourites, for the Profiles module. */
    val profileRemoval = ProfileRemoval(db)

    /** Merging one Profile's watch state and Favourites into another's, for the Profiles module. */
    val profileMerge = ProfileMerge(db, ::mergeSynced, marks::mergeSynced, recentSearches)

    /**
     * Saves how far through [mediaKey] the active Profile is, for resuming it and for its Watched
     * status. Returns whether it was saved: the first seconds say nothing about wanting to come
     * back to it, and with no known duration there's no telling how far through it is, so either
     * leaves any earlier resume point alone.
     */
    suspend fun recordProgress(mediaKey: String, positionMillis: Long, durationMillis: Long): Boolean {
        if (durationMillis <= 0 || positionMillis <= MIN_SAVED_MILLIS) return false
        savePosition(mediaKey, positionMillis, durationMillis)
        return true
    }

    private suspend fun savePosition(mediaKey: String, positionMillis: Long, durationMillis: Long) {
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
    internal suspend fun mergeSynced(position: PlaybackPosition): Boolean {
        val existing = positions.get(position.profileId, position.mediaKey)
        if (existing != null && position.updatedAtMillis <= existing.updatedAtMillis) return false
        positions.upsert(position)
        return true
    }

    /** Where to start [mediaKey] for the active Profile: where they stopped, or 0 if Watched or never played. */
    suspend fun resumeFrom(mediaKey: String): Long {
        val pos = positions.get(activeProfileId.value, mediaKey) ?: return 0
        return if (statusOf(pos) is WatchStatus.InProgress) pos.positionMillis.coerceAtLeast(0) else 0
    }

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
    fun continueWatching(): Flow<List<ContinueWatchingCard>> = home().map { it.continueWatching }

    /**
     * Home's Continue Watching and its covers' statuses together, from one read of what the active
     * Profile has played: apart, every position saved would read every played series twice.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun home(): Flow<HomeLibrary> = activeProfileId
        .flatMapLatest { pid -> cards.played(pid) }
        .map { played ->
            val continueWatching = (cards.moviesFrom(played.positions) + cards.seriesFrom(played))
                .sortedByDescending { it.playedAtMillis }
                .take(IN_PROGRESS_LIMIT)
                .map { it.card }
            HomeLibrary(continueWatching, coverStatusesIn(played))
        }

    /** Every episode's status for the active Profile; re-emits only when a status actually changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun episodeStatuses(): Flow<EpisodeStatuses> = activeProfileId
        .flatMapLatest { pid -> positions.observeKind(pid, EPISODE_PREFIX) }
        .map { list ->
            EpisodeStatuses(
                list.mapNotNull { pos -> pos.episodeId?.let { it to statusOf(pos) } }.toMap(),
            )
        }
        .distinctUntilChanged()

    /** The series page's main action over [episodes] (one series' episodes, any order); null when there are none. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun seriesMainAction(episodes: List<Episode>): Flow<SeriesMainAction?> {
        val series = SeriesEpisodes(episodes)
        // Season 0 is where providers put specials: starting a series there would open on a
        // trailer or a behind-the-scenes, so the start is S1 E1 unless specials are all there is.
        val start = series.ordered.firstOrNull { it.season > 0 } ?: series.ordered.firstOrNull() ?: return flowOf(null)
        return activeProfileId
            .flatMapLatest { pid -> positions.observeKind(pid, EPISODE_PREFIX) }
            .map<List<PlaybackPosition>, SeriesMainAction?> { list ->
                val progress = series.progressIn(list)
                when {
                    progress == null -> SeriesMainAction.Play(start)
                    progress.status is WatchStatus.InProgress -> SeriesMainAction.Resume(progress.latest)
                    // No Up Next after the last episode: offer the series from the start.
                    else -> SeriesMainAction.Play(progress.upNext ?: start)
                }
            }
            .distinctUntilChanged()
    }
}

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
        db.recentSearches().deleteFor(profileId)
    }
}

/**
 * Moves everything one Profile has in the library's tables onto another, newest wins wherever both
 * have the same thing, leaving nothing behind. Reached as `viewerLibrary.profileMerge`.
 */
class ProfileMerge internal constructor(
    private val db: OpenTvDatabase,
    private val mergePosition: suspend (PlaybackPosition) -> Boolean,
    private val mergeDismissal: suspend (SeriesDismissal) -> Boolean,
    private val recentSearches: RecentSearches,
) {
    suspend fun merge(from: Long, into: Long) = db.withTransaction {
        db.positions().forProfile(from).forEach { mergePosition(it.copy(profileId = into)) }
        db.positions().deleteFor(from)
        db.seriesDismissals().all().filter {
            it.profileId == from
        }.forEach { mergeDismissal(it.copy(profileId = into)) }
        db.seriesDismissals().deleteFor(from)
        mergeFavourites(from, into)
        recentSearches.mergeProfile(from, into)
        mergeLivePlayback(from, into)
    }

    private suspend fun mergeFavourites(from: Long, into: Long) {
        val dao = db.favourites()
        dao.all().filter { it.profileId == from }.forEach { fav ->
            val here = dao.find(into, fav.kind, fav.source, fav.itemId)
            if (isNewerChange(fav.changedAtMillis, here?.changedAtMillis)) dao.upsert(fav.copy(profileId = into))
        }
        dao.deleteFor(from)
    }

    private suspend fun mergeLivePlayback(from: Long, into: Long) {
        val dao = db.livePlayback()
        val theirs = dao.get(from) ?: return
        val ours = dao.get(into)
        val newest = if (ours == null || theirs.playedAtMillis > ours.playedAtMillis) theirs else ours
        // Kept apart from which channel wins: it's what says live TV is no longer the latest playback.
        val otherPlayedAt = listOfNotNull(theirs.otherPlayedAtMillis, ours?.otherPlayedAtMillis).maxOrNull()
        dao.upsert(newest.copy(profileId = into, otherPlayedAtMillis = otherPlayedAt))
        dao.deleteFor(from)
    }
}

/**
 * The active Profile's last [MAX] searches that a result was opened from, newest first. Reached as
 * `viewerLibrary.recentSearches`.
 */
class RecentSearches internal constructor(
    private val db: OpenTvDatabase,
    private val activeProfileId: StateFlow<Long>,
    private val clock: () -> Long,
) {
    private val dao = db.recentSearches()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<List<String>> = activeProfileId.flatMapLatest { dao.observe(it) }

    /**
     * Saves [typed], moving it to the top if the Profile has it already. Matched here rather than
     * with SQLite's NOCASE, which only folds ASCII and so would keep "ÉCOLE" and "école" apart.
     */
    suspend fun save(typed: String) {
        val text = typed.trim()
        if (text.isEmpty()) return
        val profileId = activeProfileId.value
        db.withTransaction {
            val (same, others) = dao.forProfile(profileId).partition { it.equals(text, ignoreCase = true) }
            (same + others.drop(MAX - 1)).forEach { dao.delete(profileId, it) }
            dao.insert(RecentSearch(profileId, text, clock()))
        }
    }

    suspend fun remove(text: String) = dao.delete(activeProfileId.value, text)

    suspend fun clear() = dao.deleteFor(activeProfileId.value)

    /** Profile [from] is merging into [into]: the newest of both Profiles' searches stay, once each. */
    internal suspend fun mergeProfile(from: Long, into: Long) {
        val newest = (dao.rowsFor(from) + dao.rowsFor(into))
            .sortedByDescending { it.searchedAtMillis }
            .distinctBy { it.text.lowercase() }
            .take(MAX)
        dao.deleteFor(from)
        dao.deleteFor(into)
        // Oldest first: the list reads newest first by row order, not by the time stored on each.
        newest.asReversed().forEach { dao.insert(it.copy(profileId = into)) }
    }

    private companion object {
        const val MAX = 10
    }
}

/** Fetches the episodes a New Episode could be among; Continue Watching then shows what it finds. */
class NewEpisodes internal constructor(private val positions: PlaybackPositionDao, private val episodes: EpisodeDao) {
    /**
     * Fetches the episodes of each of [updated], series the provider says changed, to find New
     * Episodes. [fetchEpisodes] loads and stores one series' episodes from its provider.
     */
    suspend fun seriesUpdated(updated: List<Series>, fetchEpisodes: suspend (Series) -> Unit) {
        if (updated.isEmpty()) return
        // Only a series some Profile is caught up on can gain a New Episode; fetching the rest
        // would be a panel call per updated show for nothing.
        val perProfile = positions.all()
            .filter { it.mediaKey.startsWith(EPISODE_PREFIX) }
            .groupBy { it.profileId }
            .values
        for (series in updated) {
            val ofSeries = SeriesEpisodes(episodes.forSeries(series.sourceId, series.seriesId))
            if (perProfile.none { ofSeries.progressIn(it)?.caughtUp == true }) continue
            // One show's panel timing out must not cost the others their New Episodes.
            runCatching { fetchEpisodes(series) }
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { Log.w(TAG, "Episode fetch failed for series ${series.seriesId}", it) }
        }
    }
}

/** Answers the Next Episode, kept beside Up Next so the two can't drift apart in how they order episodes. */
class NextEpisodes internal constructor(
    private val episodes: EpisodeDao,
    private val savePosition: suspend (mediaKey: String, positionMillis: Long, durationMillis: Long) -> Unit,
) {
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

    /**
     * The Profile moved on from [mediaKey] at the Next Episode card, which counts as finishing it:
     * on a short episode the last 15 seconds can fall short of Watched, so it is saved at its whole
     * [durationMillis]. With no known duration there is nothing to say how far that is, so nothing
     * is saved.
     */
    suspend fun movedOnFrom(mediaKey: String, durationMillis: Long) {
        if (durationMillis > 0) savePosition(mediaKey, durationMillis, durationMillis)
    }
}

/**
 * Which titles a Profile has started or given up on. Each takes the Profile explicitly because the
 * home feed computes them for whichever Profile it is building for.
 */
class TitleHistory internal constructor(
    private val positions: PlaybackPositionDao,
    private val episodes: EpisodeDao,
    private val dismissals: SeriesDismissalDao,
) {
    /**
     * Every movie [profileId] has started, Watched or not: genre-affinity recommendations count a
     * film abandoned half way as a taste signal too, but not one marked Unwatched (taken out of
     * Continue Watching, say), which was given up on deliberately or never seen.
     */
    suspend fun startedMovieIds(profileId: Long): Set<Long> = positions.forProfile(profileId)
        .filter { it.mark != HandMark.UNWATCHED }
        .mapNotNull { it.movieId }
        .toSet()

    /**
     * Every series [profileId] has started: it has progress on any episode that still exists, Watched
     * or not, with the same exception as [startedMovieIds].
     */
    suspend fun startedSeries(profileId: Long): Set<SeriesRef> =
        episodes.startedSeries(profileId, except = HandMark.UNWATCHED).toSet()

    /**
     * Every series [profileId] has removed from Continue Watching, even if its card has since come
     * back: recommending a show someone dismissed would feel wrong.
     */
    suspend fun dismissedSeries(profileId: Long): Set<SeriesRef> = dismissals.forProfile(profileId)
        .mapTo(HashSet()) { SeriesRef(it.sourceId, it.seriesId) }
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
            is ContinueWatchingCard.Movie -> markUnwatched(MediaKey.Movie(card.movieId).stored)
            is ContinueWatchingCard.Series -> {
                val ep = card.episode
                if (card.progress != null) markUnwatched(ep.mediaKey)
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
        positions.upsert(markedPosition(pid, mediaKey, positions.get(pid, mediaKey), mark, clock()))
    }

    /**
     * Takes a series' removal from Continue Watching synced from another device if it is newer
     * than this device's for the same Profile and series. Returns whether it was taken.
     */
    internal suspend fun mergeSynced(dismissal: SeriesDismissal): Boolean {
        val existing = dismissals.get(dismissal.profileId, dismissal.sourceId, dismissal.seriesId)
        if (existing != null && dismissal.dismissedAtMillis <= existing.dismissedAtMillis) return false
        dismissals.upsert(dismissal)
        return true
    }
}

/**
 * Marks by the batch, for catching up on a series started elsewhere: a run of episodes or a whole
 * season, each reaching the database as one write. Reached as `viewerLibrary.catchUp`.
 */
class CatchUpMarks internal constructor(
    private val positions: PlaybackPositionDao,
    private val episodes: EpisodeDao,
    private val activeProfileId: StateFlow<Long>,
    private val clock: () -> Long,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
) {
    /** Marks [episode] and every earlier episode of its series Watched for the active Profile. */
    suspend fun markWatchedUpTo(episode: Episode) = markWatched(upTo(episode))

    /** Marks every one of [season]'s episodes Watched, so Up Next is the episode after its last. */
    suspend fun markSeasonWatched(season: List<Episode>) = markWatched(season)

    /**
     * Marks [season]'s played episodes Unwatched, so Up Next is worked out from what remains. One
     * never played here is Unwatched already, and a mark on it would beat another device's progress.
     */
    suspend fun markSeasonUnwatched(season: List<Episode>) = inTransaction {
        val mine = episodePositions()
        val played = season.filter { ep ->
            mine[ep.mediaKey]?.let(::statusOf).let { it != null && it != WatchStatus.Unwatched }
        }
        write(played.sortedWith(EPISODE_ORDER), HandMark.UNWATCHED, mine)
    }

    /** Whether [markWatchedUpTo] would mark an episode before [episode], rather than only [episode] itself. */
    suspend fun hasEarlierToMark(episode: Episode): Boolean {
        val mine = episodePositions()
        return upTo(episode).any { ep ->
            ep.id != episode.id && mine[ep.mediaKey]?.let(::statusOf) != WatchStatus.Watched
        }
    }

    private suspend fun upTo(episode: Episode): List<Episode> = episodes.forSeries(episode.sourceId, episode.seriesId)
        .filter { EPISODE_ORDER.compare(it, episode) <= 0 }
        // Specials sort first but aren't part of the story being caught up on.
        .filter { it.season > 0 || episode.season == 0 }

    private suspend fun markWatched(titles: List<Episode>) = inTransaction {
        val mine = episodePositions()
        val ordered = titles.sortedWith(EPISODE_ORDER)
        // Already Watched ones are left as they are, so sync carries only what changed; the last
        // is written regardless, as Up Next follows the newest stamp.
        val changed = ordered.filter { ep ->
            ep == ordered.last() || mine[ep.mediaKey]?.let(::statusOf) != WatchStatus.Watched
        }
        write(changed, HandMark.WATCHED, mine)
    }

    private suspend fun episodePositions(): Map<String, PlaybackPosition> =
        positions.ofKind(activeProfileId.value, EPISODE_PREFIX).associateBy { it.mediaKey }

    private suspend fun write(ordered: List<Episode>, mark: HandMark, mine: Map<String, PlaybackPosition>) {
        // Up Next goes by the newest stamp, so a shared one would leave it to chance. Counted back
        // from now, as one stamped ahead of the clock would also beat later changes in sync.
        val newest = clock()
        positions.upsertAll(
            ordered.mapIndexed { index, ep ->
                markedPosition(
                    profileId = activeProfileId.value,
                    mediaKey = ep.mediaKey,
                    existing = mine[ep.mediaKey],
                    mark = mark,
                    atMillis = newest - (ordered.lastIndex - index),
                )
            },
        )
    }
}

/** One Profile's positions (newest first), every episode of each series it has played, and its removals. */
private class PlayedSnapshot(
    val positions: List<PlaybackPosition>,
    val ofPlayedSeries: List<Episode>,
    val dismissals: List<SeriesDismissal>,
)

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
    fun movies(pid: Long): Flow<List<Played>> = positions.observeNewestFirst(pid).map(::moviesFrom)

    /** [list] (one Profile's positions, newest first) as Continue Watching's movie cards. */
    fun moviesFrom(list: List<PlaybackPosition>): List<Played> = list.asSequence()
        .mapNotNull { pos ->
            val id = pos.movieId ?: return@mapNotNull null
            (statusOf(pos) as? WatchStatus.InProgress)?.let {
                Played(ContinueWatchingCard.Movie(id, it.progress), pos.updatedAtMillis)
            }
        }
        .take(IN_PROGRESS_LIMIT)
        .toList()

    /**
     * Everything one Profile has played, read again whenever it changes: what Continue Watching and
     * the covers on Home are both worked out from, so Home reads it once for both. Played episodes
     * are observed as well as positions so a newly added Up Next shows up.
     */
    fun played(pid: Long): Flow<PlayedSnapshot> = combine(
        positions.observeNewestFirst(pid),
        episodes.observePlayed(pid),
        dismissals.observe(pid),
    ) { list, _, dismissed ->
        // One query for every played series' episodes: each card is built before the cap, and
        // a query per series on every position written would be felt on a Fire TV Stick.
        PlayedSnapshot(list, episodes.ofPlayedSeries(pid), dismissed)
    }

    fun series(pid: Long): Flow<List<Played>> = played(pid).map { seriesFrom(it) }

    /** Continue Watching's series cards in [played]. */
    suspend fun seriesFrom(played: PlayedSnapshot): List<Played> {
        val list = played.positions
        val dismissalOf = played.dismissals.associateBy { it.sourceId to it.seriesId }
        val positionOf = list.associateBy { it.mediaKey }
        val ofPlayedSeries = played.ofPlayedSeries
        val episodesOf = ofPlayedSeries.groupBy { it.sourceId to it.seriesId }
        val episodeById = ofPlayedSeries.associateBy { it.id }
        // Still newest first within each series.
        val positionsOf = list
            .mapNotNull { pos -> episodeById[pos.episodeId]?.let { (it.sourceId to it.seriesId) to pos } }
            .groupBy({ it.first }, { it.second })
        // Capped on cards, not series: a series with nothing left to watch takes no slot. Every
        // series is built before the cap, since a New Episode can lift one long unplayed to the top.
        return positionsOf
            .mapNotNull { (series, mine) ->
                val progress = SeriesEpisodes(episodesOf[series].orEmpty()).progressIn(mine)
                    ?: return@mapNotNull null
                val dismissal = dismissalOf[series]
                // The first is when the series was last played or marked, Unwatched marks included.
                val touchedAt = mine.first().updatedAtMillis
                if (dismissal != null && stillDismissed(dismissal, touchedAt)) return@mapNotNull null
                seriesCard(progress, positionOf)?.let { Played(it, placedAt(it, progress.position)) }
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

    /** Null with no Up Next: unlike the series page, Continue Watching has nothing to offer after the last episode. */
    private fun seriesCard(
        progress: SeriesProgress,
        positionOf: Map<String, PlaybackPosition>,
    ): ContinueWatchingCard.Series? = when (val status = progress.status) {
        is WatchStatus.InProgress -> ContinueWatchingCard.Series(progress.latest, status.progress)
        else -> progress.upNext?.let { next ->
            // Up Next may have been started before an earlier episode was finished; the player
            // resumes it there, so the card shows that progress rather than none.
            val started = positionOf[next.mediaKey]?.let(::statusOf) as? WatchStatus.InProgress
            // Nothing to mark it but its arriving after the Profile had Watched the one before.
            val isNew = started == null && next.firstSeenMillis > progress.position.updatedAtMillis
            ContinueWatchingCard.Series(next, started?.progress, newEpisode = isNew)
        }
    }
}

/**
 * One series' episodes (any order), and the one place a Profile's latest position in them is worked
 * out. The series page, Continue Watching and New Episode detection each interpret the answer.
 */
private class SeriesEpisodes(episodes: List<Episode>) {
    val ordered = episodes.sortedWith(EPISODE_ORDER)

    // Ids rather than media keys: Continue Watching builds one of these per played series every
    // time a position is saved, and a string per episode would be felt on a Fire TV Stick.
    private val ids = ordered.mapTo(HashSet()) { it.id }

    /**
     * Where one Profile is in this series, given its [positions] (of any titles, any order); null
     * when it has played none of these episodes. An episode marked Unwatched is as if never played:
     * it doesn't mean the Profile has stopped following the series, so Up Next comes from what remains.
     */
    fun progressIn(positions: List<PlaybackPosition>): SeriesProgress? {
        val latest = positions.filter { it.episodeId in ids && it.mark != HandMark.UNWATCHED }
            .maxByOrNull { it.updatedAtMillis }
            ?: return null
        val at = ordered.indexOfFirst { it.id == latest.episodeId }
        return SeriesProgress(ordered[at], latest, ordered.getOrNull(at + 1))
    }
}

/** [latest] is the episode a Profile played most recently, at [position]; [following] comes after it, if anything. */
private class SeriesProgress(val latest: Episode, val position: PlaybackPosition, following: Episode?) {
    val status = statusOf(position)

    /** Null while [latest] is In Progress, and after the series' last episode. */
    val upNext = following.takeIf { status == WatchStatus.Watched }

    /** The Profile has Watched the last episode there is, so only a New Episode can follow. */
    val caughtUp = status == WatchStatus.Watched && following == null
}

/** The key ViewerLibrary keeps a Profile's watch state for this movie under. */
val Movie.mediaKey: String get() = MediaKey.Movie(id).stored

/** The key ViewerLibrary keeps a Profile's watch state for this episode under. */
val Episode.mediaKey: String get() = MediaKey.Episode(id).stored

/** The local episode id this position is for; null for anything that isn't an episode. */
private val PlaybackPosition.episodeId: Long? get() = (MediaKey.parse(mediaKey) as? MediaKey.Episode)?.episodeId

/** The movie this row is about, or null when its key names an episode or nothing at all. */
private val PlaybackPosition.movieId: Long? get() = (MediaKey.parse(mediaKey) as? MediaKey.Movie)?.movieId

// The id settles episodes a provider numbers the same, which would otherwise fall in whatever order
// the query returned them, so "the episode after this one" could change between reads.
private val EPISODE_ORDER = compareBy<Episode>({ it.season }, { it.episodeNumber }, { it.id })

// What an episode's stored key starts with, for matching in SQL and in bulk; MediaKey owns the form.
private const val EPISODE_PREFIX = "${MediaKey.EPISODE}:"
private const val IN_PROGRESS_LIMIT = 30
private const val TAG = "ViewerLibrary"

/** Past this, the credits are rolling: the title counts as Watched and is not resumed. */
private const val WATCHED_FRACTION = 0.93

private const val MIN_SAVED_MILLIS = 5_000L

/** [mediaKey]'s row once marked: a mark says nothing of how far it was played, so that is kept. */
private fun markedPosition(
    profileId: Long,
    mediaKey: String,
    existing: PlaybackPosition?,
    mark: HandMark,
    atMillis: Long,
) = PlaybackPosition(
    profileId = profileId,
    mediaKey = mediaKey,
    positionMillis = existing?.positionMillis ?: 0,
    durationMillis = existing?.durationMillis ?: 0,
    updatedAtMillis = atMillis,
    mark = mark,
)

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

private fun coverStatusOf(status: WatchStatus, episode: Episode? = null): CoverStatus? = when (status) {
    is WatchStatus.InProgress -> CoverStatus.InProgress(status.progress, status.remainingMillis, episode)
    WatchStatus.Watched -> CoverStatus.Watched
    WatchStatus.Unwatched -> null
}

/** Read as Continue Watching reads a series ([ContinueWatchingCards.seriesCard]), plus Watched once caught up. */
private fun seriesCoverStatus(progress: SeriesProgress, positionOf: Map<String, PlaybackPosition>): CoverStatus? {
    val status = progress.status
    val next = progress.upNext
    return when {
        status is WatchStatus.InProgress -> coverStatusOf(status, progress.latest)
        next == null -> if (progress.caughtUp) CoverStatus.Watched else null
        else -> {
            val started = positionOf[next.mediaKey]?.let(::statusOf) as? WatchStatus.InProgress
            when {
                started != null -> coverStatusOf(started, next)
                next.firstSeenMillis > progress.position.updatedAtMillis -> CoverStatus.NewEpisode
                else -> CoverStatus.UpNext(next)
            }
        }
    }
}

/** Each started title's [CoverStatus] for the active Profile, for the covers on Home. */
fun ViewerLibrary.coverStatuses(): Flow<CoverStatuses> = home().map { it.covers }.distinctUntilChanged()

/** Each title's [CoverStatus] in [played]. */
private fun coverStatusesIn(played: PlayedSnapshot): CoverStatuses {
    val list = played.positions
    val movies = list.mapNotNull { pos ->
        val id = pos.movieId ?: return@mapNotNull null
        coverStatusOf(statusOf(pos))?.let { id to it }
    }.toMap()
    val positionOf = list.associateBy { it.mediaKey }
    val series = played.ofPlayedSeries.groupBy { SeriesRef(it.sourceId, it.seriesId) }.mapNotNull { (ref, theirs) ->
        SeriesEpisodes(theirs).progressIn(list)?.let { seriesCoverStatus(it, positionOf) }?.let { ref to it }
    }.toMap()
    return CoverStatuses(movies, series)
}
