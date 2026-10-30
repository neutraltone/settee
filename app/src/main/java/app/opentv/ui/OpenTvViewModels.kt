/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.core.StatusBus
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Episode
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Movie
import app.opentv.data.model.Profile
import app.opentv.data.model.Programme
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.model.StremioStream
import app.opentv.data.parser.displayTitle
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.CategoryGroup
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.GenreGroup
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.LineupScope
import app.opentv.data.repo.LivePreview
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.MovieVariantGroup
import app.opentv.data.repo.PersonTitle
import app.opentv.pairing.ManagerServer
import app.opentv.sync.NasSync
import app.opentv.sync.SyncClient
import app.opentv.sync.SyncEngine
import app.opentv.sync.SyncServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * View models for the whole app.
 *
 * Grouped in one file on purpose: they are small, they share the same dependency graph, and
 * a contributor tracking down "where does the channel list come from" finds it in one place.
 */

class SourcesViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)

    data class UiState(
        val sources: List<Source> = emptyList(),
        /**
         * False until the saved sources have been read from the database once. Distinguishing
         * "no sources yet loaded" from "loaded, and there are none" is what stops a returning
         * user being sent to the setup screen — and asked for their provider again — during the
         * brief moment before the database responds on a cold launch.
         */
        val loaded: Boolean = false,
        val testing: Boolean = false,
        val testResult: String? = null,
        val testError: String? = null,
        val syncing: Boolean = false,
        val syncMessage: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            graph.sourceRepository.observeAll().collect { sources ->
                _ui.value = _ui.value.copy(sources = sources, loaded = true)
            }
        }
    }

    fun test(draft: Source) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(testing = true, testResult = null, testError = null)
            val result = graph.sourceRepository.test(draft)
            _ui.value = _ui.value.copy(
                testing = false,
                testResult = result.getOrNull(),
                testError = result.exceptionOrNull()?.message,
            )
        }
    }

    /** Saves, then immediately pulls the catalogue so the user sees channels, not a spinner. */
    fun saveAndSync(draft: Source, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(syncing = true, syncMessage = "Saving source…")
            val id = graph.sourceRepository.save(draft)
            val saved = graph.sourceRepository.byId(id)
            if (saved == null) {
                _ui.value = _ui.value.copy(syncing = false, syncMessage = "Could not save source.")
                onDone(false)
                return@launch
            }

            _ui.value = _ui.value.copy(syncMessage = "Loading channels…")
            val now = System.currentTimeMillis()
            // Load live channels first and get the user watching straight away. Movies, series and
            // the guide are what make a big provider take minutes — they load in the background so
            // "loading channels" is a few seconds, not a twenty-minute blank screen.
            when (val result = graph.catalogRepository.syncLive(saved, now)) {
                is CatalogRepository.SyncResult.Failed -> {
                    _ui.value = _ui.value.copy(syncing = false, syncMessage = result.reason)
                    onDone(false)
                    return@launch
                }
                is CatalogRepository.SyncResult.Success -> {
                    _ui.value = _ui.value.copy(
                        syncing = false,
                        syncMessage = "Loaded ${result.channelCount} channels. The guide is " +
                            "loading in the background; Movies and Shows load when you open them.",
                    )
                    onDone(true)
                }
            }

            // Background: the guide. Movies/series are pulled on demand from their own tabs, so
            // nothing the user hasn't asked for ever blocks the channels they can already watch.
            runCatching {
                val summary = StatusBus.during("Building the TV guide…") {
                    graph.epgRepository.syncAll(now)
                }
                _ui.value = _ui.value.copy(
                    syncMessage = when {
                        summary.channelsMatched > 0 ->
                            "Guide ready — matched ${summary.channelsMatched} of " +
                                "${summary.channelsTotal} channels."
                        else ->
                            "Channels ready. No guide data matched yet — add a free guide " +
                                "under Guide settings."
                    },
                )
                // Book any new series-link airings the fresh guide just revealed.
                runCatching { graph.recordingEngine.rescanSeriesRules() }
            }.onFailure { Log.w("OpenTV", "Background VOD/guide load failed", it) }
        }
    }

    fun delete(source: Source) {
        viewModelScope.launch { graph.catalogRepository.deleteSource(source.id) }
    }

    /**
     * Change an Xtream source's live-stream container. The repository rewrites the source's live
     * channel URLs in place, so the switch takes effect without a re-sync; the observed source list
     * then re-emits with the new [Source.liveFormat]. A no-op for M3U sources and unchanged values.
     */
    fun setLiveFormat(source: Source, format: LiveStreamFormat) {
        if (source.kind != SourceKind.XTREAM || source.liveFormat == format) return
        viewModelScope.launch { graph.catalogRepository.setLiveFormat(source.id, format) }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(syncing = true, syncMessage = "Refreshing…")
            val now = System.currentTimeMillis()
            var channels = 0
            var problems = 0
            for (source in graph.sourceRepository.enabled()) {
                when (val result = graph.catalogRepository.sync(source, now)) {
                    is CatalogRepository.SyncResult.Success -> channels += result.channelCount
                    is CatalogRepository.SyncResult.Failed -> problems++
                }
            }
            val summary = graph.epgRepository.syncAll(now, force = true)
            problems += summary.feedsFailed
            runCatching { graph.recordingEngine.rescanSeriesRules() }
            _ui.value = _ui.value.copy(
                syncing = false,
                syncMessage = if (problems == 0) {
                    "Refreshed $channels channels, guide matched " +
                        "${summary.channelsMatched} of ${summary.channelsTotal}."
                } else {
                    "Refreshed $channels channels, $problems problem(s) — see Guide settings."
                },
            )
        }
    }

    fun blankDraft() = Source(name = "", kind = SourceKind.XTREAM, url = "")
}

class ChannelsViewModel @JvmOverloads constructor(
    app: Application,
    private val graph: ServiceLocator.Graph = ServiceLocator.get(app),
) : AndroidViewModel(app) {
    private val lineup = graph.channelLineup

    /** null = All. Exposed so the sidebar can highlight the active entry. */
    val selectedCategory = MutableStateFlow<String?>(null)
    val favouritesOnly = MutableStateFlow(false)

    /** Provider filter for the live guide. null = every source. Only surfaced when there's >1 source. */
    val selectedSource = MutableStateFlow<Long?>(null)
    private val nowTick = MutableStateFlow(System.currentTimeMillis())

    /**
     * Every category of the chosen provider, folded into one entry per logical category, locked
     * ones included: the Parental screen lists them all so they can be locked and unlocked.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val categoryGroups: StateFlow<List<CategoryGroup>> =
        selectedSource.flatMapLatest { lineup.categoryGroups(it, includeLocked = true) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The rail's view of categories: locked ones drop out until the session is unlocked. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val visibleCategoryGroups: StateFlow<List<CategoryGroup>> =
        selectedSource.flatMapLatest { lineup.categoryGroups(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * One row on screen = one *logical* channel.
     *
     * [primary] is the best-quality variant and what plays on click; [variants] is every
     * quality of the same channel, for the in-player switch. `UK| BBC ONE SD/HD/FHD/RAW`
     * is one row, not four — the guide has one BBC One, and so should we.
     */
    @androidx.compose.runtime.Immutable
    data class Row(
        val channel: LineupChannel,
        val now: Programme? = null,
        val next: Programme? = null,
        /** Every programme for this channel inside the guide window, start-ordered. */
        val programmes: List<Programme> = emptyList(),
    ) {
        val primary: Channel get() = channel.primary

        /** Every Quality of the Channel, the same wherever it's shown. */
        val variants: List<Channel> get() = channel.qualities

        /** The Qualities worth offering as a choice: see [LineupChannel.switchable]. */
        val switchable: List<Channel> get() = channel.switchable

        /** Whether the active Profile has this Channel as a Favourite. */
        val favourite: Boolean get() = channel.favourite

        val key: Any get() = channel.key
    }

    /**
     * The guide's left edge: the current half-hour, rounded down. Programme block positions
     * are measured from here. Recomputed on each tick so the grid drifts with real time.
     */
    /** Which day the guide is browsing: 0 = today, 1 = tomorrow… so it pages forward like Sky Q. */
    private val _guideDayOffset = MutableStateFlow(0)
    val guideDayOffset: StateFlow<Int> = _guideDayOffset.asStateFlow()

    /** Page the guide a day forward/back (clamped to today…+[GUIDE_MAX_DAYS]), or jump back to now. */
    fun nudgeGuideDay(delta: Int) {
        _guideDayOffset.value = (_guideDayOffset.value + delta).coerceIn(0, GUIDE_MAX_DAYS)
    }

    fun guideToNow() {
        _guideDayOffset.value = 0
    }

    val windowStartMillis: StateFlow<Long> =
        combine(nowTick.map { it - it % HALF_HOUR_MILLIS }, _guideDayOffset) { base, day ->
            // Today tracks the current half-hour so live is always on screen; a future day starts
            // at that day's local midnight, so the whole day's schedule is browsable and drifts
            // with real time only on the "today" page.
            if (day <= 0) base else startOfLocalDay(base) + day * DAY_MILLIS
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            System.currentTimeMillis().let { it - it % HALF_HOUR_MILLIS },
        )

    private fun startOfLocalDay(millis: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Which Channels the guide shows now; channel up and down follow it from the guide. */
    private val guideScope: Flow<LineupScope> =
        combine(selectedCategory, favouritesOnly, selectedSource, ::guideScopeOf).distinctUntilChanged()

    private fun guideScopeOf(category: String?, favs: Boolean, source: Long?): LineupScope = when {
        favs -> LineupScope.Favourites(source)
        category != null -> LineupScope.Group(category, source)
        else -> LineupScope.All(source)
    }

    /** From Search, channel up and down go through every Channel rather than the results. */
    fun zapFromAll() = lineup.zapFrom(LineupScope.All())

    /** Channel up and down follow the guide's current scope once you play from it. */
    fun zapFromGuide() {
        // Set before the player opens, not in a coroutine that might land after it reads the scope.
        lineup.zapFrom(guideScopeOf(selectedCategory.value, favouritesOnly.value, selectedSource.value))
    }

    /**
     * The whole guide window's programmes, grouped by their EPG channel id once — the single
     * expensive step. Grouping the entire catalogue's programmes (hundreds of thousands of rows
     * on a big provider) is what made every category tap slow, because it used to run inside the
     * per-category flow and rebuild from scratch each time. Built here once, shared across every
     * category switch, so a tap only has to regroup that category's channels — quick.
     *
     * Keyed on the half-hour bucket (not the raw minute tick) so it holds steady while you flick
     * between categories and refreshes at most twice an hour; the underlying Room flow also
     * re-emits on its own when an EPG sync lands new programmes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val programmeIndex: StateFlow<Map<String, List<Programme>>> =
        // windowStartMillis is a StateFlow, which already conflates equal values — an explicit
        // distinctUntilChanged() on it is a no-op (and a build error under our warnings-as-errors).
        windowStartMillis
            .flatMapLatest { windowStart ->
                graph.epgRepository
                    .observeWindow(windowStart, windowStart + GUIDE_LOOKAHEAD_MILLIS)
                    .map { programmes -> programmes.groupBy { it.epgChannelId } }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Guards the start-up loading bar so it shows once, on the first build, not on every tap. */
    @Volatile
    private var guideBuilt = false

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<Row>> =
        guideScope
            .flatMapLatest { scope ->
                // Combine the scope's Channels with the shared, already-grouped programme index.
                // Switching category rebuilds only the channel→row step; the heavy programme
                // grouping happened once and is reused, so a tap is quick.
                // Deliberately NOT combined with the minute tick: building "All channels" (20k
                // rows) can take longer than 60s, and a tick landing mid-build would cancel and
                // restart it via flatMapLatest so it would never finish. "Now" is captured per
                // build instead; the guide's live progress bars advance off the screen's clock.
                combine(lineup.channels(scope), programmeIndex) { channels, byEpgChannel ->
                    val now = System.currentTimeMillis()
                    // Show the size-aware loading bar for the FIRST guide build only (start-up).
                    // After that the guide is built and flicking between categories is cheap, so
                    // don't flash a loading line on every tap — that bar was only ever meant for
                    // the one-time heavy load, and leaking it per-category read as a stuck "100%".
                    val firstBuild = !guideBuilt
                    if (firstBuild) StatusBus.set(sizeMessage(channels.size), 0f)
                    buildRows(channels, byEpgChannel, now, reportProgress = firstBuild)
                }
            }
            // Grouping thousands of channels against a 12-hour, all-feeds programme window is heavy
            // enough to freeze the UI for a big provider — a category tap that took minutes. Run the
            // whole pipeline off the main thread so the list just appears when it's ready.
            .flowOn(Dispatchers.Default)
            // Once the first guide is on screen, clear the start-up bar — and never show it for a
            // plain category switch again.
            .onEach {
                if (!guideBuilt) {
                    guideBuilt = true
                    StatusBus.set(null)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun buildRows(
        channels: List<LineupChannel>,
        byEpgChannel: Map<String, List<Programme>>,
        now: Long,
        reportProgress: Boolean = false,
    ): List<Row> {
        // The programme→channel grouping is done once upstream (see [programmeIndex]) and passed
        // in ready-made, rather than regrouping the whole EPG on every category tap. Grouping is
        // kept out of SQL because the programme query cannot take a channel-id list without
        // hitting SQLite's bound-variable cap.
        val total = channels.size.coerceAtLeast(1)
        var built = 0
        return channels.map { channel ->
            val list = channel.guideId { it in byEpgChannel }
                ?.let { byEpgChannel.getValue(it).sortedBy { p -> p.startUtcMillis } }
                .orEmpty()

            // Feed the progress bar as rows come together — this is the slow part on a big
            // provider, so it's what the percentage should actually track.
            if (reportProgress) {
                built++
                if (built % 400 == 0 || built == total) {
                    StatusBus.setProgress(built.toFloat() / total)
                }
            }

            Row(
                channel,
                now = list.firstOrNull { it.isLiveAt(now) },
                next = list.firstOrNull { it.startUtcMillis > now },
                programmes = list,
            )
        }
    }

    /** A friendly, size-aware line for the load — a small provider gets a quick word, a huge one
     * gets a "bear with me". Used at start-up so the wait always says what it's doing. */
    private fun sizeMessage(count: Int): String = when {
        count <= 0 -> "Building the guide…"
        count < 2000 -> "Loading $count channels — a small one, this'll be quick."
        count < 8000 -> "Loading $count channels — a fair few, give me a moment…"
        else -> "Loading $count channels — a big one, bear with me, I'm on it…"
    }

    /**
     * Whether the catalogue has any channels yet, as a tri-state that stops the home screen
     * spinning forever when a provider fails to load:
     *  - null  → the count query hasn't returned; still checking, keep showing the loader.
     *  - true  → channels are on disk, so an empty [rows] just means the guide is still building.
     *  - false → confirmed empty; if a provider is configured the last sync failed or returned
     *            nothing, so the screen shows a recoverable error instead of an endless spinner.
     */
    val channelsPresent: StateFlow<Boolean?> =
        graph.catalogRepository.observeChannelCount()
            .map { it > 0 }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectCategory(id: String?) {
        favouritesOnly.value = false
        selectedCategory.value = id
    }

    fun selectFavourites() {
        favouritesOnly.value = true
        selectedCategory.value = null
    }

    /** Switch the live guide's provider filter; the category resets since categories differ per source. */
    fun selectSource(sourceId: Long?) {
        selectedSource.value = sourceId
        selectedCategory.value = null
        favouritesOnly.value = false
    }

    // ---- Dedicated channel search --------------------------------------------------------------
    // Kept separate from the guide's `rows` on purpose: `rows` loads the entire 12-hour EPG
    // window, which is far too heavy to run on every keystroke (that was the ~30s search lag).
    // This path is channel-only — no programme window — debounced, and capped by the DAO's LIMIT.
    private val searchInput = MutableStateFlow("")

    fun setSearchQuery(text: String) {
        searchInput.value = text
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val searchResults: StateFlow<List<Row>> =
        searchInput
            .map { it.trim() }
            .debounce(200)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) {
                    flowOf(emptyList())
                } else {
                    // No EPG here — rows with no now/next. Finding and playing the channel is the
                    // job; guide detail would cost the slow query.
                    lineup.channels(LineupScope.Search(q)).map { channels -> channels.map { Row(it) } }
                }
            }
            // Favourites first, then the DAO's order (sortedBy is stable). Done here rather than in
            // SQL because which channels are Favourites depends on the active Profile.
            .map { rows -> rows.sortedBy { !it.favourite } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun tick() {
        nowTick.value = System.currentTimeMillis()
    }

    // ---- Channel manager (search, then hide/show or favourite) ---------------------------------
    private val managerInput = MutableStateFlow("")

    fun setManagerQuery(text: String) {
        managerInput.value = text
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val managerResults: StateFlow<List<Row>> =
        managerInput
            .map { it.trim() }
            .debounce(200)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) {
                    flowOf(emptyList())
                } else {
                    lineup.channels(LineupScope.ManageSearch(q)).map { channels -> channels.map { Row(it) } }
                }
            }
            .map { rows -> rows.sortedWith(compareBy({ it.primary.hidden }, { !it.favourite })) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Channel manager: browse by category ---------------------------------------------------
    // Deliberately independent of the guide's `selectedCategory`/`rows`: browsing here never moves
    // what Live TV is showing. Two differences from the guide feed — it can be scoped to a single
    // provider (cardiodoc's "keep sources separate"), and it includes HIDDEN channels so they can
    // be brought back. Always scoped to one category, never the whole catalogue, so the right pane
    // stays a size a remote can scroll (no flat 20k-row list).

    /** Provider filter for the manager. null = every source ("All sources"). */
    val managerSelectedSource = MutableStateFlow<Long?>(null)

    /** The [CategoryGroup.key] whose channels fill the manager's right pane; null = none picked. */
    val managerSelectedCategory = MutableStateFlow<String?>(null)

    /** Providers, so the manager can offer a source filter (shown only when there's more than one). */
    val sources: StateFlow<List<Source>> =
        graph.sourceRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The manager's category rail: like [categoryGroups] but scoped to [managerSelectedSource], and
     * NOT filtered by the adult/hidden-category setting — the manager shows every category so a
     * hidden one's channels can still be reached and un-hidden.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val managerCategoryGroups: StateFlow<List<CategoryGroup>> =
        managerSelectedSource.flatMapLatest { lineup.categoryGroups(it, includeLocked = true) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Channels in the selected manager category (and source), grouped into [Row]s exactly like the
     * guide — but including hidden channels. Empty until a category is picked. Reuses [buildRows]
     * with no EPG window (the manager needs logo/name/state, not now/next).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val managerRows: StateFlow<List<Row>> =
        combine(managerSelectedSource, managerSelectedCategory) { source, key -> source to key }
            .distinctUntilChanged()
            .flatMapLatest { (source, key) ->
                if (key == null) {
                    flowOf(emptyList())
                } else {
                    lineup.channels(LineupScope.ManageGroup(key, source)).map { channels ->
                        channels.map { Row(it) }
                    }
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Switch the manager's provider filter; the category resets since categories differ per source. */
    fun selectManagerSource(sourceId: Long?) {
        managerSelectedSource.value = sourceId
        managerSelectedCategory.value = null
    }

    fun selectManagerCategory(key: String?) {
        managerSelectedCategory.value = key
    }

    /** Hide or show a whole logical channel — every quality variant follows. */
    fun setRowHidden(row: Row, hidden: Boolean) {
        viewModelScope.launch {
            row.variants.forEach { graph.catalogRepository.setChannelHidden(it.id, hidden) }
        }
    }

    /** Stars or unstars the row's whole Channel, every Quality. */
    fun toggleFavourite(row: Row) {
        viewModelScope.launch { lineup.setFavourite(row.channel, !row.favourite) }
    }

    fun hide(row: Row) {
        viewModelScope.launch {
            row.variants.forEach { graph.catalogRepository.setChannelHidden(it.id, true) }
        }
    }

    private companion object {
        const val GUIDE_LOOKAHEAD_MILLIS = 24 * 60 * 60 * 1000L // a full day fits the grid
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        const val GUIDE_MAX_DAYS = 6 // browse up to a week out, matching typical XMLTV depth
        const val HALF_HOUR_MILLIS = 30 * 60 * 1000L
    }
}

/** Backs the Guide settings screen: feed list, toggles, custom URLs, match report. */
class EpgViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)

    data class UiState(
        val feeds: List<EpgFeed> = emptyList(),
        val syncing: Boolean = false,
        val statusLine: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { graph.epgRepository.ensureFeeds() }
        viewModelScope.launch {
            graph.epgRepository.observeFeeds().collect { feeds ->
                _ui.value = _ui.value.copy(feeds = feeds)
            }
        }
    }

    fun setEnabled(feed: EpgFeed, enabled: Boolean) {
        viewModelScope.launch {
            graph.epgRepository.setFeedEnabled(feed.id, enabled)
            if (enabled) refresh() // turning a guide on should visibly do something
        }
    }

    fun addCustom(name: String, url: String) {
        viewModelScope.launch {
            graph.epgRepository.addCustomFeed(name, url)
            refresh()
        }
    }

    fun remove(feed: EpgFeed) {
        viewModelScope.launch { graph.epgRepository.removeFeed(feed) }
    }

    fun refresh() {
        if (_ui.value.syncing) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(syncing = true, statusLine = "Downloading guides…")
            val summary = graph.epgRepository.syncAll(System.currentTimeMillis(), force = true)
            _ui.value = _ui.value.copy(
                syncing = false,
                statusLine = "Guide matched ${summary.channelsMatched} of " +
                    "${summary.channelsTotal} channels" +
                    if (summary.feedsFailed > 0) " · ${summary.feedsFailed} feed(s) failed" else "",
            )
        }
    }
}

class VodViewModel @JvmOverloads constructor(
    app: Application,
    private val graph: ServiceLocator.Graph = ServiceLocator.get(app),
) : AndroidViewModel(app) {
    private val settings = graph.settings

    /** Which episode a series card picks up at. */
    data class SeasonEpisode(val season: Int, val episode: Int)

    /**
     * One card in the Continue Watching shelf: a movie, or a series at its In Progress episode or
     * Up Next. A series card is titled with the series and carries the [episode] it plays.
     */
    data class ResumeItem(
        val key: MediaKey,
        val title: String,
        val posterUrl: String?,
        /** How far through, 0..1; null when it has never been played, so the card shows no bar. */
        val progress: Float?,
        val episode: SeasonEpisode? = null,
        /** Whether [episode] is a New Episode, which the card says on its thumbnail. */
        val newEpisode: Boolean = false,
        /** The Continue Watching card this was drawn from, for the options panel to act on. */
        val card: ContinueWatchingCard? = null,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val continueWatchingMovies: StateFlow<List<ResumeItem>> =
        graph.viewerLibrary.continueWatchingMovies()
            .mapLatest { cards -> cards.mapNotNull { resolveResume(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val continueWatchingSeries: StateFlow<List<ResumeItem>> =
        graph.viewerLibrary.continueWatchingSeries()
            .mapLatest { cards -> cards.mapNotNull { resolveResume(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Each episode's Watched / In Progress / Unwatched status for the active Profile. */
    val episodeStatuses = graph.viewerLibrary.episodeStatuses()

    private suspend fun resolveResume(card: ContinueWatchingCard) = resumeItem(graph.catalogRepository, card)

    private val movieCategory = MutableStateFlow<String?>(null)
    private val seriesCategory = MutableStateFlow<String?>(null)

    /** Provider filter for Movies/Shows. null = every source. Surfaced only when there's >1 source. */
    val selectedVodSource = MutableStateFlow<Long?>(null)

    /** Providers, so Movies/Shows can offer a source filter (shown only when there's more than one). */
    val sources: StateFlow<List<Source>> =
        graph.sourceRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Category chips scoped to the chosen provider: pick a provider and you see only its categories,
    // and since a category id belongs to one provider, the grid you then open is already that
    // provider's titles. null folds categories across every provider ("All sources").
    val movieCategories: StateFlow<List<Category>> =
        combine(
            graph.catalogRepository.observeCategories(StreamKind.MOVIE),
            selectedVodSource,
        ) { raw, sourceId -> if (sourceId == null) raw else raw.filter { it.sourceId == sourceId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val seriesCategories: StateFlow<List<Category>> =
        combine(
            graph.catalogRepository.observeCategories(StreamKind.SERIES),
            selectedVodSource,
        ) { raw, sourceId -> if (sourceId == null) raw else raw.filter { it.sourceId == sourceId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val movies: StateFlow<List<Movie>> = movieCategory
        .flatMapLatest { graph.catalogRepository.observeMovies(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val series: StateFlow<List<Series>> = seriesCategory
        .flatMapLatest { graph.catalogRepository.observeSeries(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectMovieCategory(id: String?) {
        movieCategory.value = id
    }

    fun selectSeriesCategory(id: String?) {
        seriesCategory.value = id
    }

    /** True when at least one Stremio add-on is configured — gates the "add-on sources" button. */
    val hasAddons: StateFlow<Boolean> =
        settings.stremioAddons
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settings.stremioAddons.value.isNotEmpty())

    /**
     * The streams the configured add-ons offer for [movie], resolved through the user's TMDB key for
     * the IMDb id. Empty when there's no key, no add-ons, no IMDb match, or nothing came back. Each
     * add-on is queried independently so one failing doesn't sink the rest.
     */
    suspend fun addonStreams(movie: Movie): List<StremioStream> {
        val addons = settings.stremioAddons.value
        if (addons.isEmpty()) return emptyList()
        val imdb = graph.catalogRepository.imdbIdFor(movie) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            addons.flatMap { addon ->
                runCatching { graph.stremioClient.streams(addon.manifestUrl, addon.name, "movie", imdb) }
                    .getOrDefault(emptyList())
            }
        }
    }

    /** Switch the Movies/Shows provider filter; category selections reset since they differ per source. */
    fun selectVodSource(sourceId: Long?) {
        selectedVodSource.value = sourceId
        movieCategory.value = null
        seriesCategory.value = null
    }

    // ---- Netflix-style home rows ----------------------------------------------------------------
    // "Recently added" is reactive: it fills in live as a VOD sync lands. The computed feeds
    // (recommended, by-genre) are held in the StateFlows below and (re)built by [loadHomeFeeds] from
    // one shared scan of the library — guarded so opening the tab twice, or recomposing, does not
    // re-scan a 20k-title catalogue; it recomputes only on a profile change or catalogue growth.

    val recentlyAddedMovies: StateFlow<List<Movie>> =
        graph.catalogRepository.recentlyAddedMovies()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentlyAddedSeries: StateFlow<List<Series>> =
        graph.catalogRepository.recentlyAddedSeries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _recommendedMovies = MutableStateFlow<List<Movie>>(emptyList())
    val recommendedMovies: StateFlow<List<Movie>> = _recommendedMovies.asStateFlow()

    private val _movieGenreRows = MutableStateFlow<List<GenreGroup<Movie>>>(emptyList())
    val movieGenreRows: StateFlow<List<GenreGroup<Movie>>> = _movieGenreRows.asStateFlow()

    private val _seriesGenreRows = MutableStateFlow<List<GenreGroup<Series>>>(emptyList())
    val seriesGenreRows: StateFlow<List<GenreGroup<Series>>> = _seriesGenreRows.asStateFlow()

    /** The (profile, catalogue) the computed rows were last built for — the redundant-reload guard. */
    private data class HomeFeedsKey(val profileId: Long, val movieCount: Int, val seriesCount: Int)

    @Volatile private var loadedHomeFeeds: HomeFeedsKey? = null

    /** Serialises [loadHomeFeeds] so two near-simultaneous calls (screen open + the profile emit in
     *  `init`) can't both start the heavy scan — the second waits, sees the guard satisfied, returns.
     *  MUST be declared before the `init` block below: `activeProfileId` is a StateFlow, so its
     *  `collect` fires synchronously during construction, and Kotlin initialises properties top to
     *  bottom — declared after `init`, this mutex would still be null when the first collect runs. */
    private val homeFeedsMutex = Mutex()

    init {
        // The home feeds are per profile (Recommended) and per catalogue (the genre rows). Rebuild
        // them when the active profile changes — and once at start. Routed through the guarded
        // [loadHomeFeeds] so a profile switch triggers exactly one library scan, and re-opening the
        // tab with the same profile and an unchanged catalogue triggers none. Reads an empty result
        // until a VOD sync has populated the catalogue; [ensureVodLoaded] re-runs it once one has.
        viewModelScope.launch {
            settings.activeProfileId.collect { loadHomeFeeds() }
        }
    }

    /**
     * Recomputes the computed home rows (Recommended + by-genre) from a SINGLE scan of the library.
     *
     * The reactive rows (recently-added, continue-watching) keep themselves current, so this only
     * covers the computed feeds. On a 20k-title library the old version was the lag: it read the
     * whole movie table three times over (recommended + movie genres, plus a profile-change reload)
     * and again on every tab re-open. Now [allMovies]/[allSeries] are read once and reused for the
     * recommended row and every genre row, off the main thread, and a `(profile, movieCount,
     * seriesCount)` guard skips the work entirely unless the profile changed or the catalogue grew —
     * so re-opening Movies/Shows is free and a post-sync refresh still fills the rows in.
     */
    fun loadHomeFeeds() {
        viewModelScope.launch {
            homeFeedsMutex.withLock {
                val profileId = settings.activeProfileId.value
                val movieCount = runCatching { graph.catalogRepository.movieCount() }.getOrDefault(0)
                val seriesCount = runCatching { graph.catalogRepository.seriesCount() }.getOrDefault(0)

                // Already built for this profile and this exact catalogue? Then a tab re-open or a
                // recomposition must not trigger another full-library scan. Only a profile change or
                // a catalogue whose size moved gets past here.
                val prev = loadedHomeFeeds
                if (prev != null &&
                    prev.profileId == profileId &&
                    prev.movieCount == movieCount &&
                    prev.seriesCount == seriesCount
                ) {
                    return@withLock
                }

                // One scan of each library (repo switches to IO), reused across every computed row.
                val allMovies = runCatching { graph.catalogRepository.allMovies() }.getOrDefault(emptyList())
                val allSeries = runCatching { graph.catalogRepository.allSeries() }.getOrDefault(emptyList())

                _recommendedMovies.value = runCatching {
                    graph.catalogRepository.recommendedMoviesFrom(allMovies, profileId)
                }.getOrDefault(emptyList())
                _movieGenreRows.value = runCatching {
                    graph.catalogRepository.moviesByGenreFrom(allMovies)
                }.getOrDefault(emptyList())
                _seriesGenreRows.value = runCatching {
                    graph.catalogRepository.seriesByGenreFrom(allSeries)
                }.getOrDefault(emptyList())

                loadedHomeFeeds = HomeFeedsKey(profileId, movieCount, seriesCount)
            }
        }
    }

    // Movies + series load on demand — the first time the user opens Movies or Shows — rather than
    // up front at login. A provider's 40,000-title VOD list is exactly what makes a first sync
    // crawl, and most sessions only ever watch live TV. Loaded once per app run.
    private val _vodLoading = MutableStateFlow(false)
    val vodLoading: StateFlow<Boolean> = _vodLoading.asStateFlow()

    @Volatile private var vodRequested = false

    /** How long a fetched VOD catalogue is trusted before a warm launch re-syncs it. */
    private val vodTtlMillis = 12L * 60 * 60 * 1000 // 12 hours

    fun ensureVodLoaded() {
        if (vodRequested) return
        vodRequested = true
        viewModelScope.launch { syncVodIfStale(force = false) }
    }

    /**
     * Force a fresh download of the movies/series catalogue, ignoring the freshness cache. Wired
     * to the pull-to-refresh / refresh action so the user always has a way to pull new titles in
     * before the TTL lapses.
     */
    fun refreshVod() {
        vodRequested = true
        viewModelScope.launch { syncVodIfStale(force = true) }
    }

    private suspend fun syncVodIfStale(force: Boolean) {
        val now = System.currentTimeMillis()

        // Warm-launch fast path. The catalogue is persisted in Room, so once it has been fetched
        // there is no reason to re-download and re-upsert a 40k-title list on every launch — that
        // was both the multi-minute "Loading movies & shows…" and the bandwidth hog that starved
        // the live preview into buffering. When we synced recently and already have rows, skip the
        // network entirely and just (re)build the home shelves off what is stored.
        if (!force) {
            val haveCatalogue = runCatching {
                graph.catalogRepository.movieCount() + graph.catalogRepository.seriesCount()
            }.getOrDefault(0) > 0
            val last = settings.vodSyncedAtMillis
            val fresh = last > 0 && now - last < vodTtlMillis
            if (haveCatalogue && fresh) {
                loadHomeFeeds()
                return
            }
        }

        _vodLoading.value = true
        val synced = StatusBus.during("Loading movies & shows…") {
            runCatching {
                for (source in graph.sourceRepository.enabled()) {
                    graph.catalogRepository.syncVod(source, now)
                }
            }.isSuccess
        }
        _vodLoading.value = false
        // Stamp the cache only when the fetch actually succeeded, so a failed sync retries on the
        // next open instead of being remembered as "fresh" and leaving the user with no catalogue.
        if (synced) settings.vodSyncedAtMillis = now
        // The catalogue may have grown — recompute the computed home rows off the fresh data.
        loadHomeFeeds()
    }

    // ---- VOD search (shared by the unified search screen) --------------------------------------
    private val vodSearchInput = MutableStateFlow("")

    fun setVodSearchQuery(text: String) {
        vodSearchInput.value = text
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val movieResults: StateFlow<List<Movie>> =
        vodSearchInput.map { it.trim() }.debounce(200).distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList()) else graph.catalogRepository.searchMovies(q)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val seriesResults: StateFlow<List<Series>> =
        vodSearchInput.map { it.trim() }.debounce(200).distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList()) else graph.catalogRepository.searchSeries(q)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The active Profile's Favourite movies and series: the Favourites rows and the detail pages'
     * stars. Null until first loaded, so a star never shows "not a Favourite" for a frame and acts on it.
     */
    val favouriteMovies: StateFlow<List<Movie>?> =
        graph.viewerLibrary.favourites.movies()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val favouriteSeries: StateFlow<List<Series>?> =
        graph.viewerLibrary.favourites.series()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setMovieFavourite(movie: Movie, favourite: Boolean) {
        viewModelScope.launch { graph.viewerLibrary.favourites.set(movie, favourite) }
    }

    /**
     * Episodes are fetched on demand rather than during catalogue sync.
     *
     * Pulling every episode of every series up front is what makes a first sync take twenty
     * minutes on a large provider, and most of it is never looked at.
     */
    fun loadEpisodes(series: Series) {
        viewModelScope.launch {
            val source = graph.sourceRepository.byId(series.sourceId) ?: return@launch
            graph.catalogRepository.ensureEpisodes(source, series.seriesId)
        }
    }

    /** The series page's main button over that series' [episodes]: Resume, Up Next or the first episode. */
    fun seriesMainAction(episodes: List<Episode>) = graph.viewerLibrary.seriesMainAction(episodes)

    fun episodes(series: Series) = graph.catalogRepository.observeEpisodes(series.sourceId, series.seriesId)

    /** For the series detail screen: look the series up by row id. */
    fun seriesById(id: Long): kotlinx.coroutines.flow.Flow<Series?> =
        kotlinx.coroutines.flow.flow { emit(graph.catalogRepository.series(id)) }

    /** An always-empty episode flow, so the detail screen has something before a series loads. */
    val noEpisodes: kotlinx.coroutines.flow.Flow<List<app.opentv.data.model.Episode>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun movieById(id: Long): Movie? = graph.catalogRepository.movie(id)

    // ---- Detail screens -------------------------------------------------------------------------
    // Thin wrappers over the repository's detail feeds. The screens drive them from `produceState`
    // keyed on the id, so each detail instance owns its own state and a "More like this" hop to
    // another title loads cleanly without two screens fighting over one shared StateFlow.

    /** Loads a movie, lazily enriching backdrop/cast/genre on first open. See CatalogRepository.movieDetail. */
    suspend fun movieDetail(id: Long): Movie? = graph.catalogRepository.movieDetail(id)

    /** Loads a series, lazily enriching backdrop/cast/genre on first open. See CatalogRepository.seriesDetail. */
    suspend fun seriesDetail(id: Long): Series? = graph.catalogRepository.seriesDetail(id)

    /** "More like this" for the movie detail screen. */
    suspend fun moreLikeThis(movie: Movie): List<Movie> = graph.catalogRepository.moreLikeThis(movie)

    /** "More like this" for the series detail screen. */
    suspend fun moreLikeThisSeries(series: Series): List<Series> = graph.catalogRepository.moreLikeThisSeries(series)

    /** Every library title (movies + series) featuring a person — the Person screen's grid. */
    suspend fun titlesWithPerson(name: String): List<PersonTitle> = graph.catalogRepository.titlesWithPerson(name)

    /** Where the active Profile would resume a movie/episode, 0 for the start — drives Resume vs Watch now. */
    suspend fun resumeFrom(mediaKey: String): Long = graph.viewerLibrary.resumeFrom(mediaKey)

    /** Collapses quality variants of a movie list for a browse grid. See CatalogRepository.collapseVariants. */
    fun collapseVariants(movies: List<Movie>): List<MovieVariantGroup> =
        graph.catalogRepository.collapseVariants(movies)

    fun setSeriesFavourite(series: Series, favourite: Boolean) {
        viewModelScope.launch { graph.viewerLibrary.favourites.set(series, favourite) }
    }
}

/** [card] as Continue Watching draws it, or null when its title has left the catalogue. */
private suspend fun resumeItem(catalog: CatalogRepository, card: ContinueWatchingCard): VodViewModel.ResumeItem? =
    when (card) {
        is ContinueWatchingCard.Movie -> catalog.movie(card.movieId)?.let {
            VodViewModel.ResumeItem(
                MediaKey.Movie(it.id),
                it.displayTitle,
                it.posterUrl,
                card.progress,
                card = card,
            )
        }
        is ContinueWatchingCard.Series -> {
            val ep = card.episode
            catalog.seriesOf(ep)?.let {
                VodViewModel.ResumeItem(
                    key = MediaKey.Episode(ep.id),
                    title = it.name,
                    // The card is landscape: the wide backdrop fits it, a portrait poster only crops.
                    posterUrl = it.backdropUrl ?: it.posterUrl,
                    progress = card.progress,
                    episode = VodViewModel.SeasonEpisode(ep.season, ep.episodeNumber),
                    newEpisode = card.newEpisode,
                    card = card,
                )
            }
        }
    }

/** Home's Live Preview and the titles on its channel now and next, when the guide has them. */
data class LiveNow(val preview: LivePreview, val nowTitle: String?, val nextTitle: String?)

/** What Home shows: the active Profile's Continue Watching and its Favourites, by kind, then [recent]. */
data class HomeState(
    val continueWatching: List<VodViewModel.ResumeItem>,
    val favouriteShows: List<Series>,
    val favouriteMovies: List<Movie>,
    val favouriteChannels: List<LineupChannel>,
    val recent: RecentRows,
    val live: LiveNow? = null,
)

/**
 * Home's discovery rows, the same for every Profile because the catalogue is device-wide.
 * Released goes by release date (shows: their newest episode); added by when the provider added or
 * last updated the title. A title can be in more than one.
 */
data class RecentRows(
    val releasedMovies: List<Movie> = emptyList(),
    val addedMovies: List<Movie> = emptyList(),
    val releasedShows: List<Series> = emptyList(),
    val addedShows: List<Series> = emptyList(),
) {
    fun isEmpty(): Boolean =
        releasedMovies.isEmpty() && addedMovies.isEmpty() && releasedShows.isEmpty() && addedShows.isEmpty()
}

/**
 * Home: the active Profile's Continue Watching across movies and series, its Favourites, and the
 * catalogue's recently released and added titles ([RecentRows]). The
 * state is null until all of it has loaded, so Home never flashes its nothing-here hint at a
 * Profile that has rows. A content type switched off in settings is left off Home too, as its tab
 * is from the side menu.
 */
class HomeViewModel @JvmOverloads constructor(
    app: Application,
    private val graph: ServiceLocator.Graph = ServiceLocator.get(app),
) : AndroidViewModel(app) {
    private val settings = graph.settings
    private val lineup = graph.channelLineup

    @OptIn(ExperimentalCoroutinesApi::class)
    private val continueWatching: Flow<List<VodViewModel.ResumeItem>> = combine(
        graph.viewerLibrary.continueWatching()
            .mapLatest { cards -> cards.mapNotNull { resumeItem(graph.catalogRepository, it) } },
        settings.moviesEnabled,
        settings.seriesEnabled,
    ) { items, movies, series -> items.filter { if (it.episode == null) movies else series } }

    /** The time now, again every hour, so a title comes out on Home without Home being reopened. */
    private val clock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(CLOCK_TICK_MILLIS)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val recent: Flow<RecentRows> = clock.flatMapLatest { now ->
        combine(
            graph.catalogRepository.recentlyReleasedMovies(now).onlyWhen(settings.moviesEnabled),
            graph.catalogRepository.recentlyAddedMovies().onlyWhen(settings.moviesEnabled),
            graph.catalogRepository.recentlyReleasedSeries(now).onlyWhen(settings.seriesEnabled),
            graph.catalogRepository.recentlyAddedSeries().onlyWhen(settings.seriesEnabled),
            ::RecentRows,
        )
    }

    /** The time now, again every minute, so what's on the Live Preview's channel moves on. */
    private val minute: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(MINUTE_MILLIS)
        }
    }

    // A channel in a locked category would otherwise play on Home, unasked, for whoever is in the
    // room next: the guide hides it until the session is unlocked, and so does Home. One that has
    // left the channel list still shows, as a reminder that can't play.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<LiveNow?> = combine(
        graph.viewerLibrary.live.preview(),
        settings.livePreviewOnHome,
        settings.liveEnabled,
    ) { preview, onHome, liveOn -> preview?.takeIf { onHome && liveOn } }
        .distinctUntilChanged()
        .flatMapLatest { preview ->
            val channelId = preview?.channel?.id
            when {
                preview == null -> flowOf(null)
                channelId == null -> flowOf(preview to null)
                else -> lineup.channel(channelId).map { channel -> channel?.let { preview to it } }
            }
        }
        .distinctUntilChanged()
        .flatMapLatest { shown ->
            if (shown == null) {
                flowOf(null)
            } else {
                minute.map { now -> liveNow(shown.first, shown.second, now) }.distinctUntilChanged()
            }
        }

    private suspend fun liveNow(preview: LivePreview, channel: LineupChannel?, now: Long): LiveNow {
        // The Channel's guide, as the guide itself picks it.
        val guideId = channel?.let { lineup.guideId(it) } ?: return LiveNow(preview, null, null)
        val shows = graph.epgRepository.upcoming(guideId, now, limit = 2)
        val onNow = shows.firstOrNull()?.takeIf { it.startUtcMillis <= now }
        val next = if (onNow != null) shows.getOrNull(1) else shows.firstOrNull()
        return LiveNow(preview, onNow?.title, next?.title)
    }

    val state: StateFlow<HomeState?> = combine(
        combine(
            continueWatching,
            graph.viewerLibrary.favourites.series().onlyWhen(settings.seriesEnabled),
            graph.viewerLibrary.favourites.movies().onlyWhen(settings.moviesEnabled),
            lineup.channels(LineupScope.Favourites()).onlyWhen(settings.liveEnabled),
            recent,
            ::HomeState,
        ),
        // In the same state as the rows, so Home is first drawn with it and highlights it.
        live,
    ) { rows, live -> rows.copy(live = live) }
        // Nothing replayed once the rows have stopped updating: back from the player, Home waits for
        // fresh rows rather than drawing the old ones, where it would highlight a card that's about
        // to move or go (a title just finished, a Favourite just removed).
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)

    /** Channel up and down go through every Channel, as Home has no list of its own. */
    fun zapFromAll() = lineup.zapFrom(LineupScope.All())

    private fun <T> Flow<List<T>>.onlyWhen(enabled: StateFlow<Boolean>): Flow<List<T>> =
        combine(this, enabled) { list, on -> if (on) list else emptyList() }

    private companion object {
        // Release dates are whole days, so an hour late is soon enough.
        const val CLOCK_TICK_MILLIS = 60L * 60 * 1000
        const val MINUTE_MILLIS = 60L * 1000
    }
}

/**
 * Local viewing profiles. No accounts — just names on this device — with the active one stored in
 * [app.opentv.core.AppSettings] so the whole app agrees on whose watch history is in play.
 */
class ProfilesViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val settings = graph.settings

    val profiles: StateFlow<List<Profile>> =
        graph.profiles.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The same list, but null until it has been read once, so launch can tell "not loaded" from "none". */
    val loadedProfiles: StateFlow<List<Profile>?> =
        graph.profiles.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeProfileId: StateFlow<Long> = settings.activeProfileId

    /** Adds a Profile and switches to it. A taken name is refused; the name entry says so first. */
    fun addProfile(name: String) {
        viewModelScope.launch { graph.profiles.create(name)?.let(settings::setActiveProfile) }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch { graph.profiles.rename(id, name) }
    }

    fun select(id: Long) {
        settings.setActiveProfile(id)
    }

    fun remove(id: Long) {
        viewModelScope.launch { graph.profiles.remove(id) }
    }
}

/**
 * Local, device-to-device watch-history sync — no servers of ours.
 *
 * One device shares (a short-lived LAN server behind a six-digit code); the other receives by
 * typing that device's address and code. Only resume positions travel, keyed by stream URL and
 * profile name so they land on the right film and the right person on the other device. Merge is
 * newest-wins per item, so syncing both ways leaves the two devices agreeing.
 */
class SyncViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val server = SyncServer(viewModelScope)
    private val client = SyncClient(graph.httpClient)
    private val engine = SyncEngine(graph)

    val serverState: StateFlow<SyncServer.State> = server.state

    sealed interface ReceiveState {
        data object Idle : ReceiveState
        data object Connecting : ReceiveState
        data class Done(val merged: Int) : ReceiveState
        data class Failed(val message: String) : ReceiveState
    }

    private val _receiveState = MutableStateFlow<ReceiveState>(ReceiveState.Idle)
    val receiveState: StateFlow<ReceiveState> = _receiveState.asStateFlow()

    // ---- NAS ("cloud") sync ------------------------------------------------------------------

    private val nas = NasSync(graph)

    /** Whether an SMB/NAS is set up at all — the NAS pane needs one before it can do anything. */
    val smbHost: StateFlow<String> = graph.settings.smbHost
    val nasAutoSync: StateFlow<Boolean> = graph.settings.nasAutoSync

    sealed interface NasState {
        data object Idle : NasState
        data object Syncing : NasState
        data class Done(val message: String) : NasState
        data class Failed(val message: String) : NasState
    }

    private val _nasState = MutableStateFlow<NasState>(NasState.Idle)
    val nasState: StateFlow<NasState> = _nasState.asStateFlow()

    fun setNasAutoSync(enabled: Boolean) = graph.settings.setNasAutoSync(enabled)

    fun syncNas() {
        if (_nasState.value is NasState.Syncing) return
        viewModelScope.launch {
            _nasState.value = NasState.Syncing
            val ctx = getApplication<Application>()
            _nasState.value = when (val result = nas.sync()) {
                is NasSync.Result.Success ->
                    if (result.peers == 0) {
                        NasState.Done(ctx.getString(R.string.sync_nas_uploaded))
                    } else {
                        NasState.Done(ctx.getString(R.string.sync_nas_done, result.merged, result.peers))
                    }
                is NasSync.Result.NotConfigured ->
                    NasState.Failed(ctx.getString(R.string.sync_nas_needs_setup))
                is NasSync.Result.Failed ->
                    NasState.Failed(ctx.getString(R.string.sync_nas_failed))
            }
        }
    }

    fun startSharing() {
        viewModelScope.launch { server.start(engine.encode(engine.gather())) }
    }

    fun stopSharing() = server.stop()

    fun receive(address: String, code: String) {
        viewModelScope.launch {
            _receiveState.value = ReceiveState.Connecting
            client.pull(address, code)
                .onSuccess { _receiveState.value = ReceiveState.Done(engine.apply(it).total) }
                .onFailure { _receiveState.value = ReceiveState.Failed(it.message ?: "Sync failed.") }
        }
    }

    fun resetReceive() {
        _receiveState.value = ReceiveState.Idle
    }

    override fun onCleared() {
        server.stop()
    }
}

/**
 * Owns the "manage channels from your phone or laptop" web server for the manage screen.
 *
 * Same ownership shape as [SyncViewModel]: the server is bound to [viewModelScope] (so its idle
 * watchdog is torn down with the screen) and stopped in [onCleared]. The screen also starts it on
 * open and stops it on leave, mirroring the phone-pairing lifecycle — so no socket is ever left
 * listening on the user's network once they navigate away.
 */
class WebManagerViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val server =
        ManagerServer(
            viewModelScope,
            graph.catalogRepository,
            graph.settings,
            graph.sourceRepository,
            graph.channelLineup,
        )

    val state: StateFlow<ManagerServer.State> = server.state

    fun start() = server.start()

    fun stop() = server.stop()

    override fun onCleared() {
        server.stop()
    }
}
