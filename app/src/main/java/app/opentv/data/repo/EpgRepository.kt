/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.util.Log
import app.opentv.R
import app.opentv.core.ErrorWording
import app.opentv.core.ViewerError
import app.opentv.data.db.ChannelDao
import app.opentv.data.db.EpgChannelAliasDao
import app.opentv.data.db.EpgFeedDao
import app.opentv.data.db.ProgrammeDao
import app.opentv.data.db.SourceDao
import app.opentv.data.model.ChannelEpgMatch
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.GuideProgramme
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.parser.XmltvParser
import app.opentv.data.remote.CountingInputStream
import app.opentv.data.remote.XtreamApi
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody

/**
 * Owns the electronic programme guide, end to end: feeds in, matched channels out.
 *
 * ## The feed model
 *
 * Guide data can come from three places at once — the provider's own XMLTV (usually thin or
 * empty), the curated free sources shipped with the app, and URLs the user adds. Each is an
 * [EpgFeed] row; every enabled feed is downloaded and merged into one guide. After a sync,
 * [EpgMatcher] joins provider channels to guide channels by normalised name, which is what
 * makes a free national guide light up channels named `UK| BBC ONE FHD`.
 *
 * ## The failure this class exists to prevent
 *
 * The standard way to refresh an EPG is: delete everything, download the new XMLTV, insert
 * it. It is simple and it is why so many IPTV players lose their guide — one stalled
 * download and the user is left with nothing, plus advice to reinstall.
 *
 * OpenTV never deletes before it has the replacement:
 *
 * - Programmes are **upserted in batches** as they stream out of the parser. The unique index
 *   on `(feedId, epgChannelId, startUtcMillis)` makes a re-run idempotent, so a sync that
 *   dies at 60% leaves 60% of a fresher guide behind — strictly better than before.
 * - Old programmes are pruned **by age**, only after at least one feed succeeds.
 * - A failed feed leaves that feed's previous data intact and records the reason on the
 *   feed row, where the settings screen shows it — no silent failure.
 */
/** What a channel has on [now], if anything, and [next]: the card in the player, the Live Preview on Home. */
data class NowAndNext(val now: Programme?, val next: Programme?)

/** What's showing at [nowUtcMillis], from [EpgRepository.upcoming]: its first may not have started yet. */
internal fun List<Programme>.onNowAt(nowUtcMillis: Long): Programme? =
    firstOrNull()?.takeIf { it.startUtcMillis <= nowUtcMillis }

class EpgRepository(
    private val programmeDao: ProgrammeDao,
    private val feedDao: EpgFeedDao,
    private val aliasDao: EpgChannelAliasDao,
    private val channelDao: ChannelDao,
    private val sourceDao: SourceDao,
    private val api: XtreamApi,
    private val http: OkHttpClient,
    /** Words a feed that failed: its last result is shown in Guide settings. */
    private val wording: ErrorWording,
    private val selfEnabledGuide: SelfEnabledGuide,
) {

    /**
     * The built-in guide Settee switched on by itself, by name rather than id: ids restart when the
     * database is rebuilt, and the name is what [BUILT_IN_FEEDS] keys on. Null once the viewer has
     * switched it on or off themselves, which makes it their choice.
     */
    interface SelfEnabledGuide {
        var name: String?

        /** For tests: the app keeps it in [app.opentv.core.AppSettings] so it outlives the process. */
        class InMemory(override var name: String? = null) : SelfEnabledGuide
    }

    data class SyncSummary(
        val feedsSucceeded: Int,
        val feedsFailed: Int,
        val programmesWritten: Int,
        val channelsMatched: Int,
        val channelsTotal: Int,
    )

    // ---- Reads -----------------------------------------------------------------------------

    fun observeFeeds(): Flow<List<EpgFeed>> = feedDao.observeAll()

    // Settled: Room re-runs it for every batch a guide sync writes, and whoever watches it would
    // otherwise redo its work hundreds of times per sync (#257).
    fun observeGuideWindow(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<GuideProgramme>> =
        programmeDao.observeGuideWindow(fromUtcMillis, toUtcMillis).settled()

    /**
     * A programme the guide lists, in full, found by its slot so a guide refresh since doesn't lose it.
     * Null only once the guide has dropped that airing.
     */
    suspend fun programme(listed: GuideProgramme): Programme? =
        programmeDao.startingAt(listed.epgChannelId, listed.startUtcMillis)

    suspend fun upcoming(epgChannelId: String, nowUtcMillis: Long, limit: Int = 12): List<Programme> =
        programmeDao.upcoming(epgChannelId, nowUtcMillis, limit)

    /**
     * What's on [epgChannelId] at [nowUtcMillis] and what starts after it. Next is the first programme
     * starting after now rather than the second row: rows are unique per feed, so two feeds carrying
     * one channel list each programme twice, and the second row would repeat what's on now.
     */
    suspend fun nowAndNext(epgChannelId: String, nowUtcMillis: Long): NowAndNext {
        val shows = upcoming(epgChannelId, nowUtcMillis, limit = NOW_AND_NEXT_ROWS)
        return NowAndNext(shows.onNowAt(nowUtcMillis), shows.firstOrNull { it.startUtcMillis > nowUtcMillis })
    }

    /** What's showing at [nowUtcMillis]. */
    suspend fun programmeOnNow(epgChannelId: String, nowUtcMillis: Long): Programme? =
        upcoming(epgChannelId, nowUtcMillis, limit = 1).onNowAt(nowUtcMillis)

    // ---- Feed management -------------------------------------------------------------------

    suspend fun addCustomFeed(name: String, url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || feedDao.byUrl(trimmed) != null) return
        feedDao.insert(
            EpgFeed(name = name.trim().ifBlank { trimmed }, url = trimmed, enabled = true),
        )
    }

    suspend fun setFeedEnabled(feed: EpgFeed, enabled: Boolean) {
        if (feed.isSelfEnabled(selfEnabledGuide.name)) selfEnabledGuide.name = null
        feedDao.setEnabled(feed.id, enabled)
    }

    suspend fun removeFeed(feed: EpgFeed) {
        // A removed feed takes its guide data with it — orphaned programmes would otherwise
        // keep matching channels forever with content that never refreshes.
        programmeDao.deleteForFeed(feed.id)
        aliasDao.deleteForFeed(feed.id)
        feedDao.delete(feed.id)
    }

    /**
     * Makes sure the standing feed rows exist: one per provider source, plus the curated
     * built-in list. Insert-if-absent, so user toggles survive every call. A built-in whose address
     * has moved keeps its row, toggle included, and takes the new address: matching by address
     * alone would leave the dead one switched on beside a second, switched-off row of the same name.
     */
    suspend fun ensureFeeds() {
        for (source in sourceDao.enabled()) {
            if (feedDao.forProvider(source.id) == null) {
                feedDao.insert(
                    EpgFeed(
                        name = "${source.name} (provider guide)",
                        providerSourceId = source.id,
                        enabled = true,
                    ),
                )
            }
        }
        val builtIns = feedDao.all().filter { it.builtIn }.associateBy { it.name }
        for ((name, url) in BUILT_IN_FEEDS) {
            val existing = builtIns[name]
            if (existing != null) {
                if (existing.url != url) {
                    // The new file names its channels differently (epgshare01's `.us1` became `.us2`), so
                    // nothing would ever replace the old file's aliases: left, they keep matching channels
                    // to ids whose programmes have aged out. Its programmes go by age as usual.
                    aliasDao.deleteForFeed(existing.id)
                    feedDao.update(existing.copy(url = url, lastResult = ""))
                }
            } else if (feedDao.byUrl(url) == null) {
                // Off by default: shipping a switched-on 20 MB download for a country the
                // user may not live in would be rude. The EPG settings screen makes
                // enabling one a single click.
                feedDao.insert(EpgFeed(name = name, url = url, builtIn = true, enabled = false))
            }
        }
    }

    // ---- Sync ------------------------------------------------------------------------------

    /**
     * Downloads every enabled feed, merges, prunes, and re-runs the matcher. [skipFetchedSince] leaves
     * out feeds already downloaded at or after that time, even when [force]d. [progress] hears how far
     * through it is, 0 to 1, never backwards; see [GuideProgress].
     */
    suspend fun syncAll(
        nowUtcMillis: Long,
        force: Boolean = false,
        skipFetchedSince: Long? = null,
        progress: (SyncProgress) -> Unit = {},
    ): SyncSummary = withContext(Dispatchers.IO) {
        ensureFeeds()
        maybeAutoEnableRegionalFeed()

        var succeeded = 0
        var failed = 0
        var written = 0

        val (due, fresh) = feedDao.enabled().partition { feed ->
            val inWindow = !force && nowUtcMillis - feed.lastSyncMillis < REFRESH_INTERVAL_MILLIS
            !(inWindow || (skipFetchedSince != null && feed.lastSyncMillis >= skipFetchedSince))
        }
        succeeded += fresh.size
        val reporting = GuideProgress(feeds = due.size, progress)
        reporting.feedsReady()

        for ((index, feed) in due.withIndex()) {
            val result = syncFeed(
                feed,
                nowUtcMillis,
                read = { read -> reporting.reading(index, read) },
                counted = reporting::programme,
            )
            reporting.feedDone(index)
            when (result) {
                is FeedResult.Success -> {
                    succeeded++
                    written += result.programmes
                    feedDao.markSynced(
                        feed.id,
                        nowUtcMillis,
                        "${result.programmes} programmes, ${result.channels} channels",
                    )
                }
                is FeedResult.Failed -> {
                    // A guide the viewer never asked for doesn't make the refresh a failure (Setup
                    // would say the guide didn't load); Guide settings still shows why it failed.
                    if (!feed.isSelfEnabled(selfEnabledGuide.name)) failed++
                    // The stamp is NOT advanced on failure, so the next sync retries
                    // rather than waiting out the interval on a feed that never landed.
                    feedDao.markSynced(feed.id, feed.lastSyncMillis, result.reason.stored())
                    Log.w(TAG, "Feed '${feed.name}' failed: ${result.reason.detail}")
                }
            }
        }

        if (succeeded > 0) {
            programmeDao.deleteEndedBefore(nowUtcMillis - RETENTION_PAST_MILLIS)
        }

        val (matched, total) = runMatcher()
        reporting.done()
        SyncSummary(succeeded, failed, written, matched, total)
    }

    /**
     * How far through [syncAll] a pass is. Shared as it went on a Fire TV Stick with a big provider
     * (#224): setting up the feeds about 7% of the time, downloading them 85%, matching them to the
     * channels 8%. A feed's slice moves with the bytes read where the server says how many there
     * are; otherwise it fills when the feed is done.
     */
    private class GuideProgress(private val feeds: Int, private val report: (SyncProgress) -> Unit) {
        private var last = -1f
        private var programmes = 0
        private var reportedProgrammes = 0

        private fun at(value: Float) {
            // Every whole half-percent, not every read: a big guide is thousands of reads.
            if (value >= 1f || value - last >= STEP) {
                last = value
                tell()
            }
        }

        private fun tell() {
            reportedProgrammes = programmes
            report(SyncProgress(last.coerceAtLeast(0f), programmes))
        }

        /**
         * Another programme read. A guide whose size the server doesn't give can't move the share,
         * but a count going up still shows it's arriving, so that's told every [PROGRAMMES] too.
         */
        fun programme() {
            programmes++
            if (programmes - reportedProgrammes >= PROGRAMMES) tell()
        }

        fun feedsReady() = at(SETUP)

        /** [read] is the share of feed [index] read so far, 0 to 1. */
        fun reading(index: Int, read: Float) = at(SETUP + FEEDS * (index + read.coerceIn(0f, 1f)) / feeds)

        fun feedDone(index: Int) = at(SETUP + FEEDS * (index + 1) / feeds)

        fun done() {
            last = 1f
            tell()
        }

        companion object {
            const val SETUP = 0.07f
            const val FEEDS = 0.85f
            const val STEP = 0.005f
            const val PROGRAMMES = 500
        }
    }

    /** How far through [syncAll] a pass is: its [share], 0 to 1, and the [programmes] read so far. */
    data class SyncProgress(val share: Float, val programmes: Int)

    private sealed interface FeedResult {
        data class Success(val programmes: Int, val channels: Int) : FeedResult
        data class Failed(val reason: ViewerError) : FeedResult
    }

    private suspend fun syncFeed(
        feed: EpgFeed,
        nowUtcMillis: Long,
        read: (Float) -> Unit,
        counted: () -> Unit,
    ): FeedResult {
        val batch = ArrayList<Programme>(BATCH_SIZE)
        val aliases = ArrayList<EpgChannelAlias>(256)
        var written = 0

        try {
            openFeedStream(feed, read).use { stream ->
                val stats = XmltvParser.parse(
                    input = stream,
                    feedId = feed.id,
                    onChannelAlias = { id, displayName ->
                        aliases += EpgChannelAlias(
                            feedId = feed.id,
                            epgId = id,
                            displayName = displayName ?: id,
                            normalizedKey = ChannelNameNormalizer.normalize(displayName ?: id).groupKey,
                        )
                    },
                    onProgramme = { programme ->
                        counted()
                        // Skip anything that finished before the retention cut-off; no point
                        // writing rows we are about to prune.
                        if (programme.endUtcMillis >= nowUtcMillis - RETENTION_PAST_MILLIS) {
                            batch += programme
                            if (batch.size >= BATCH_SIZE) {
                                programmeDao.insertOrReplace(batch)
                                written += batch.size
                                batch.clear()
                            }
                        }
                    },
                )
                if (batch.isNotEmpty()) {
                    programmeDao.insertOrReplace(batch)
                    written += batch.size
                }
                if (aliases.isNotEmpty()) {
                    aliases.chunked(BATCH_SIZE).forEach { aliasDao.upsertAll(it) }
                }

                if (written == 0 && stats.programmeCount == 0) {
                    return FeedResult.Failed(wording.of(R.string.error_guide_empty, "NoProgrammes"))
                }
                return FeedResult.Success(written, stats.channelCount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Deliberately no cleanup. Whatever was written is newer than what was there,
            // and what was there is still there.
            Log.w(TAG, "Feed '${feed.name}' download failed", e)
            return FeedResult.Failed(wording.of(e, R.string.error_guide))
        }
    }

    /**
     * Opens a feed as a decompressed XML stream.
     *
     * Several of the free guide sources publish `.gz` files (a national guide compresses
     * roughly 10:1), and providers occasionally gzip xmltv.php without saying so. Sniffing
     * the first two bytes for the gzip magic number handles both, regardless of what the
     * URL or the headers claim.
     */
    private suspend fun openFeedStream(feed: EpgFeed, read: (Float) -> Unit): InputStream {
        val body: ResponseBody = when {
            feed.providerSourceId != null -> {
                val source: Source = sourceDao.byId(feed.providerSourceId)
                    ?: throw IllegalStateException("Provider for this guide no longer exists.")
                api.openEpg(source)
            }
            feed.url != null -> {
                val response = http.newCall(Request.Builder().url(feed.url).build()).execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw IllegalStateException("Guide download failed (HTTP ${response.code}).")
                }
                response.body
            }
            else -> throw IllegalStateException("Feed has no URL and no provider.")
        }
        // Counted before decompressing: the size the server gives is of what it sends.
        val raw = body.contentLength().let { size ->
            if (size > 0) CountingInputStream(body.byteStream()) { read(it.toFloat() / size) } else body.byteStream()
        }

        val buffered = BufferedInputStream(raw, 8 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered
    }

    // ---- Matching --------------------------------------------------------------------------

    /**
     * Joins every channel to the merged guide by normalised name.
     * Returns (channels with a working guide id, total channels).
     */
    suspend fun runMatcher(): Pair<Int, Int> {
        val aliases = aliasDao.all()
        val index = EpgMatcher.buildIndex(aliases.map { it.epgId to it.displayName })
        // Only ids that actually have programmes count as "working" — a match against a
        // channel the guide lists but never fills is a guide that looks broken.
        val populated = programmeDao.channelIdsWithProgrammes().toHashSet()

        val channels = channelDao.matcherInputs()
        var matched = 0
        val changed = ArrayList<ChannelEpgMatch>()

        for (channel in channels) {
            val newMatch = index.match(ChannelNameNormalizer.nameKeyOf(channel.groupKey))
            if (newMatch != channel.matchedEpgId) changed += ChannelEpgMatch(channel.id, newMatch)
            val works = channel.epgCandidates.any { it in populated } ||
                (newMatch != null && newMatch in populated)
            if (works) matched++
        }
        // All at once, unlike programmes: a match is cheap to work out again if this is interrupted,
        // and each separate transaction would make the lineup re-read every channel.
        if (changed.isNotEmpty()) channelDao.setMatchedEpgIds(changed)

        Log.i(
            TAG,
            "Matcher: $matched of ${channels.size} channels have a working guide " +
                "(${aliases.size} guide aliases, ${populated.size} populated guide channels)",
        )
        return matched to channels.size
    }

    suspend fun setManualOverride(channelId: Long, epgId: String?) = channelDao.setEpgOverride(channelId, epgId)

    /**
     * Turns on the free guide for the user's region, once, on a pristine install.
     *
     * The reasoning: a provider guide is usually empty, and "go and find Guide settings"
     * is a hurdle most people meet as a blank guide and give up on. The app already knows
     * the region — the normaliser reads it off the `UK|` prefixes the provider itself
     * ships — so when a clear majority of channels agree, enable that region's built-in.
     *
     * Guard rails: only when every built-in is untouched (never enabled, never synced)
     * and no custom feed exists. Switch it off and it stays off — a choice the user has
     * made is never overridden.
     */
    private suspend fun maybeAutoEnableRegionalFeed() {
        val all = feedDao.all()
        val builtIns = all.filter { it.builtIn }
        if (builtIns.isEmpty()) return
        val pristine = builtIns.all { !it.enabled && it.lastSyncMillis == 0L } &&
            all.none { !it.builtIn && it.providerSourceId == null }
        if (!pristine) return

        val regionCounts = HashMap<String, Int>()
        for (name in channelDao.providerNames()) {
            val region = ChannelNameNormalizer.normalize(name).region ?: continue
            regionCounts.merge(region, 1, Int::plus)
        }
        val top = regionCounts.maxByOrNull { it.value } ?: return
        if (top.value < MIN_CHANNELS_FOR_AUTO_REGION) return

        val feedName = REGION_TO_FEED[top.key] ?: return
        val feed = builtIns.firstOrNull { it.name == feedName } ?: return
        feedDao.setEnabled(feed.id, true)
        selfEnabledGuide.name = feed.name
        Log.i(TAG, "Auto-enabled '${feed.name}' — ${top.value} channels tagged ${top.key}")
    }

    /** A custom feed can share a built-in's name, so only the built-in row is the one Settee switched on. */
    private fun EpgFeed.isSelfEnabled(selfEnabledName: String?) = builtIn && name == selfEnabledName

    companion object {
        private const val TAG = "EpgRepository"

        /** Writes per transaction. Large enough to be fast, small enough not to hold WAL open. */
        const val BATCH_SIZE = 500

        /** Keep finished programmes for a day so "what was on" still works. */
        val RETENTION_PAST_MILLIS: Long = TimeUnit.DAYS.toMillis(1)

        /** Feeds publish rolling windows; refreshing more often than this is rude. */
        val REFRESH_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(6)

        /**
         * The curated built-in list. Criteria for being here: free, no key or signup,
         * openly licensed or explicitly public, and reachable as plain XMLTV(.gz).
         * All ship disabled; the user turns on the one for where they live.
         *
         * The UK Freeview entry is GPL-3.0 (same licence as OpenTV), regenerated every
         * 12 h, ~270 channels with logos — verified working before it earned this slot.
         */
        /** A region needs at least this many prefixed channels before we act on it. */
        const val MIN_CHANNELS_FOR_AUTO_REGION = 5

        /** Region tag (as providers write it) → built-in feed name to auto-enable. */
        val REGION_TO_FEED: Map<String, String> = mapOf(
            "UK" to "UK — Freeview (free-to-air)",
            "GB" to "UK — Freeview (free-to-air)",
            "US" to "USA — epgshare01",
            "USA" to "USA — epgshare01",
            "CA" to "Canada — epgshare01",
            "AU" to "Australia — epgshare01",
            "AUS" to "Australia — epgshare01",
        )

        val BUILT_IN_FEEDS: List<Pair<String, String>> = listOf(
            "UK — Freeview (free-to-air)" to
                "https://raw.githubusercontent.com/dp247/Freeview-EPG/master/epg.xml",
            "UK — epgshare01 (Sky lineup)" to
                "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
            // epgshare01 renumbers a file when it rebuilds a country's lineup (US1 and CA1 went in
            // 2026, leaving 404s): its directory listing at /epgshare01/ shows the current names.
            "USA — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
            "Canada — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_CA2.xml.gz",
            "Australia — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_AU1.xml.gz",
        )
    }
}

/** Enough rows for what's on now and next with a few feeds carrying the same channel. */
private const val NOW_AND_NEXT_ROWS = 8
