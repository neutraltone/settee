/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.ChannelEpgMatch
import app.opentv.data.model.ChannelHiddenChange
import app.opentv.data.model.ChannelMatchInput
import app.opentv.data.model.ChannelUserState
import app.opentv.data.model.CoverRow
import app.opentv.data.model.CreditedTitle
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Episode
import app.opentv.data.model.Favourite
import app.opentv.data.model.GuideFingerprint
import app.opentv.data.model.GuideProgramme
import app.opentv.data.model.HandMark
import app.opentv.data.model.LivePlayback
import app.opentv.data.model.Movie
import app.opentv.data.model.MovieCredit
import app.opentv.data.model.OnNowRow
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Profile
import app.opentv.data.model.ProfileSetting
import app.opentv.data.model.Programme
import app.opentv.data.model.RecentSearch
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Reminder
import app.opentv.data.model.Series
import app.opentv.data.model.SeriesCredit
import app.opentv.data.model.SeriesDismissal
import app.opentv.data.model.SeriesRef
import app.opentv.data.model.SeriesRule
import app.opentv.data.model.Source
import app.opentv.data.model.SourceCategoryName
import app.opentv.data.model.StreamKind
import app.opentv.data.model.VersionRow
import app.opentv.data.model.movieCredits
import app.opentv.data.model.seriesCredits
import app.opentv.sync.SyncHiddenChannel
import kotlinx.coroutines.flow.Flow

/** Rows per upsert statement. Keeps a 40,000-channel playlist from building one huge query. */
private const val UPSERT_CHUNK = 500

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY id")
    fun observeAll(): Flow<List<Source>>

    @Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY id")
    suspend fun enabled(): List<Source>

    @Query("SELECT * FROM sources ORDER BY id")
    suspend fun all(): List<Source>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun byId(id: Long): Source?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: Source): Long

    @Update
    suspend fun update(source: Source)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE sources SET lastCatalogSyncMillis = :millis WHERE id = :id")
    suspend fun markCatalogSynced(id: Long, millis: Long)
}

@Dao
interface ChannelDao {
    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0
          AND (:sourceId IS NULL OR sourceId = :sourceId)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY sortIndex, displayName
        """,
    )
    fun observe(sourceId: Long?, categoryId: String?): Flow<List<Channel>>

    /**
     * Channels across a SET of provider categories. Needed because one logical category
     * ("General") is often shipped as several codec-split ones ("UK| GENERAL HD/RAW",
     * "UK| GENERAL hevc") — the UI merges them into a single rail entry. The list is a
     * handful of ids, nowhere near SQLite's bound-variable cap.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0 AND categoryId IN (:categoryIds)
        ORDER BY sortIndex, displayName
        """,
    )
    fun observeInCategories(categoryIds: List<String>): Flow<List<Channel>>

    /**
     * Channels across a set of categories INCLUDING hidden ones, optionally scoped to one source
     * — the channel manager's browse query.
     *
     * The guide's [observe]/[observeInCategories] filter `hidden = 0`; the manager can't, because
     * its whole job is finding a hidden channel and switching it back on. Still scoped to a
     * category set (never the whole table) so the list stays a size a remote can actually scroll.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE (:sourceId IS NULL OR sourceId = :sourceId)
          AND categoryId IN (:categoryIds)
        ORDER BY sortIndex, displayName
        """,
    )
    fun observeInCategoriesIncludingHidden(sourceId: Long?, categoryIds: List<String>): Flow<List<Channel>>

    /**
     * One-shot (non-Flow) read of ONE category's channels INCLUDING hidden, optionally scoped to a
     * single source — the web channel manager's per-category fetch. That server handles one HTTP
     * request at a time on a socket thread and wants a plain list, not a subscription, so this is
     * the suspend `List` sibling of [observeInCategoriesIncludingHidden], narrowed to one category.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE (:sourceId IS NULL OR sourceId = :sourceId)
          AND categoryId = :categoryId
        ORDER BY sortIndex, displayName
        """,
    )
    suspend fun channelsInCategoryIncludingHidden(sourceId: Long?, categoryId: String): List<Channel>

    /**
     * Channel count per (source, category), hidden rows included — the web manager's category list
     * shows how many channels each holds. Grouped by (sourceId, categoryId) because a category id
     * is only unique within a source.
     */
    @Query(
        """
        SELECT sourceId, categoryId, COUNT(*) AS count
        FROM channels
        WHERE categoryId IS NOT NULL
        GROUP BY sourceId, categoryId
        """,
    )
    suspend fun channelCountsByCategory(): List<CategoryChannelCount>

    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0
          AND (displayName LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%')
        ORDER BY sortIndex, displayName
        LIMIT :limit
        """,
    )
    fun search(query: String, limit: Int = 200): Flow<List<Channel>>

    /** Like [search] but keeps hidden channels in — the channel manager needs them to unhide. */
    @Query(
        """
        SELECT * FROM channels
        WHERE (displayName LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%')
        ORDER BY hidden, sortIndex, displayName
        LIMIT :limit
        """,
    )
    fun searchIncludingHidden(query: String, limit: Int = 200): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun byId(id: Long): Channel?

    /**
     * Every row called [nameKey] in any region, hidden ones too: `groupKey` is `nameKey` itself, or
     * ends in `:nameKey`. What [app.opentv.data.repo.ChannelLineup] folds to find one row's Channel.
     * A suffix match can't use the groupKey index, so this scans the table; it runs once per lookup,
     * never per row.
     */
    @Query("SELECT * FROM channels WHERE groupKey = :nameKey OR groupKey LIKE '%:' || :nameKey")
    suspend fun sameName(nameKey: String): List<Channel>

    @Query("SELECT COUNT(*) FROM channels WHERE sourceId = :sourceId")
    suspend fun countForSource(sourceId: Long): Int

    /**
     * Reactive count of channels the guide can actually show (hidden rows excluded). Lets the home
     * screen tell "the guide is still building from channels on disk" apart from "nothing loaded",
     * so a failed or empty sync surfaces an error instead of spinning forever.
     */
    @Query("SELECT COUNT(*) FROM channels WHERE hidden = 0")
    fun observeVisibleCount(): Flow<Int>

    @Query("UPDATE channels SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    /** A viewer hid or showed a channel: its state and when, together. */
    @Transaction
    suspend fun changeHidden(id: Long, hidden: Boolean, changedAtMillis: Long) {
        setHidden(id, hidden)
        recordHiddenChange(id, hidden, changedAtMillis)
    }

    @Query(
        """
        INSERT OR REPLACE INTO channel_hidden_changes (source, streamId, hidden, changedAtMillis)
        SELECT sources.name, channels.streamId, :hidden, :changedAtMillis
        FROM channels JOIN sources ON sources.id = channels.sourceId WHERE channels.id = :id
        """,
    )
    suspend fun recordHiddenChange(id: Long, hidden: Boolean, changedAtMillis: Long)

    @Query("UPDATE channels SET customName = :name WHERE id = :id")
    suspend fun setCustomName(id: Long, name: String?)

    @Query("UPDATE channels SET sortIndex = :sortIndex WHERE id = :id")
    suspend fun setSortIndex(id: Long, sortIndex: Int)

    /** Every channel for a source. The channels table is live-only, so these are its live channels. */
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: Long): List<Channel>

    /** Rewrites one channel's resolved playback URL — used when a source's live stream format changes. */
    @Query("UPDATE channels SET streamUrl = :url WHERE id = :id")
    suspend fun updateStreamUrl(id: Long, url: String)

    // --- Sync: identify hidden channels by source name and stream id (ADR 0004) ---
    @Query(
        """
        SELECT source, streamId AS id, hidden, changedAtMillis FROM channel_hidden_changes
        """,
    )
    suspend fun hiddenForSync(): List<SyncHiddenChannel>

    @Query("SELECT * FROM channel_hidden_changes WHERE source = :source AND streamId = :streamId")
    suspend fun hiddenChange(source: String, streamId: String): ChannelHiddenChange?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHiddenChange(change: ChannelHiddenChange)

    @Query("SELECT hidden FROM channels WHERE sourceId = :sourceId AND streamId = :streamId")
    suspend fun isHidden(sourceId: Long, streamId: String): Boolean?

    @Query("UPDATE channels SET hidden = :hidden WHERE sourceId = :sourceId AND streamId = :streamId")
    suspend fun setHidden(sourceId: Long, streamId: String, hidden: Boolean)

    /** The last hide or show a viewer made of each channel from this source, listed or not right now. */
    @Query(
        """
        SELECT * FROM channel_hidden_changes
        WHERE source = (SELECT name FROM sources WHERE id = :sourceId)
        """,
    )
    suspend fun hiddenChangesFor(sourceId: Long): List<ChannelHiddenChange>

    /**
     * Saves many guide matches in one transaction, so what observes `channels`, including the whole
     * lineup, re-reads it once rather than after every match or every batch. Each row is its own
     * small statement, so this has no bound-variable limit to split around.
     */
    @Update(entity = Channel::class)
    suspend fun setMatchedEpgIds(matches: List<ChannelEpgMatch>)

    @Query("UPDATE channels SET epgOverrideId = :epgId WHERE id = :id")
    suspend fun setEpgOverride(id: Long, epgId: String?)

    /** Every channel, hidden ones too, as it changes. */
    @Query("SELECT * FROM channels")
    fun observeEvery(): Flow<List<Channel>>

    /** Every row, whole, once. */
    @Query("SELECT * FROM channels")
    suspend fun everyRow(): List<Channel>

    /** The matcher's working set: no URLs or names, which a large catalogue would allocate on every launch. */
    @Query("SELECT id, groupKey, epgChannelId, matchedEpgId, epgOverrideId FROM channels")
    suspend fun matcherInputs(): List<ChannelMatchInput>

    /** Every channel's name as the provider wrote it, which is where a region prefix is. */
    @Query("SELECT name FROM channels")
    suspend fun providerNames(): List<String>

    @Upsert
    suspend fun upsertAll(channels: List<Channel>)

    /**
     * Deletes channels the provider no longer lists.
     *
     * Compares a sync stamp rather than using `streamId NOT IN (:allCurrentIds)`. The latter
     * is the obvious implementation and it breaks on exactly the providers where it matters:
     * SQLite caps bound variables (999 on older Android, 32766 on newer) and a large playlist
     * has 40,000 channels, so the query throws and the catalogue never converges.
     *
     * Note also the deliberate absence of any "delete everything then insert" method. Wiping
     * first means a sync that fails halfway leaves the user with an empty app — which is how
     * a player ends up telling people to reinstall to get their channels back.
     */
    @Query("DELETE FROM channels WHERE sourceId = :sourceId AND lastSeenMillis < :syncStamp")
    suspend fun deleteStale(sourceId: Long, syncStamp: Long)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    /**
     * Preserves device state (hidden, manual order, custom names, EPG overrides) across a
     * catalogue refresh, for a list held whole, as Stalker and M3U have theirs. Upsert would
     * otherwise overwrite them with the defaults from the freshly parsed rows.
     */
    @Transaction
    suspend fun replaceCatalogue(sourceId: Long, incoming: List<Channel>, syncStamp: Long) {
        if (incoming.isEmpty()) return

        // Every stored row at once, not a batch at a time as below: a stream id listed twice then
        // meets the row as it was before the refresh both times, which is how these lists always
        // merged, and the whole list is in memory already.
        val merged = mergedOver(
            incoming,
            userStateForSource(sourceId).associateBy { it.streamId },
            hiddenChangesFor(sourceId).associateBy { it.streamId },
            syncStamp,
        )
        // Chunked so a 40,000-channel playlist does not build one enormous statement.
        merged.chunked(UPSERT_CHUNK).forEach { upsertAll(it) }
        deleteStale(sourceId, syncStamp)
    }

    /**
     * [replaceCatalogue] for a list read a batch at a time, as Xtream's is, returning how many
     * channels there were. Each batch is merged over the stored rows as it comes, and stale
     * channels go only once the last is saved: all in one transaction, so a list that fails partway
     * changes nothing, and a list with no channels deletes none.
     */
    suspend fun replaceCatalogue(
        sourceId: Long,
        syncStamp: Long,
        batches: suspend (save: suspend (List<Channel>) -> Unit) -> Unit,
    ): Int {
        var saved = 0
        inTransaction {
            batches { batch ->
                // Looked up a chunk at a time rather than every stored row at once, which would cost
                // as much memory as the list. Chunked whatever the batch size, since both lookups
                // bind every stream id in it and SQLite allows 999 variables.
                batch.chunked(UPSERT_CHUNK).forEach { chunk ->
                    val streamIds = chunk.map { it.streamId }
                    val merged = mergedOver(
                        chunk,
                        byStreamIds(sourceId, streamIds).associateBy { it.streamId },
                        hiddenChangesFor(sourceId, streamIds).associateBy { it.streamId },
                        syncStamp,
                    )
                    upsertAll(merged)
                }
                saved += batch.size
            }
            if (saved > 0) deleteStale(sourceId, syncStamp)
        }
        return saved
    }

    /** Runs [block] as one transaction; see [replaceCatalogue]. */
    @Transaction
    suspend fun inTransaction(block: suspend () -> Unit) = block()

    @Query(
        """
        SELECT streamId, id, hidden, sortIndex, customName, epgOverrideId, matchedEpgId
        FROM channels WHERE sourceId = :sourceId
        """,
    )
    suspend fun userStateForSource(sourceId: Long): List<ChannelUserState>

    /** The stored rows for one refresh batch of [streamIds]; keep it under SQLite's 999 variables. */
    @Query(
        """
        SELECT streamId, id, hidden, sortIndex, customName, epgOverrideId, matchedEpgId
        FROM channels WHERE sourceId = :sourceId AND streamId IN (:streamIds)
        """,
    )
    suspend fun byStreamIds(sourceId: Long, streamIds: List<String>): List<ChannelUserState>

    /**
     * [hiddenChangesFor] only for one refresh batch's [streamIds]; keep it under SQLite's 999
     * variables.
     */
    @Query(
        """
        SELECT * FROM channel_hidden_changes
        WHERE source = (SELECT name FROM sources WHERE id = :sourceId) AND streamId IN (:streamIds)
        """,
    )
    suspend fun hiddenChangesFor(sourceId: Long, streamIds: List<String>): List<ChannelHiddenChange>
}

/**
 * [incoming] over the rows they replace ([existing], by stream id), carrying what the viewer set
 * across a catalogue refresh, each stamped as seen at [syncStamp].
 */
private fun mergedOver(
    incoming: List<Channel>,
    existing: Map<String, ChannelUserState>,
    hiddenChanges: Map<String, ChannelHiddenChange>,
    syncStamp: Long,
): List<Channel> = incoming.map { channel ->
    val previous = existing[channel.streamId]
    if (previous == null) {
        // A channel the provider dropped for a while comes back as the viewer left it.
        channel.copy(
            hidden = hiddenChanges[channel.streamId]?.hidden ?: channel.hidden,
            lastSeenMillis = syncStamp,
        )
    } else {
        channel.copy(
            id = previous.id,
            // OR, not overwrite: the importer hides separator rows ('### UK ###')
            // at parse time, and that decision must stick even for rows that
            // predate the rule. User hides are preserved the same way.
            hidden = previous.hidden || channel.hidden,
            sortIndex = previous.sortIndex,
            // A user's rename must outlive the provider's fresh name on every refresh.
            customName = previous.customName,
            epgOverrideId = previous.epgOverrideId,
            matchedEpgId = previous.matchedEpgId,
            lastSeenMillis = syncStamp,
        )
    }
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY sortIndex, name")
    fun observe(kind: StreamKind): Flow<List<Category>>

    /** One-shot list of every category of a kind, ordered like [observe] — the web manager reads
     * the live categories once per request rather than subscribing to a Flow. */
    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY sortIndex, name")
    suspend fun allByKind(kind: StreamKind): List<Category>

    /** One category's name, for what a Version's category says of it (`VersionTraits`). */
    @Query("SELECT name FROM categories WHERE sourceId = :sourceId AND id = :id AND kind = :kind")
    suspend fun nameOf(sourceId: Long, id: String, kind: StreamKind): String?

    /** Every category of [kind] by name, for what each says of its Versions. */
    @Query("SELECT sourceId, id, name FROM categories WHERE kind = :kind")
    fun observeNames(kind: StreamKind): Flow<List<SourceCategoryName>>

    /** Minimal id+name rows for the re-normalise pass. */
    @Query("SELECT id, name FROM categories WHERE id IN (:ids)")
    suspend fun namesFor(ids: Set<String>): List<CategoryName>

    @Upsert
    suspend fun upsertAll(categories: List<Category>)

    @Query("DELETE FROM categories WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    /**
     * Drops [sourceId]'s live categories that no channel row is in, hidden rows included. A
     * subquery rather than a list of ids, which a playlist's group-titles could take past SQLite's
     * bound-variable limit.
     */
    @Query(
        "DELETE FROM categories WHERE sourceId = :sourceId AND kind = 'LIVE' AND id NOT IN " +
            "(SELECT categoryId FROM channels WHERE sourceId = :sourceId AND categoryId IS NOT NULL)",
    )
    suspend fun deleteEmptyLive(sourceId: Long)
}

/** Projection for [CategoryDao.namesFor]. */
data class CategoryName(val id: String, val name: String)

/** What More like this ranks a film by, without the rest of its row. */
data class MoviePick(val id: Long, val sourceId: Long, val genre: String?, val rating: Double?, val addedMillis: Long)

/** Projection for [ChannelDao.channelCountsByCategory]. */
data class CategoryChannelCount(val sourceId: Long, val categoryId: String, val count: Int)

@Dao
interface EpgFeedDao {
    @Query("SELECT * FROM epg_feeds ORDER BY builtIn DESC, id")
    fun observeAll(): Flow<List<EpgFeed>>

    @Query("SELECT * FROM epg_feeds")
    suspend fun all(): List<EpgFeed>

    @Query("SELECT * FROM epg_feeds WHERE enabled = 1")
    suspend fun enabled(): List<EpgFeed>

    @Query("SELECT * FROM epg_feeds WHERE providerSourceId = :sourceId")
    suspend fun forProvider(sourceId: Long): EpgFeed?

    @Query("SELECT * FROM epg_feeds WHERE url = :url")
    suspend fun byUrl(url: String): EpgFeed?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(feed: EpgFeed): Long

    @Update
    suspend fun update(feed: EpgFeed)

    @Query("UPDATE epg_feeds SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE epg_feeds SET lastSyncMillis = :millis, lastResult = :result WHERE id = :id")
    suspend fun markSynced(id: Long, millis: Long, result: String)

    @Query("DELETE FROM epg_feeds WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface EpgChannelAliasDao {
    @Query("SELECT * FROM epg_channels")
    suspend fun all(): List<EpgChannelAlias>

    @Query("SELECT COUNT(*) FROM epg_channels")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(aliases: List<EpgChannelAlias>)

    @Query("DELETE FROM epg_channels WHERE feedId = :feedId")
    suspend fun deleteForFeed(feedId: Long)
}

@Dao
interface ProgrammeDao {
    /**
     * The guide grid: everything overlapping the visible window, without the details the grid
     * doesn't draw (see [GuideProgramme]).
     *
     * Filtered by time only, never by a list of channel ids. `epgChannelId IN (:ids)` is the
     * natural way to write this and it breaks on any provider large enough to matter — SQLite
     * caps bound variables at 999 on older Android, and a real catalogue has thousands of
     * channels. Callers group the result by channel in memory instead.
     */
    @Query(
        """
        SELECT epgChannelId, startUtcMillis, endUtcMillis, title, season, episode FROM programmes
        WHERE endUtcMillis > :fromUtcMillis
          AND startUtcMillis < :toUtcMillis
        ORDER BY epgChannelId, startUtcMillis
        """,
    )
    fun observeGuideWindow(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<GuideProgramme>>

    /** What's on at [nowUtcMillis] of the programmes ending by [endsByUtcMillis]; see [ON_NOW_QUERY]. */
    @Query(ON_NOW_QUERY)
    suspend fun onNow(nowUtcMillis: Long, endsByUtcMillis: Long): List<OnNowRow>

    @Query(
        """
        SELECT * FROM programmes
        WHERE epgChannelId = :channelId AND endUtcMillis > :nowUtcMillis
        ORDER BY startUtcMillis LIMIT :limit
        """,
    )
    suspend fun upcoming(channelId: String, nowUtcMillis: Long, limit: Int): List<Programme>

    /**
     * The programme starting at [startUtcMillis] on [channelId]: for playing it back from the archive,
     * and for the details of one the guide grid lists. When two feeds both carry it, the one with a
     * description wins, so the synopsis doesn't depend on which feed SQLite happens to return first.
     */
    @Query(
        """
        SELECT * FROM programmes WHERE epgChannelId = :channelId AND startUtcMillis = :startUtcMillis
        ORDER BY description IS NULL LIMIT 1
        """,
    )
    suspend fun startingAt(channelId: String, startUtcMillis: Long): Programme?

    /** See [GuideFingerprint] and [GUIDE_FINGERPRINT_QUERY]. */
    @Query(GUIDE_FINGERPRINT_QUERY)
    suspend fun guideFingerprint(): GuideFingerprint

    /** Distinct guide channels that actually have programmes — the match report's baseline. */
    @Query("SELECT DISTINCT epgChannelId FROM programmes")
    suspend fun channelIdsWithProgrammes(): List<String>

    /**
     * Writes a feed's programmes, each replacing the stored one in its slot (same feed, channel and start).
     *
     * Not `@Upsert`: parsed rows come with `id = 0`, so Room's upsert hit the slot's unique index on
     * insert, fell back to `UPDATE … WHERE id = 0`, matched nothing, and kept the first download's
     * details, throwing an exception per stored row on the way. REPLACE lets SQLite resolve the
     * conflict on that index itself. `ON CONFLICT … DO UPDATE` would keep the row's id, but the oldest
     * devices' SQLite doesn't have it. A replaced row gets a new id, which is fine: nothing refers to
     * programme ids (reminders and recordings key on channel and start time).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(programmes: List<Programme>)

    /** Housekeeping: drop anything that finished before the retention cut-off. */
    @Query("DELETE FROM programmes WHERE endUtcMillis < :beforeUtcMillis")
    suspend fun deleteEndedBefore(beforeUtcMillis: Long)

    @Query("DELETE FROM programmes WHERE feedId = :feedId")
    suspend fun deleteForFeed(feedId: Long)
}

@Dao
interface MovieDao {
    @Query("SELECT COUNT(*) FROM movies WHERE sourceId = :sourceId")
    suspend fun countForSource(sourceId: Long): Int

    @Query(MOVIES_IN_CATEGORY)
    fun observe(categoryId: String?): Flow<List<Movie>>

    /**
     * A provider's category's covers for its grid: only what the cards show, in [observe]'s order.
     * By provider too, since another provider can use the same category id.
     */
    @Query(
        "SELECT id, name, posterUrl, year FROM movies WHERE sourceId = :sourceId AND categoryId = :categoryId " +
            "ORDER BY addedMillis DESC, name",
    )
    fun observeCovers(sourceId: Long, categoryId: String): Flow<List<CoverRow>>

    @Query("SELECT * FROM movies WHERE name LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    fun search(query: String, limit: Int = 200): Flow<List<Movie>>

    /** Newest-first, for the "Recently Added" home row. Reactive so it fills in as VOD sync lands. */
    @Query(RECENT_MOVIES)
    fun observeRecentlyAdded(limit: Int): Flow<List<Movie>>

    /**
     * Every movie, newest first — the working set for the Kotlin-side feeds (by-genre grouping,
     * genre-affinity recommendations, more-like-this). One pass over a few thousand rows is cheap;
     * per-genre `LIKE` queries would multiply round-trips and risk substring false positives.
     */
    @Query(ALL_MOVIES)
    suspend fun all(): List<Movie>

    /** See [RANKED_FINGERPRINT]. */
    @Query("$RANKED_FINGERPRINT FROM movies")
    suspend fun fingerprint(): String

    @Query("SELECT * FROM movies WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: Long): List<Movie>

    /**
     * The [limit] movies a source most recently added that the provider hasn't been asked about, or,
     * [withTmdb], that TMDB hasn't answered about since [tmdbStaleBefore], for [MovieGenres].
     */
    @Query(NEWEST_MOVIES_NOT_LOOKED_UP)
    suspend fun newestNotLookedUp(sourceId: Long, withTmdb: Boolean, tmdbStaleBefore: Long, limit: Int): List<Movie>

    /**
     * Fills whichever of a movie's details are still empty from what [MovieGenres] looked up, and marks
     * what it asked, so a film nobody has opened has its people for a person's page. Never overwrites,
     * since opening the movie may have found better ones meanwhile, except a blank genre (the provider
     * had none) with one found since, and the director, writers and studios, which TMDB's replace.
     * Every field `Movie.filledFrom` fills, since a film this fills counts as enriched and opening it
     * won't ask the provider again.
     */
    @Query(
        """
        UPDATE movies SET
            genre = COALESCE(NULLIF(genre, ''), NULLIF(:genre, ''), genre, :genre),
            `cast` = COALESCE(`cast`, :cast),
            director = COALESCE(:director, director),
            writer = COALESCE(:writer, writer),
            studio = COALESCE(:studio, studio),
            plot = COALESCE(plot, :plot),
            backdropUrl = COALESCE(backdropUrl, :backdropUrl),
            tmdbId = COALESCE(tmdbId, :tmdbId),
            rating = COALESCE(rating, :rating),
            year = COALESCE(year, :year),
            durationSeconds = COALESCE(durationSeconds, :durationSeconds),
            releaseDate = COALESCE(releaseDate, :releaseDate),
            detailsLookedUp = MAX(detailsLookedUp, :detailsLookedUp),
            tmdbAnsweredAt = COALESCE(MAX(tmdbAnsweredAt, :tmdbAnsweredAt), tmdbAnsweredAt, :tmdbAnsweredAt)
        WHERE id = :id
        """,
    )
    @Suppress("LongParameterList") // One per column; Room binds no objects.
    suspend fun fillDetails(
        id: Long,
        genre: String?,
        cast: String?,
        director: String?,
        writer: String?,
        studio: String?,
        plot: String?,
        backdropUrl: String?,
        tmdbId: String?,
        rating: Double?,
        year: Int?,
        durationSeconds: Int?,
        releaseDate: Long?,
        detailsLookedUp: Boolean,
        tmdbAnsweredAt: Long?,
    )

    /**
     * [fillDetails] for each of these in one go, so everything observing movies reloads once. Each is
     * a movie carrying what was looked up for it, which only lands where the stored row has nothing.
     */
    @Transaction
    suspend fun fillDetails(found: List<Movie>) {
        val before = credited(found.map { it.id })
        found.forEach {
            fillDetails(
                id = it.id,
                genre = it.genre,
                cast = it.cast,
                director = it.director,
                writer = it.writer,
                studio = it.studio,
                plot = it.plot,
                backdropUrl = it.backdropUrl,
                tmdbId = it.tmdbId,
                rating = it.rating,
                year = it.year,
                durationSeconds = it.durationSeconds,
                releaseDate = it.releaseDate,
                detailsLookedUp = it.detailsLookedUp,
                tmdbAnsweredAt = it.tmdbAnsweredAt,
            )
        }
        recreditChanged(before.associateBy { it.id }, credited(found.map { it.id }))
    }

    /**
     * Movies that act or direct credit the person filed under [nameKey] (see `personKey`), best-rated
     * first: a person's page. An index lookup on [MovieCredit.nameKey], whatever the catalogue's size.
     */
    @Query(MOVIES_WITH_PERSON)
    suspend fun moviesWithPerson(nameKey: String, limit: Int): List<Movie>

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun byId(id: Long): Movie?

    /** Every copy of one film: providers list each Quality as its own row, in its own category. */
    @Query("SELECT * FROM movies WHERE tmdbId = :tmdbId ORDER BY id")
    suspend fun byTmdbId(tmdbId: String): List<Movie>

    /** Every film as grouping into titles reads it, again whenever the table changes. */
    @Query("SELECT id, sourceId, name, tmdbId, year, categoryId FROM movies ORDER BY id")
    fun observeVersionRows(): Flow<List<VersionRow>>

    @Query("SELECT * FROM movies WHERE sourceId = :sourceId AND streamId = :streamId")
    suspend fun byProviderId(sourceId: Long, streamId: String): Movie?

    /** Runs [block] as one transaction, so a whole list's batches land, and reload screens, together. */
    @Transaction
    suspend fun inTransaction(block: suspend () -> Unit) = block()

    /** The stored rows for one sync batch (at most 500) of [streamIds]. */
    @Query("SELECT * FROM movies WHERE sourceId = :sourceId AND streamId IN (:streamIds)")
    suspend fun byProviderIds(sourceId: Long, streamIds: List<String>): List<Movie>

    /**
     * The candidates for a film's More like this whose genre string contains [genre], as just what
     * the ranking reads: a big provider's Drama is thousands of films, too many to load whole on a
     * Fire TV Stick. A substring match, so [CatalogRepository.moreLikeThis] checks the genre is whole.
     */
    @Query(
        """
        SELECT id, sourceId, genre, rating, addedMillis FROM movies
        WHERE id != :excludeId AND genre LIKE '%' || :genre || '%'
        """,
    )
    suspend fun picksSharingGenre(genre: String, excludeId: Long): List<MoviePick>

    /** More like this's candidates for a film with no genre: others from its provider and category. */
    @Query(
        """
        SELECT id, sourceId, genre, rating, addedMillis FROM movies
        WHERE sourceId = :sourceId AND id != :excludeId AND (:categoryId IS NULL OR categoryId = :categoryId)
        """,
    )
    suspend fun picksInCategory(sourceId: Long, categoryId: String?, excludeId: Long): List<MoviePick>

    /** At most a row's worth of films, in no particular order. */
    @Query("SELECT * FROM movies WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<Movie>

    /** Saves these rows as they are. Use [upsertAll], which keeps their credits in step. */
    @Upsert
    suspend fun upsertRows(movies: List<Movie>): List<Long>

    /**
     * Saves these movies, rewriting the [MovieCredit]s of those whose people changed. At
     * most a catalogue batch (500) at a time: their ids are bound in one query.
     */
    @Transaction
    suspend fun upsertAll(movies: List<Movie>) {
        val before = credited(movies.map { it.id }.filter { it > 0 }).associateBy { it.id }
        val rowIds = upsertRows(movies)
        // A new row's id is its insert's; an update's comes back as -1, but it carried its id.
        val after = movies.mapIndexedNotNull { i, movie ->
            val id = if (movie.id > 0) movie.id else rowIds[i]
            if (id > 0) CreditedTitle(id, movie.cast, movie.director, movie.writer, movie.studio) else null
        }
        recreditChanged(before, after)
    }

    /** Rewrites the credits of each of [after] whose people aren't what [before] had. */
    suspend fun recreditChanged(before: Map<Long, CreditedTitle>, after: List<CreditedTitle>) {
        for (now in after) {
            if (before[now.id] == now || (before[now.id] == null && now.isEmpty)) continue
            deleteCredits(now.id)
            insertCredits(movieCredits(now))
        }
    }

    @Query("SELECT id, `cast`, director, writer, studio FROM movies WHERE id IN (:ids)")
    suspend fun credited(ids: List<Long>): List<CreditedTitle>

    /**
     * The next [limit] movies after [afterId] that credit someone but have no [MovieCredit]s: those
     * saved before credits were kept. Ones whose names all fold to nothing come back each time, so
     * callers page by id rather than asking until none are left.
     */
    @Query(
        """
        SELECT id, `cast`, director, writer, studio FROM movies
        WHERE id > :afterId AND (`cast` != '' OR director != '' OR writer != '' OR studio != '')
          AND id NOT IN (SELECT movieId FROM movie_credits)
        ORDER BY id LIMIT :limit
        """,
    )
    suspend fun uncredited(afterId: Long, limit: Int): List<CreditedTitle>

    /**
     * Credits the next [limit] [uncredited] titles after [afterId], returning the last one's id, or
     * null when none are left. Read and written in one transaction, so a refresh changing a cast in
     * between can't have its credits replaced with the old names.
     */
    @Transaction
    suspend fun creditNextUncredited(afterId: Long, limit: Int): Long? {
        val titles = uncredited(afterId, limit).ifEmpty { return null }
        recreditChanged(emptyMap(), titles)
        return titles.last().id
    }

    @Query("DELETE FROM movie_credits WHERE movieId = :movieId")
    suspend fun deleteCredits(movieId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCredits(credits: List<MovieCredit>)

    @Query("DELETE FROM movies WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}

@Dao
interface SeriesDao {
    @Query("SELECT COUNT(*) FROM series WHERE sourceId = :sourceId")
    suspend fun countForSource(sourceId: Long): Int

    @Query(SERIES_IN_CATEGORY)
    fun observe(categoryId: String?): Flow<List<Series>>

    /**
     * A provider's category's covers for its grid: only what the cards show, in [observe]'s order.
     * By provider too, since another provider can use the same category id.
     */
    @Query(
        "SELECT id, name, posterUrl, year FROM series WHERE sourceId = :sourceId AND categoryId = :categoryId " +
            "ORDER BY addedMillis DESC, name",
    )
    fun observeCovers(sourceId: Long, categoryId: String): Flow<List<CoverRow>>

    @Query("SELECT * FROM series WHERE name LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    fun search(query: String, limit: Int = 200): Flow<List<Series>>

    /** Newest-first, for the "Recently Added" home row. Reactive so it fills in as VOD sync lands. */
    @Query(RECENT_SERIES)
    fun observeRecentlyAdded(limit: Int): Flow<List<Series>>

    /** Every series, newest first — working set for the Kotlin-side by-genre / more-like-this feeds. */
    @Query(ALL_SERIES)
    suspend fun all(): List<Series>

    /** See [RANKED_FINGERPRINT]. */
    @Query("$RANKED_FINGERPRINT FROM series")
    suspend fun fingerprint(): String

    @Query("SELECT * FROM series WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: Long): List<Series>

    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND seriesId = :seriesId")
    suspend fun bySeriesId(sourceId: Long, seriesId: String): Series?

    /** Every copy of one show: providers list each Quality as its own row, in its own category. */
    @Query("SELECT * FROM series WHERE tmdbId = :tmdbId ORDER BY id")
    suspend fun byTmdbId(tmdbId: String): List<Series>

    /** At most a title's worth of shows, in no particular order. */
    @Query("SELECT * FROM series WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<Series>

    /** Every show as grouping into titles reads it, again whenever the table changes. */
    @Query("SELECT id, sourceId, name, tmdbId, year, categoryId FROM series ORDER BY id")
    fun observeVersionRows(): Flow<List<VersionRow>>

    /**
     * Series whose cast credits the person filed under [nameKey], best-rated first. See
     * [MovieDao.moviesWithPerson].
     */
    @Query(SERIES_WITH_PERSON)
    suspend fun seriesWithPerson(nameKey: String, limit: Int): List<Series>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun byId(id: Long): Series?

    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND seriesId = :seriesId LIMIT 1")
    suspend fun byProviderId(sourceId: Long, seriesId: String): Series?

    /** Runs [block] as one transaction; see [MovieDao.inTransaction]. */
    @Transaction
    suspend fun inTransaction(block: suspend () -> Unit) = block()

    /** The stored rows for one sync batch (at most 500) of [seriesIds]. */
    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND seriesId IN (:seriesIds)")
    suspend fun byProviderIds(sourceId: Long, seriesIds: List<String>): List<Series>

    /** Saves these rows as they are. Use [upsertAll], which keeps their credits in step. */
    @Upsert
    suspend fun upsertRows(items: List<Series>): List<Long>

    /** Saves these series, rewriting the [SeriesCredit]s of those whose cast changed. See [MovieDao.upsertAll]. */
    @Transaction
    suspend fun upsertAll(items: List<Series>) {
        val before = credited(items.map { it.id }.filter { it > 0 }).associateBy { it.id }
        val rowIds = upsertRows(items)
        val after = items.mapIndexedNotNull { i, series ->
            val id = if (series.id > 0) series.id else rowIds[i]
            if (id > 0) CreditedTitle(id, series.cast) else null
        }
        recreditChanged(before, after)
    }

    /** Rewrites the credits of each of [after] whose cast isn't what [before] had. */
    suspend fun recreditChanged(before: Map<Long, CreditedTitle>, after: List<CreditedTitle>) {
        for (now in after) {
            if (before[now.id]?.cast == now.cast) continue
            deleteCredits(now.id)
            insertCredits(seriesCredits(now.id, now.cast))
        }
    }

    /**
     * The [limit] series a source most recently added that TMDB hasn't answered about since
     * [staleBefore], for `SeriesTmdbPass`.
     */
    @Query(NEWEST_SERIES_TMDB_DUE)
    suspend fun newestTmdbDue(sourceId: Long, staleBefore: Long, limit: Int): List<Series>

    @Query("SELECT id, `cast`, NULL AS director, NULL AS writer, NULL AS studio FROM series WHERE id IN (:ids)")
    suspend fun credited(ids: List<Long>): List<CreditedTitle>

    /** The next [limit] series after [afterId] with a cast but no [SeriesCredit]s. See [MovieDao.uncredited]. */
    @Query(
        """
        SELECT id, `cast`, NULL AS director, NULL AS writer, NULL AS studio FROM series
        WHERE id > :afterId AND `cast` != '' AND id NOT IN (SELECT seriesId FROM series_credits)
        ORDER BY id LIMIT :limit
        """,
    )
    suspend fun uncredited(afterId: Long, limit: Int): List<CreditedTitle>

    /**
     * Credits the next [limit] [uncredited] titles after [afterId], returning the last one's id, or
     * null when none are left. Read and written in one transaction, so a refresh changing a cast in
     * between can't have its credits replaced with the old names.
     */
    @Transaction
    suspend fun creditNextUncredited(afterId: Long, limit: Int): Long? {
        val titles = uncredited(afterId, limit).ifEmpty { return null }
        recreditChanged(emptyMap(), titles)
        return titles.last().id
    }

    @Query("DELETE FROM series_credits WHERE seriesId = :seriesId")
    suspend fun deleteCredits(seriesId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCredits(credits: List<SeriesCredit>)

    @Query("DELETE FROM series WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE sourceId = :sourceId AND seriesId = :seriesId ORDER BY season, episodeNumber")
    fun observeForSeries(sourceId: Long, seriesId: String): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun byId(id: Long): Episode?

    /**
     * Every episode [profileId] has any progress on. A join rather than `IN (:ids)`: a long watch
     * history would run past SQLite's bound-variable limit. `'ep:' || id` is
     * [app.opentv.data.repo.MediaKey.Episode]'s stored form, which a query can't build through it.
     */
    @Query(
        "SELECT episodes.* FROM episodes JOIN playback_positions " +
            "ON playback_positions.mediaKey = 'ep:' || episodes.id WHERE playback_positions.profileId = :profileId",
    )
    fun observePlayed(profileId: Long): Flow<List<Episode>>

    /**
     * The series [profileId] has progress on an episode of, not counting episodes marked [except], by
     * a join for the same reason, the one with an episode played most recently first. Only the ids: a
     * home feed reads this each time a tab opens, and a long watch history's plots and stream URLs
     * would be felt on a Fire TV Stick.
     */
    @Query(
        "SELECT episodes.sourceId, episodes.seriesId FROM episodes JOIN playback_positions " +
            "ON playback_positions.mediaKey = 'ep:' || episodes.id WHERE playback_positions.profileId = :profileId " +
            "AND (playback_positions.mark IS NULL OR playback_positions.mark != :except) " +
            "GROUP BY episodes.sourceId, episodes.seriesId ORDER BY MAX(playback_positions.updatedAtMillis) DESC",
    )
    suspend fun startedSeries(profileId: Long, except: HandMark): List<SeriesRef>

    /** Every episode of each series [profileId] has played any of, by a join for the same reason. */
    @Query(
        "SELECT * FROM episodes WHERE EXISTS (SELECT 1 FROM episodes AS played JOIN playback_positions " +
            "ON playback_positions.mediaKey = 'ep:' || played.id WHERE playback_positions.profileId = :profileId " +
            "AND played.sourceId = episodes.sourceId AND played.seriesId = episodes.seriesId)",
    )
    suspend fun ofPlayedSeries(profileId: Long): List<Episode>

    @Query("SELECT * FROM episodes WHERE sourceId = :sourceId AND seriesId = :seriesId")
    suspend fun forSeries(sourceId: Long, seriesId: String): List<Episode>

    /** A series' last episode in season then episode order. */
    @Query(
        """
        SELECT * FROM episodes WHERE sourceId = :sourceId AND seriesId = :seriesId
        ORDER BY season DESC, episodeNumber DESC LIMIT 1
        """,
    )
    suspend fun lastOfSeries(sourceId: Long, seriesId: String): Episode?

    @Query("SELECT * FROM episodes WHERE sourceId = :sourceId AND episodeId = :episodeId")
    suspend fun byProviderId(sourceId: Long, episodeId: String): Episode?

    @Upsert
    suspend fun upsertAll(episodes: List<Episode>)

    @Query("DELETE FROM episodes WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}

@Dao
interface PlaybackPositionDao {
    @Query("SELECT * FROM playback_positions WHERE profileId = :profileId AND mediaKey = :key")
    suspend fun get(profileId: Long, key: String): PlaybackPosition?

    // No LIMIT: the caller drops Watched titles first, and a LIMIT applied before that lets a run of
    // newer Watched episodes push every In Progress title off the end.
    @Query("SELECT * FROM playback_positions WHERE profileId = :profileId ORDER BY updatedAtMillis DESC")
    fun observeNewestFirst(profileId: Long): Flow<List<PlaybackPosition>>

    @Query("SELECT * FROM playback_positions")
    suspend fun all(): List<PlaybackPosition>

    /** One profile's positions of one kind ("ep:", "movie:") — a series page needs every episode's. */
    @Query("SELECT * FROM playback_positions WHERE profileId = :profileId AND mediaKey LIKE :prefix || '%'")
    fun observeKind(profileId: Long, prefix: String): Flow<List<PlaybackPosition>>

    /** The same as a one-off read, for a hand mark deciding what it would change. */
    @Query("SELECT * FROM playback_positions WHERE profileId = :profileId AND mediaKey LIKE :prefix || '%'")
    suspend fun ofKind(profileId: Long, prefix: String): List<PlaybackPosition>

    /** One profile's whole watch history — the input to genre-affinity recommendations. */
    @Query("SELECT * FROM playback_positions WHERE profileId = :profileId")
    suspend fun forProfile(profileId: Long): List<PlaybackPosition>

    @Upsert
    suspend fun upsert(position: PlaybackPosition)

    /** One write however long the list, so Continue Watching's queries re-run once for a bulk mark. */
    @Upsert
    suspend fun upsertAll(positions: List<PlaybackPosition>)

    @Query("DELETE FROM playback_positions WHERE profileId = :profileId AND mediaKey = :key")
    suspend fun delete(profileId: Long, key: String)

    @Query("DELETE FROM playback_positions WHERE profileId = :profileId")
    suspend fun deleteFor(profileId: Long)
}

@Dao
interface LivePlaybackDao {
    // Copied from the channel's row in SQL, its source's name and all, so callers never handle names.
    // The name is the one the viewer sees: their rename if they gave one.
    @Query(
        """
        INSERT OR REPLACE INTO live_playback (profileId, source, streamId, name, logoUrl, playedAtMillis)
        SELECT :profileId, src.name, c.streamId, COALESCE(NULLIF(TRIM(c.customName), ''), c.displayName),
            c.logoUrl, :playedAtMillis
        FROM channels c JOIN sources src ON src.id = c.sourceId WHERE c.id = :channelId
        """,
    )
    suspend fun record(profileId: Long, channelId: Long, playedAtMillis: Long)

    @Query("SELECT * FROM live_playback WHERE profileId = :profileId")
    suspend fun get(profileId: Long): LivePlayback?

    @Upsert
    suspend fun upsert(playback: LivePlayback)

    @Query("UPDATE live_playback SET otherPlayedAtMillis = :playedAtMillis WHERE profileId = :profileId")
    suspend fun playedOther(profileId: Long, playedAtMillis: Long)

    @Query("SELECT * FROM live_playback WHERE profileId = :profileId")
    fun observe(profileId: Long): Flow<LivePlayback?>

    @Query(
        """
        SELECT c.* FROM channels c JOIN sources src ON src.id = c.sourceId
        WHERE src.name = :source AND c.streamId = :streamId
        """,
    )
    fun observeChannel(source: String, streamId: String): Flow<Channel?>

    @Query("DELETE FROM live_playback WHERE profileId = :profileId")
    suspend fun deleteFor(profileId: Long)
}

@Dao
interface SeriesDismissalDao {
    @Query("SELECT * FROM series_dismissals WHERE profileId = :profileId")
    fun observe(profileId: Long): Flow<List<SeriesDismissal>>

    @Query("SELECT * FROM series_dismissals WHERE profileId = :profileId")
    suspend fun forProfile(profileId: Long): List<SeriesDismissal>

    @Query(
        """
        SELECT * FROM series_dismissals
        WHERE profileId = :profileId AND sourceId = :sourceId AND seriesId = :seriesId
        """,
    )
    suspend fun get(profileId: Long, sourceId: Long, seriesId: String): SeriesDismissal?

    @Query("SELECT * FROM series_dismissals")
    suspend fun all(): List<SeriesDismissal>

    @Upsert
    suspend fun upsert(dismissal: SeriesDismissal)

    @Query("DELETE FROM series_dismissals WHERE profileId = :profileId")
    suspend fun deleteFor(profileId: Long)
}

/**
 * Two subqueries, not one SELECT of both: SQLite answers a lone MIN or MAX from an index, but two
 * aggregates in one SELECT walk the whole table.
 */
const val GUIDE_FINGERPRINT_QUERY =
    "SELECT (SELECT MAX(id) FROM programmes) AS lastId, (SELECT MIN(endUtcMillis) FROM programmes) AS firstEnd"

/**
 * What's on now, for many channels at once. Ranged over the programmes ending soon after now, not
 * every one still to end: with the index on (end, start) that's a short stretch of the index, and
 * the start is tested there before any row is read. Only what's on is read, not whole programmes.
 */
const val ON_NOW_QUERY =
    "SELECT epgChannelId, startUtcMillis, title FROM programmes " +
        "WHERE endUtcMillis > :nowUtcMillis AND endUtcMillis <= :endsByUtcMillis AND startUtcMillis <= :nowUtcMillis"

/**
 * Changes when a catalogue's titles or anything the home feeds rank them by does, for telling "same as
 * last time" without loading every row. Not a count: a refresh can swap titles at the same size, and
 * ratings and genres are filled in later. Ids only ever grow, so a swapped-in title moves the MAX.
 */
private const val RANKED_FINGERPRINT =
    "SELECT COUNT(*) || ':' || COALESCE(MAX(id), 0) || ':' || TOTAL(rating) || ':' || TOTAL(LENGTH(genre)) " +
        "|| ':' || COALESCE(MAX(addedMillis), 0)"

// By rowid, which only ever grows for a new row, not by time: a TV box's clock can go back (one that
// hasn't synced since booting), and the newest search would sort last and be the one the cap removes.
private const val RECENT_SEARCHES_NEWEST_FIRST =
    "SELECT text FROM recent_searches WHERE profileId = :profileId ORDER BY rowid DESC"

@Dao
interface RecentSearchDao {
    @Query(RECENT_SEARCHES_NEWEST_FIRST)
    fun observe(profileId: Long): Flow<List<String>>

    @Query(RECENT_SEARCHES_NEWEST_FIRST)
    suspend fun forProfile(profileId: Long): List<String>

    @Query("SELECT * FROM recent_searches WHERE profileId = :profileId")
    suspend fun rowsFor(profileId: Long): List<RecentSearch>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(search: RecentSearch)

    @Query("DELETE FROM recent_searches WHERE profileId = :profileId AND text = :text")
    suspend fun delete(profileId: Long, text: String)

    @Query("DELETE FROM recent_searches WHERE profileId = :profileId")
    suspend fun deleteFor(profileId: Long)
}

@Dao
interface FavouriteDao {
    @Query(
        """
        SELECT EXISTS(
          SELECT 1 FROM favourites WHERE profileId = :profileId AND kind = :kind AND itemId = :itemId
            AND removed = 0 AND source = (SELECT name FROM sources WHERE id = :sourceId)
        )
        """,
    )
    suspend fun contains(profileId: Long, kind: StreamKind, sourceId: Long, itemId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favourite: Favourite)

    @Query(
        """
        SELECT * FROM favourites
        WHERE profileId = :profileId AND kind = :kind AND source = :source AND itemId = :itemId
        """,
    )
    suspend fun find(profileId: Long, kind: StreamKind, source: String, itemId: String): Favourite?

    /** Every row, those marked removed included: what sync sends. Viewers see the queries below. */
    @Query("SELECT * FROM favourites")
    suspend fun all(): List<Favourite>

    @Query("DELETE FROM favourites WHERE profileId = :profileId")
    suspend fun deleteFor(profileId: Long)

    @Query(
        """
        SELECT m.*, f.changedAtMillis AS starredAtMillis FROM favourites f
        JOIN sources src ON src.name = f.source
        JOIN movies m ON m.sourceId = src.id AND m.streamId = f.itemId
        WHERE f.profileId = :profileId AND f.removed = 0 AND f.kind = 'MOVIE'
        """,
    )
    fun observeStarredMovies(profileId: Long): Flow<List<StarredMovie>>

    @Query(
        """
        SELECT s.*, f.changedAtMillis AS starredAtMillis FROM favourites f
        JOIN sources src ON src.name = f.source
        JOIN series s ON s.sourceId = src.id AND s.seriesId = f.itemId
        WHERE f.profileId = :profileId AND f.removed = 0 AND f.kind = 'SERIES'
        """,
    )
    fun observeStarredSeries(profileId: Long): Flow<List<StarredSeries>>

    @Query(
        """
        SELECT c.* FROM favourites f
        JOIN sources src ON src.name = f.source
        JOIN channels c ON c.sourceId = src.id AND c.streamId = f.itemId
        WHERE f.profileId = :profileId AND f.removed = 0 AND f.kind = 'LIVE' AND c.hidden = 0
        ORDER BY c.sortIndex, c.displayName
        """,
    )
    fun observeChannels(profileId: Long): Flow<List<Channel>>

    @Query(
        """
        SELECT c.id FROM favourites f
        JOIN sources src ON src.name = f.source
        JOIN channels c ON c.sourceId = src.id AND c.streamId = f.itemId
        WHERE f.profileId = :profileId AND f.removed = 0 AND f.kind = 'LIVE'
        """,
    )
    fun observeChannelIds(profileId: Long): Flow<List<Long>>
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY id")
    fun observeAll(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles ORDER BY id")
    suspend fun all(): List<Profile>

    @Insert
    suspend fun insert(profile: Profile): Long

    @Query("UPDATE profiles SET name = :name, namedAtMillis = :namedAtMillis WHERE id = :id")
    suspend fun rename(id: Long, name: String, namedAtMillis: Long)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM profiles WHERE id = :id)")
    suspend fun exists(id: Long): Boolean
}

@Dao
interface ProfileSettingDao {
    @Query("SELECT * FROM profile_settings WHERE profileId = :profileId")
    fun observeFor(profileId: Long): Flow<List<ProfileSetting>>

    @Query("SELECT * FROM profile_settings")
    suspend fun all(): List<ProfileSetting>

    @Query("SELECT * FROM profile_settings WHERE profileId = :profileId AND name = :name")
    suspend fun find(profileId: Long, name: String): ProfileSetting?

    @Upsert
    suspend fun upsert(setting: ProfileSetting)

    @Query("DELETE FROM profile_settings WHERE profileId = :profileId")
    suspend fun deleteFor(profileId: Long)
}

@Dao
interface RecordingDao {
    /** The library, newest first — scheduled and in-progress float to the top by recency. */
    @Query("SELECT * FROM recordings ORDER BY COALESCE(NULLIF(startedAtMillis, 0), scheduledStartMillis) DESC, id DESC")
    fun observeAll(): Flow<List<Recording>>

    /** Just the ones capturing right now — the player watches this to light its Record button. */
    @Query("SELECT * FROM recordings WHERE status = 'RECORDING'")
    fun observeActive(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE status = 'RECORDING'")
    suspend fun active(): List<Recording>

    @Query("SELECT * FROM recordings WHERE status = 'SCHEDULED' ORDER BY scheduledStartMillis")
    suspend fun scheduled(): List<Recording>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun byId(id: Long): Recording?

    /** Snapshot of every recording — the NAS sync reads this to gather the smb:// ones to share. */
    @Query("SELECT * FROM recordings")
    suspend fun all(): List<Recording>

    /** The Profile is being removed: its recordings stay, for everyone. */
    @Query("UPDATE recordings SET profileId = NULL WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: Long)

    @Query("UPDATE recordings SET profileId = :into WHERE profileId = :from")
    suspend fun moveProfile(from: Long, into: Long)

    /** De-dup for NAS sync: a recording's smb:// locator is its stable cross-device identity. */
    @Query("SELECT * FROM recordings WHERE filePath = :filePath LIMIT 1")
    suspend fun byFilePath(filePath: String): Recording?

    /** For series-link de-dup: has this rule already booked this programme window on this channel? */
    @Query(
        """
        SELECT COUNT(*) FROM recordings
        WHERE seriesRuleId = :ruleId AND channelId = :channelId AND scheduledStartMillis = :startMillis
        """,
    )
    suspend fun countForRuleAt(ruleId: Long, channelId: Long, startMillis: Long): Int

    /** Any live/scheduled booking for this exact airing (same channel + start) — for manual de-dup. */
    @Query(
        """
        SELECT * FROM recordings
        WHERE channelId = :channelId AND scheduledStartMillis = :startMillis
          AND status IN ('SCHEDULED', 'RECORDING') LIMIT 1
        """,
    )
    suspend fun bookingAt(channelId: Long, startMillis: Long): Recording?

    @Insert
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Query("UPDATE recordings SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: RecordingStatus, error: String? = null)

    @Query("UPDATE recordings SET sizeBytes = :bytes WHERE id = :id")
    suspend fun setSize(id: Long, bytes: Long)

    /** Repoint a row at its real locator — used when a USB (SAF) capture learns its content:// URI. */
    @Query("UPDATE recordings SET filePath = :filePath WHERE id = :id")
    suspend fun setFilePath(id: Long, filePath: String)

    @Query(
        "UPDATE recordings SET status = :status, startedAtMillis = :startedAt WHERE id = :id",
    )
    suspend fun markStarted(id: Long, status: RecordingStatus, startedAt: Long)

    @Query(
        "UPDATE recordings SET status = :status, endedAtMillis = :endedAt, sizeBytes = :bytes, error = :error WHERE id = :id",
    )
    suspend fun markFinished(id: Long, status: RecordingStatus, endedAt: Long, bytes: Long, error: String?)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * On a cold start, any row still marked RECORDING is a leftover from a process that was
     * killed mid-capture — reconcile it to FAILED so the UI never shows a phantom "recording".
     */
    @Query("UPDATE recordings SET status = 'FAILED', error = 'Interrupted' WHERE status = 'RECORDING'")
    suspend fun failInterrupted()
}

@Dao
interface SeriesRuleDao {
    @Query("SELECT * FROM series_rules ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<SeriesRule>>

    @Query("SELECT * FROM series_rules WHERE enabled = 1")
    suspend fun enabled(): List<SeriesRule>

    @Query("SELECT * FROM series_rules WHERE channelId = :channelId AND titleKey = :titleKey LIMIT 1")
    suspend fun forChannelTitle(channelId: Long, titleKey: String): SeriesRule?

    @Insert
    suspend fun insert(rule: SeriesRule): Long

    @Query("DELETE FROM series_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ReminderDao {
    /** All reminders, soonest first — the reminders list and boot re-arm both read this. */
    @Query("SELECT * FROM reminders ORDER BY startUtcMillis")
    fun observeAll(): Flow<List<Reminder>>

    /** Still-future, not-yet-fired reminders — the set to re-arm after a reboot. */
    @Query("SELECT * FROM reminders WHERE fired = 0 AND startUtcMillis > :nowMillis ORDER BY startUtcMillis")
    suspend fun upcoming(nowMillis: Long): List<Reminder>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): Reminder?

    /** De-dup: is there already a reminder for this (channel, slot)? */
    @Query("SELECT * FROM reminders WHERE channelId = :channelId AND startUtcMillis = :startMillis LIMIT 1")
    suspend fun forProgramme(channelId: Long, startMillis: Long): Reminder?

    /** The guide grid asks: which upcoming slots already have a bell? Keyed by start time. */
    @Query("SELECT startUtcMillis FROM reminders WHERE channelId = :channelId AND fired = 0")
    fun observeStartsForChannel(channelId: Long): Flow<List<Long>>

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Query("UPDATE reminders SET fired = 1 WHERE id = :id")
    suspend fun markFired(id: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reminders WHERE channelId = :channelId AND startUtcMillis = :startMillis")
    suspend fun deleteForProgramme(channelId: Long, startMillis: Long)

    /** Housekeeping: drop reminders whose programme has already ended. */
    @Query("DELETE FROM reminders WHERE endUtcMillis < :beforeMillis")
    suspend fun deleteEndedBefore(beforeMillis: Long)
}

/** A Favourite movie and when the Profile last starred it. */
data class StarredMovie(@Embedded val movie: Movie, val starredAtMillis: Long)

/** A Favourite series and when the Profile last starred it. */
data class StarredSeries(@Embedded val series: Series, val starredAtMillis: Long)

/**
 * Home's Recently Added films, by walking the `addedMillis` index backwards to the limit. Room re-runs it
 * on every write to `movies`, which the background title lookups make every few seconds, so sorting the
 * whole library each time would cost Home its smoothness. A const so the query-plan test reads it.
 * Providers stamp a whole import alike, so `id` settles which of a tie make the limit, the last saved
 * first, rather than leaving it to the plan; the index holds the rowid, so it still needs no sort.
 */
internal const val RECENT_MOVIES = "SELECT * FROM movies ORDER BY addedMillis DESC, id DESC LIMIT :limit"

/** Home's Recently Added shows. See [RECENT_MOVIES]. */
internal const val RECENT_SERIES = "SELECT * FROM series ORDER BY addedMillis DESC, id DESC LIMIT :limit"

/**
 * Every film, newest first, read straight through the table and sorted, as before `addedMillis` was
 * indexed. Left alone, SQLite walks that index instead and fetches each row out of order by id, which
 * reads the same rows less sequentially. NOT INDEXED keeps the index to Recently Added, which stops at
 * its limit. Consts like these are read by the query-plan tests.
 */
internal const val ALL_MOVIES = "SELECT * FROM movies NOT INDEXED ORDER BY addedMillis DESC"

/** Every series, newest first. See [ALL_MOVIES]. */
internal const val ALL_SERIES = "SELECT * FROM series NOT INDEXED ORDER BY addedMillis DESC"

/** A category's films, or every film, newest first. See [ALL_MOVIES]. */
internal const val MOVIES_IN_CATEGORY = """
    SELECT * FROM movies NOT INDEXED
    WHERE (:categoryId IS NULL OR categoryId = :categoryId)
    ORDER BY addedMillis DESC, name
"""

/** A category's series, or every series, newest first. See [ALL_MOVIES]. */
internal const val SERIES_IN_CATEGORY = """
    SELECT * FROM series NOT INDEXED
    WHERE (:categoryId IS NULL OR categoryId = :categoryId)
    ORDER BY addedMillis DESC, name
"""

/** A source's newest films due a lookup: found through `sourceId`, not by walking every source's titles. */
internal const val NEWEST_MOVIES_NOT_LOOKED_UP =
    "SELECT * FROM movies WHERE sourceId = :sourceId " +
        "AND (detailsLookedUp = 0 OR " +
        "(:withTmdb AND (tmdbAnsweredAt IS NULL OR tmdbAnsweredAt < :tmdbStaleBefore))) " +
        "ORDER BY addedMillis DESC, id DESC LIMIT :limit"

/** A source's newest series due a TMDB lookup. See [NEWEST_MOVIES_NOT_LOOKED_UP]. */
internal const val NEWEST_SERIES_TMDB_DUE =
    "SELECT * FROM series WHERE sourceId = :sourceId " +
        "AND (tmdbAnsweredAt IS NULL OR tmdbAnsweredAt < :staleBefore) " +
        "ORDER BY addedMillis DESC, id DESC LIMIT :limit"

/** A person's films, by an index lookup on their credits. A const so the query-plan test reads it. */
internal const val MOVIES_WITH_PERSON = """
    SELECT * FROM movies
    WHERE id IN (SELECT movieId FROM movie_credits WHERE nameKey = :nameKey)
    ORDER BY rating DESC, addedMillis DESC
    LIMIT :limit
"""

/** A person's shows. See [MOVIES_WITH_PERSON]. */
internal const val SERIES_WITH_PERSON = """
    SELECT * FROM series
    WHERE id IN (SELECT seriesId FROM series_credits WHERE nameKey = :nameKey)
    ORDER BY rating DESC, addedMillis DESC
    LIMIT :limit
"""
