/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.app.Application
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
import app.opentv.data.model.SeriesRef
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.model.StremioStream
import app.opentv.data.model.ref
import app.opentv.data.parser.displayTitle
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.data.repo.CategoryGroup
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.CoverStatuses
import app.opentv.data.repo.GenreGroup
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.LineupScope
import app.opentv.data.repo.LivePreview
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.RefreshMessages
import app.opentv.data.repo.TitleCover
import app.opentv.data.repo.VodTitle
import app.opentv.data.repo.categoryGroupKey
import app.opentv.data.repo.coverStatuses
import app.opentv.data.repo.movieCovers
import app.opentv.data.repo.movies
import app.opentv.data.repo.nowOn
import app.opentv.data.repo.series
import app.opentv.data.repo.seriesCovers
import app.opentv.data.repo.splitGenres
import app.opentv.data.work.SyncWorker
import app.opentv.pairing.ManagerServer
import app.opentv.player.playsQualityRanked
import app.opentv.sync.NasSync
import app.opentv.sync.SyncClient
import app.opentv.sync.SyncEngine
import app.opentv.sync.SyncServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
        val adding: Boolean = false,
        val refreshing: Boolean = false,
        val syncMessage: String? = null,
    ) {
        val syncing: Boolean get() = adding || refreshing
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val refresh = graph.catalogueRefresh
    private val messages = graph.refreshMessages

    init {
        viewModelScope.launch {
            graph.sourceRepository.observeAll().collect { sources ->
                _ui.update { it.copy(sources = sources, loaded = true) }
            }
        }
        // A refresh runs as background work, so this is how its progress and result reach the screen.
        // What was already there when this screen model appeared isn't news.
        viewModelScope.launch {
            var shown = refresh.state.value.last
            var wasRefreshing = false
            var announced: String? = null
            try {
                refresh.state.collect { state ->
                    val news = state.last.takeIf { it !== shown || (wasRefreshing && !state.refreshing) }
                    shown = state.last
                    wasRefreshing = state.refreshing
                    // On changes only: the channel list's own progress shares this status bar.
                    val status = when {
                        state.loadingVod -> messages.of(RefreshMessages.Step.LoadingVod)
                        state.buildingGuide -> messages.of(RefreshMessages.Step.BuildingGuide)
                        else -> null
                    }
                    if (status != announced) StatusBus.set(status)
                    announced = status
                    _ui.update {
                        it.copy(
                            refreshing = state.refreshing,
                            syncMessage = when {
                                state.refreshing -> messages.of(RefreshMessages.Step.Refreshing)
                                news != null -> messages.of(news)
                                else -> it.syncMessage
                            },
                        )
                    }
                }
            } finally {
                // The bar outlives this screen model, and the next one can't know this one set it.
                if (announced != null) StatusBus.set(null)
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

    /** Saves, then immediately pulls the channels so the viewer sees them, not a spinner. */
    fun saveAndSync(draft: Source, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _ui.update { it.copy(adding = true, syncMessage = messages.of(RefreshMessages.Step.SavingSource)) }
            val id = graph.sourceRepository.save(draft)
            val saved = graph.sourceRepository.byId(id)
            if (saved == null) {
                _ui.update { it.copy(adding = false, syncMessage = messages.of(RefreshMessages.Step.SaveFailed)) }
                onDone(false)
                return@launch
            }

            _ui.update { it.copy(syncMessage = messages.of(RefreshMessages.Step.LoadingChannels)) }
            val outcome = refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(saved))
            _ui.update { it.copy(adding = false, syncMessage = messages.of(outcome)) }
            onDone(outcome is CatalogueRefresh.Outcome.ChannelsLoaded)
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

    /** Runs as background work, like Settings' Refresh now, so leaving Home doesn't stop it. */
    fun refreshAll() = SyncWorker.refreshNow(getApplication())

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

    private val nowTick = MutableStateFlow(System.currentTimeMillis())

    /**
     * Every provider's categories, folded into one entry per logical category, locked ones
     * included: the Parental screen lists them all so they can be locked and unlocked.
     */
    val categoryGroups: StateFlow<List<CategoryGroup>> = lineup.categoryGroups(includeLocked = true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Live TV's Categories Sidebar: every provider's categories in one list, with no provider
     * switch. Locked ones drop out until the session is unlocked.
     */
    val visibleCategoryGroups: StateFlow<List<CategoryGroup>> = lineup.categoryGroups()
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

    /**
     * The day the guide shows, as start and end. Today starts at the current half-hour so live is
     * always on screen; a later day starts at its local midnight, so its whole schedule is there.
     * Each day ends where the next starts, so paging Right past today's last programme carries on
     * from midnight rather than going back over the morning. One flow, so the two never disagree.
     */
    private val guideWindow: StateFlow<Pair<Long, Long>> =
        combine(nowTick.map { it - it % HALF_HOUR_MILLIS }, _guideDayOffset, ::guideWindowOf)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                guideWindowOf(System.currentTimeMillis().let { it - it % HALF_HOUR_MILLIS }, 0),
            )

    private fun guideWindowOf(halfHour: Long, day: Int): Pair<Long, Long> {
        val midnight = startOfLocalDay(halfHour)
        val start = if (day <= 0) halfHour else midnight + day * DAY_MILLIS
        return start to midnight + (day.coerceAtLeast(0) + 1) * DAY_MILLIS
    }

    /** The guide's left edge, where programme cells are measured from. */
    val windowStartMillis: StateFlow<Long> = guideWindow.map { it.first }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), guideWindow.value.first)

    /** Where the guide's day ends: today's at midnight, where tomorrow's starts. */
    val windowEndMillis: StateFlow<Long> = guideWindow.map { it.second }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), guideWindow.value.second)

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
        combine(selectedCategory, favouritesOnly, ::guideScopeOf).distinctUntilChanged()

    private fun guideScopeOf(category: String?, favs: Boolean): LineupScope = when {
        favs -> LineupScope.Favourites()
        category != null -> LineupScope.Group(category)
        else -> LineupScope.All()
    }

    /** From Search, channel up and down go through every Channel rather than the results. */
    fun zapFromAll() = lineup.zapFrom(LineupScope.All())

    /** Channel up and down follow the guide's current scope once you play from it. */
    fun zapFromGuide() {
        // Set before the player opens, not in a coroutine that might land after it reads the scope.
        lineup.zapFrom(guideScopeOf(selectedCategory.value, favouritesOnly.value))
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
        // guideWindow is a StateFlow, which already conflates equal values — an explicit
        // distinctUntilChanged() on it is a no-op (and a build error under our warnings-as-errors).
        guideWindow
            .flatMapLatest { (windowStart, windowEnd) ->
                graph.epgRepository
                    .observeWindow(windowStart, windowEnd)
                    .map { programmes -> programmes.groupBy { it.epgChannelId } }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Guards the start-up loading bar so it shows once, on the first build, not on every tap. */
    @Volatile
    private var guideBuilt = false

    private val _rowsBuilt = MutableStateFlow(false)

    /**
     * Whether [guideRows] has been built at least once. Until then an empty list means still loading;
     * after it, that the Favourites or category on show has nothing in it (every channel hidden).
     */
    val rowsBuilt: StateFlow<Boolean> = _rowsBuilt.asStateFlow()

    /**
     * The guide's rows with the scope (All channels, Favourites, a category) they were built for,
     * in one value, so the guide can tell a new category's rows from the old one's and start it
     * at the top: a list kept by key would otherwise open it wherever the old channel was.
     */
    @androidx.compose.runtime.Immutable
    data class GuideRows(val scope: LineupScope? = null, val rows: List<Row> = emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val guideRows: StateFlow<GuideRows> =
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
                    GuideRows(scope, buildRows(channels, byEpgChannel, now, reportProgress = firstBuild))
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
                _rowsBuilt.value = true
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuideRows())

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
     *  - true  → channels are on disk, so an empty [guideRows] just means the guide is still building.
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
            .debounceSearch()
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
    // what Live TV is showing. It includes HIDDEN channels so they can be brought back, and is
    // always scoped to one category, never the whole catalogue, so the list stays a size a remote
    // can scroll (no flat 20k-row list).

    /** The [CategoryGroup.key] whose channels fill the manager's list; null = none picked. */
    val managerSelectedCategory = MutableStateFlow<String?>(null)

    /** Providers, for naming who records a programme in its details. */
    val sources: StateFlow<List<Source>> =
        graph.sourceRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The manager's Categories Sidebar: every provider's categories, Parental-Locked ones too. The
     * lock hides them from browsing, not from the screens that manage channels (GLOSSARY).
     */
    val managerCategoryGroups: StateFlow<List<CategoryGroup>> get() = categoryGroups

    /**
     * Channels in the selected manager category, grouped into [Row]s exactly like the guide, but
     * including hidden channels. Empty until a category is picked. No guide window: the manager
     * needs logo, name and state, not now and next.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val managerRows: StateFlow<List<Row>> =
        managerSelectedCategory
            .flatMapLatest { key ->
                if (key == null) {
                    flowOf(emptyList())
                } else {
                    lineup.channels(LineupScope.ManageGroup(key)).map { channels -> channels.map { Row(it) } }
                }
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    companion object {
        /** How many days ahead the guide goes: up to a week out, matching typical XMLTV depth. */
        const val GUIDE_MAX_DAYS = 6
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val HALF_HOUR_MILLIS = 30 * 60 * 1000L
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
            _ui.value = _ui.value.copy(
                syncing = true,
                statusLine = graph.refreshMessages.of(RefreshMessages.Step.DownloadingGuides),
            )
            val outcome = graph.catalogueRefresh.refresh(CatalogueRefresh.Trigger.GuideSettings)
            _ui.value = _ui.value.copy(syncing = false, statusLine = graph.refreshMessages.of(outcome))
        }
    }
}

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
    /** The provider's name for the title, which is where its Quality is written. */
    val name: String = title,
    /** The film's or the show's local id, which Movies' and Shows' backdrop looks it up by. */
    val titleId: Long? = null,
)

/**
 * What Movies' and Shows' backdrop shows for the highlighted title: its name, where the Profile is
 * with it ([status]), a line of facts about it, its synopsis and its wide art, if it has any.
 */
@androidx.compose.runtime.Immutable
data class Backdrop(
    val title: String,
    val imageUrl: String?,
    val status: CoverStatus?,
    val synopsis: String?,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    /** Films only: shows have no single running time. */
    val runtimeSeconds: Int? = null,
)

/** What Movies and Shows know a category by: its id is only unique within its provider. */
val Category.browseKey: String get() = "$sourceId:$id"

class VodViewModel @JvmOverloads constructor(
    app: Application,
    private val graph: ServiceLocator.Graph = ServiceLocator.get(app),
) : AndroidViewModel(app) {
    private val settings = graph.settings

    init {
        // Asked here, off the main thread: the grid collapses its movies while composing, and listing a
        // cheap box's decoders the first time is slow enough to drop frames.
        viewModelScope.launch(Dispatchers.Default) { graph.decoders.tallestListed }
    }

    // null until loaded, so Movies and Shows can wait for the rows at the top before drawing any:
    // arriving after the Highlight, they pushed the row it was on down off the screen.
    @OptIn(ExperimentalCoroutinesApi::class)
    val continueWatchingMovies: StateFlow<List<ResumeItem>?> =
        graph.viewerLibrary.continueWatchingMovies()
            .mapLatest { cards -> cards.mapNotNull { resumeItem(graph.catalogRepository, it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val continueWatchingSeries: StateFlow<List<ResumeItem>?> =
        graph.viewerLibrary.continueWatchingSeries()
            .mapLatest { cards -> cards.mapNotNull { resumeItem(graph.catalogRepository, it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Each episode's Watched / In Progress / Unwatched status for the active Profile. */
    val episodeStatuses = graph.viewerLibrary.episodeStatuses()

    // Each a category's browseKey: an id alone is only unique within its provider.
    private val movieCategory = MutableStateFlow<String?>(null)
    private val seriesCategory = MutableStateFlow<String?>(null)

    /** Providers, so Movies/Shows can offer a source filter (shown only when there's more than one). */
    val sources: StateFlow<List<Source>> =
        graph.sourceRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Every provider's categories, less the Parental Lock's until the viewer unlocks them for the
    // session.
    val movieCategories: StateFlow<List<Category>> = viewableCategories(StreamKind.MOVIE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(KEEP_CATEGORIES_MILLIS), emptyList())

    val seriesCategories: StateFlow<List<Category>> = viewableCategories(StreamKind.SERIES)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(KEEP_CATEGORIES_MILLIS), emptyList())

    private fun viewableCategories(kind: StreamKind): Flow<List<Category>> = combine(
        graph.catalogRepository.observeCategories(kind),
        settings.hiddenCategories,
        settings.hiddenUnlocked,
    ) { raw, locked, unlocked -> if (unlocked) raw else raw.filter { categoryGroupKey(it) !in locked } }

    private companion object {
        /** As long as the tab's other flows: through a quick trip to a title's page and back. */
        const val KEEP_CATEGORIES_MILLIS = 5_000L

        /** Two genres keep the backdrop's line of facts to one line. */
        const val BACKDROP_GENRES = 2
    }

    suspend fun movie(id: Long): Movie? = graph.catalogRepository.movie(id)

    suspend fun series(id: Long): Series? = graph.catalogRepository.series(id)

    /**
     * What Movies' backdrop shows for movie [id]: at once what's stored, then again with the wide art
     * and synopsis the provider (or TMDB, with the viewer's key) has, when the stored row lacks them.
     * Only wide art, never the cover: a portrait poster cropped across the top of the screen looked
     * wrong. The statuses are Home's covers', so a title says the same thing on both.
     */
    fun movieBackdrop(id: Long): Flow<Backdrop?> = flow {
        val movie = movie(id) ?: return@flow emit(null)
        val status = graph.viewerLibrary.coverStatuses().first().ofMovie(movie.id)
        emit(movie.backdrop(status))
        if (movie.backdropUrl != null && movie.plot != null) return@flow
        graph.catalogRepository.movieDetail(id)?.takeIf { it != movie }?.let { emit(it.backdrop(status)) }
    }

    /** As [movieBackdrop], for Shows. */
    fun seriesBackdrop(id: Long): Flow<Backdrop?> = flow {
        val series = series(id) ?: return@flow emit(null)
        val status = graph.viewerLibrary.coverStatuses().first().ofSeries(series.ref)
        emit(series.backdrop(status))
        if (series.backdropUrl != null && series.plot != null) return@flow
        graph.catalogRepository.seriesDetail(id)?.takeIf { it != series }?.let { emit(it.backdrop(status)) }
    }

    private fun Movie.backdrop(status: CoverStatus?) = Backdrop(
        displayTitle,
        backdropUrl,
        status,
        plot,
        year = year,
        genres = splitGenres(genre).take(BACKDROP_GENRES),
        runtimeSeconds = durationSeconds?.takeIf { it > 0 },
    )

    private fun Series.backdrop(status: CoverStatus?) = Backdrop(
        displayTitle,
        backdropUrl,
        status,
        plot,
        year = year,
        genres = splitGenres(genre).take(BACKDROP_GENRES),
    )

    /** Shows [key]'s grid ([browseKey]), or none for For you. */
    fun selectMovieCategory(key: String?) {
        movieCategory.value = key
    }

    /** Shows [key]'s grid ([browseKey]), or none for For you. */
    fun selectSeriesCategory(key: String?) {
        seriesCategory.value = key
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

    // ---- Netflix-style home rows ----------------------------------------------------------------
    // "Recently added" is reactive: it fills in live as a VOD sync lands. The computed feeds
    // (recommended, by-genre) are held in the StateFlows below and (re)built by [loadHomeFeeds] from
    // one shared scan of the library — guarded so opening the tab twice, or recomposing, does not
    // re-scan a 20k-title catalogue; it recomputes only when the profile, catalogue or started titles
    // change.

    val recentlyAddedMovies: StateFlow<List<Movie>> =
        graph.catalogRepository.recentlyAddedMovies()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentlyAddedSeries: StateFlow<List<Series>> =
        graph.catalogRepository.recentlyAddedSeries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // True while Recommended is being worked out, which takes seconds on a big library, so the
    // screen can keep its place with a placeholder row rather than have it land above the others.
    private val _recommendingMovies = MutableStateFlow(true)
    val recommendingMovies: StateFlow<Boolean> = _recommendingMovies.asStateFlow()

    private val _recommendingSeries = MutableStateFlow(true)
    val recommendingSeries: StateFlow<Boolean> = _recommendingSeries.asStateFlow()

    private val _recommendedMovies = MutableStateFlow<List<Movie>>(emptyList())
    val recommendedMovies: StateFlow<List<Movie>> = _recommendedMovies.asStateFlow()

    private val _recommendedSeries = MutableStateFlow<List<Series>>(emptyList())
    val recommendedSeries: StateFlow<List<Series>> = _recommendedSeries.asStateFlow()

    private val _movieGenreRows = MutableStateFlow<List<GenreGroup<Movie>>>(emptyList())
    val movieGenreRows: StateFlow<List<GenreGroup<Movie>>> = _movieGenreRows.asStateFlow()

    private val _seriesGenreRows = MutableStateFlow<List<GenreGroup<Series>>>(emptyList())
    val seriesGenreRows: StateFlow<List<GenreGroup<Series>>> = _seriesGenreRows.asStateFlow()

    /** What one title type's computed rows were last built from — the redundant-reload guard. */
    private data class HomeFeedsKey<T>(
        val profileId: Long,
        val catalogue: String?,
        val started: Set<T>,
        val recent: List<T>,
        val dismissed: Set<T> = emptySet(),
    )

    @Volatile private var loadedMovieFeeds: HomeFeedsKey<Long>? = null

    @Volatile private var loadedSeriesFeeds: HomeFeedsKey<SeriesRef>? = null

    /** Serialises [loadHomeFeeds] so two near-simultaneous calls (screen open + the profile emit in
     *  `init`) can't both start the heavy scan — the second waits, sees the guard satisfied, returns.
     *  MUST be declared before the `init` block below: `activeProfileId` is a StateFlow, so its
     *  `collect` fires synchronously during construction, and Kotlin initialises properties top to
     *  bottom — declared after `init`, this mutex would still be null when the first collect runs. */
    private val homeFeedsMutex = Mutex()

    init {
        // The home feeds are per profile and per catalogue (Recommended and the genre rows). Rebuild
        // them when the active profile changes — and once at start. Routed through the guarded
        // [loadHomeFeeds] so a profile switch triggers exactly one library scan, and re-opening the
        // tab with nothing changed triggers none. Reads an empty result
        // until a VOD sync has populated the catalogue; [ensureVodLoaded] re-runs it once one has.
        viewModelScope.launch {
            settings.activeProfileId.collect { loadHomeFeeds() }
        }
    }

    /**
     * Recomputes the computed home rows (Recommended + by-genre) from a SINGLE scan of each library.
     *
     * The reactive rows (recently-added, continue-watching) keep themselves current, so this only
     * covers the computed feeds. On a 20k-title library the old version was the lag: it read the
     * whole movie table three times over (recommended + movie genres, plus a profile-change reload)
     * and again on every tab re-open. Now each library is read once and reused for its rows, off the
     * main thread, and only when a [HomeFeedsKey] says something they depend on changed — so
     * re-opening Movies/Shows is free and a post-sync refresh still fills the rows in.
     */
    fun loadHomeFeeds(): Job = viewModelScope.launch {
        homeFeedsMutex.withLock {
            val profileId = settings.activeProfileId.value
            refreshMovieFeeds(profileId)
            refreshSeriesFeeds(profileId)
        }
    }

    private suspend fun refreshMovieFeeds(profileId: Long) {
        val key = HomeFeedsKey(
            profileId,
            catalogue = runCatching { graph.catalogRepository.movieFingerprint() }.getOrNull(),
            started = runCatching { graph.viewerLibrary.history.startedMovieIds(profileId) }.getOrDefault(emptySet()),
            recent = runCatching { graph.viewerLibrary.history.recentMovieIds(profileId) }.getOrDefault(emptyList()),
        )
        val loaded = loadedMovieFeeds
        if (key == loaded) return
        _recommendingMovies.value = true

        // Now, not after the scan: that takes seconds on a big library, and in the meantime the
        // viewer could move onto a title they have only just started.
        _recommendedMovies.value = _recommendedMovies.value.filterNot { it.id in key.started }

        val all = runCatching { graph.catalogRepository.allMovies() }.getOrDefault(emptyList())
        _recommendedMovies.value = runCatching {
            graph.catalogRepository.recommendedMoviesFrom(all, key.started)
        }.getOrDefault(emptyList())
        _recommendingMovies.value = false
        // Watching something takes it out of its rows and can move its genres to the top.
        _movieGenreRows.value = runCatching {
            graph.catalogRepository.moviesByGenreFrom(all, key.started, key.recent)
        }.getOrDefault(emptyList())
        loadedMovieFeeds = key
    }

    private suspend fun refreshSeriesFeeds(profileId: Long) {
        val key = HomeFeedsKey(
            profileId,
            catalogue = runCatching { graph.catalogRepository.seriesFingerprint() }.getOrNull(),
            started = runCatching { graph.viewerLibrary.history.startedSeries(profileId) }.getOrDefault(emptySet()),
            recent = runCatching { graph.viewerLibrary.history.recentSeries(profileId) }.getOrDefault(emptyList()),
            dismissed = runCatching { graph.viewerLibrary.history.dismissedSeries(profileId) }.getOrDefault(emptySet()),
        )
        val loaded = loadedSeriesFeeds
        if (key == loaded) return
        _recommendingSeries.value = true

        // As in [refreshMovieFeeds].
        _recommendedSeries.value =
            _recommendedSeries.value.filterNot { it.ref in key.started || it.ref in key.dismissed }

        val all = runCatching { graph.catalogRepository.allSeries() }.getOrDefault(emptyList())
        _recommendedSeries.value = runCatching {
            graph.catalogRepository.recommendedSeriesFrom(all, key.started, key.dismissed)
        }.getOrDefault(emptyList())
        _recommendingSeries.value = false
        _seriesGenreRows.value = runCatching {
            graph.catalogRepository.seriesByGenreFrom(all, key.started, key.dismissed, key.recent)
        }.getOrDefault(emptyList())
        loadedSeriesFeeds = key
    }

    // Movies + series load on demand — the first time the user opens Movies or Shows — rather than
    // up front at login. A provider's 40,000-title VOD list is exactly what makes a first sync
    // crawl, and most sessions only ever watch live TV. Loaded once per app run.
    val vodLoading: StateFlow<Boolean> = graph.catalogueRefresh.state
        .map { it.loadingVod }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    @Volatile private var vodRequested = false

    fun ensureVodLoaded() {
        if (vodRequested) return
        vodRequested = true
        viewModelScope.launch {
            graph.catalogueRefresh.refresh(CatalogueRefresh.Trigger.OpenedMoviesOrShows)
            // The catalogue may have grown — recompute the computed home rows off the fresh data.
            loadHomeFeeds()
        }
    }

    // ---- VOD search (shared by the unified search screen) --------------------------------------
    private val vodSearchInput = MutableStateFlow("")

    fun setVodSearchQuery(text: String) {
        vodSearchInput.value = text
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val movieResults: StateFlow<List<Movie>> =
        vodSearchInput.map { it.trim() }.debounceSearch().distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList()) else graph.catalogRepository.searchMovies(q)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val seriesResults: StateFlow<List<Series>> =
        vodSearchInput.map { it.trim() }.debounceSearch().distinctUntilChanged()
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

    /** Where the active Profile is with the series of [episodes], for its page's hero. */
    fun seriesStatus(episodes: List<Episode>): Flow<CoverStatus?> = graph.viewerLibrary.titleStatus.series(episodes)

    /** Where the active Profile is with [movie], for its page's hero. */
    fun movieStatus(movie: Movie): Flow<CoverStatus?> = graph.viewerLibrary.titleStatus.movie(movie.id)

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

    /** Whether playing [movie] would pick up partway: the Quality that plays keeps the resume point. */
    /** A film's More like this. */
    suspend fun moreLikeThis(movie: Movie): List<Movie> = graph.catalogRepository.moreLikeThis(movie)

    suspend fun resumesPartway(movie: Movie): Boolean {
        val playing = graph.vodQualities.planFor(MediaKey.Movie(movie.id)).start.key
        return graph.viewerLibrary.resumeFrom(playing.stored) > 0
    }

    /**
     * The picked category's grid as covers, each film's Qualities folded into one ([movieCovers]),
     * or null while it loads. Off the main thread: folding parses every name, again each time a
     * refresh touches the movies table, and done while composing it froze a Fire TV Stick long
     * enough for Android to close the app (#180).
     *
     * No category means For you, which doesn't read this: observing every row for nothing would
     * hold the whole catalogue in memory (#180).
     */
    val movieGrid: StateFlow<List<TitleCover>?> =
        grid(movieCategory, StreamKind.MOVIE) { category ->
            graph.catalogRepository.observeMovieCovers(category.sourceId, category.id)
                .map { movieCovers(it, graph.decoders) }
        }

    /** The picked category's shows as covers, or null while they load. */
    val seriesGrid: StateFlow<List<TitleCover>?> =
        grid(seriesCategory, StreamKind.SERIES) { category ->
            graph.catalogRepository.observeSeriesCovers(category.sourceId, category.id).map(::seriesCovers)
        }

    /**
     * [picked]'s covers. Only from a category the viewer may see: one saved before the Parental Lock
     * was back on, or since removed, comes back empty rather than showing what's locked.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun grid(
        picked: Flow<String?>,
        kind: StreamKind,
        covers: (Category) -> Flow<List<TitleCover>>,
    ): StateFlow<List<TitleCover>?> = picked
        .flatMapLatest { key ->
            if (key == null) {
                flowOf(null)
            } else {
                viewableCategories(kind).map { all -> all.firstOrNull { it.browseKey == key } }.distinctUntilChanged()
                    .flatMapLatest<Category?, List<TitleCover>?> { category ->
                        category?.let(covers) ?: flowOf(emptyList())
                    }
                    .onStart { emit(null) }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(KEEP_CATEGORIES_MILLIS), null)

    fun setSeriesFavourite(series: Series, favourite: Boolean) {
        viewModelScope.launch { graph.viewerLibrary.favourites.set(series, favourite) }
    }
}

/** [card] as Continue Watching draws it, or null when its title has left the catalogue. */
internal suspend fun resumeItem(catalog: CatalogRepository, card: ContinueWatchingCard): ResumeItem? = when (card) {
    is ContinueWatchingCard.Movie -> catalog.movie(card.movieId)?.let {
        ResumeItem(
            MediaKey.Movie(it.id),
            it.displayTitle,
            // The card is landscape: the wide backdrop, a frame from the film, fits it; a portrait
            // cover only crops.
            it.backdropUrl ?: it.posterUrl,
            card.progress,
            card = card,
            name = it.name,
            titleId = it.id,
        )
    }
    is ContinueWatchingCard.Series -> {
        val ep = card.episode
        catalog.seriesOf(ep)?.let {
            ResumeItem(
                key = MediaKey.Episode(ep.id),
                title = it.name,
                // The episode's own still says which episode it is; the show's wide backdrop fits the
                // landscape card next best, and a portrait poster only crops.
                posterUrl = ep.stillUrl ?: it.backdropUrl ?: it.posterUrl,
                progress = card.progress,
                episode = SeasonEpisode(ep.season, ep.episodeNumber),
                newEpisode = card.newEpisode,
                card = card,
                titleId = it.id,
            )
        }
    }
}

/** Home's Live Preview and the titles on its channel now and next, when the guide has them. */
data class LiveNow(val preview: LivePreview, val nowTitle: String?, val nextTitle: String?)

/**
 * What Home shows, top to bottom: the Live Preview, the active Profile's Continue Watching, what the
 * providers have Recently Added (shows, then movies), then its Favourite shows, movies and channels.
 */
data class HomeState(
    val continueWatching: List<ResumeItem>,
    val favouriteShows: List<Cover>,
    val favouriteMovies: List<Cover>,
    val favouriteChannels: List<HomeChannel>,
    val recentShows: List<Cover>,
    val recentMovies: List<Cover>,
    val live: LiveNow? = null,
)

/** A show's or movie's cover on Home, with where the active Profile is with it; null when never started. */
data class Cover(val title: VodTitle, val status: CoverStatus? = null)

/** A Favourite channel on Home, with the title of what's on it now when the guide on the device has it. */
data class HomeChannel(val channel: LineupChannel, val nowOn: String? = null)

/**
 * Home: the active Profile's Continue Watching across movies and series, its Favourites, and what
 * the providers have Recently Added. The state is null until all of it has loaded, so Home never
 * flashes its nothing-here hint at a Profile that has rows. A content type switched off in settings
 * is left off Home too, as its tab is from the side menu.
 */
class HomeViewModel @JvmOverloads constructor(
    app: Application,
    private val graph: ServiceLocator.Graph = ServiceLocator.get(app),
) : AndroidViewModel(app) {
    private val settings = graph.settings
    private val lineup = graph.channelLineup

    // Continue Watching and the covers' statuses come from one read of what the Profile has played.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val watching: Flow<Pair<List<ResumeItem>, CoverStatuses>> = combine(
        graph.viewerLibrary.home().mapLatest { home ->
            home.continueWatching.mapNotNull { resumeItem(graph.catalogRepository, it) } to home.covers
        },
        settings.moviesEnabled,
        settings.seriesEnabled,
    ) { (items, covers), movies, series -> items.filter { if (it.episode == null) movies else series } to covers }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val recentShows: Flow<List<VodTitle>> =
        settings.seriesEnabled.flatMapLatest { on -> graph.catalogRepository.recentlyAdded(movies = false, shows = on) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val recentMovies: Flow<List<VodTitle>> =
        settings.moviesEnabled.flatMapLatest { on -> graph.catalogRepository.recentlyAdded(movies = on, shows = false) }

    private val favourites: Flow<List<VodTitle>> = combine(
        graph.viewerLibrary.favourites.titles(),
        settings.moviesEnabled,
        settings.seriesEnabled,
    ) { titles, movies, shows ->
        titles.filter { if (it is VodTitle.OfMovie) movies else shows }
    }

    private fun List<VodTitle>.withStatuses(statuses: CoverStatuses) = map { title ->
        Cover(
            title,
            when (title) {
                is VodTitle.OfMovie -> statuses.ofMovie(title.movie.id)
                is VodTitle.OfSeries -> statuses.ofSeries(title.series.ref)
            },
        )
    }

    /** The time now, again every minute, so what's on the Live Preview's channel moves on. */
    private val minute: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(MINUTE_MILLIS)
        }
    }

    // Read from the guide already on the device, a minute at a time: Home never fetches one.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val favouriteChannels: Flow<List<HomeChannel>> =
        lineup.channels(LineupScope.Favourites()).onlyWhen(settings.liveEnabled)
            .flatMapLatest { channels ->
                if (channels.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    minute.map { now ->
                        val nowOn = lineup.nowOn(channels, now)
                        channels.map { HomeChannel(it, nowOn[it.key]) }
                    }.distinctUntilChanged()
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
                else -> lineup.channel(channelId).map { channel ->
                    channel?.let { shownQuality(preview, it) to it }
                }
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

    /**
     * The Quality last settled on, which may have been picked from the list, unless this device can't
     * play it: then the one its Channel plays by default, not a tile that only says it can't.
     */
    private fun shownQuality(preview: LivePreview, channel: LineupChannel): LivePreview {
        val settled = preview.channel ?: return preview
        return if (graph.decoders.playsQualityRanked(settled.qualityRank)) {
            preview
        } else {
            LivePreview.Playable(channel.primary)
        }
    }

    private suspend fun liveNow(preview: LivePreview, channel: LineupChannel?, now: Long): LiveNow {
        // The Channel's guide, as the guide itself picks it.
        val guideId = channel?.let { lineup.guideId(it) } ?: return LiveNow(preview, null, null)
        val shows = graph.epgRepository.nowAndNext(guideId, now)
        return LiveNow(preview, shows.now?.title, shows.next?.title)
    }

    val state: StateFlow<HomeState?> = combine(
        combine(watching, favourites, favouriteChannels, recentShows, recentMovies) {
                (continueWatching, statuses),
                favourites,
                channels,
                shows,
                movies,
            ->
            // Favourites carry no status: Home shows only their titles.
            val (favouriteMovies, favouriteShows) = favourites.map(::Cover).partition { it.title is VodTitle.OfMovie }
            HomeState(
                continueWatching,
                favouriteShows,
                favouriteMovies,
                channels,
                shows.withStatuses(statuses),
                movies.withStatuses(statuses),
            )
        },
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
        const val MINUTE_MILLIS = 60L * 1000
    }
}

/**
 * Waits for typing to pause, except when the box is emptied: a chosen Recent Search would otherwise
 * find the last search's results still there, and take them for its own.
 */
@OptIn(FlowPreview::class)
internal fun Flow<String>.debounceSearch(): Flow<String> = debounce { if (it.isEmpty()) 0L else SEARCH_PAUSE_MILLIS }

private const val SEARCH_PAUSE_MILLIS = 200L

/** The active Profile's Recent Searches, for the search screen. */
class RecentSearchesViewModel(app: Application) : AndroidViewModel(app) {
    private val recent = ServiceLocator.get(app).viewerLibrary.recentSearches

    /** Null until first loaded, so the empty box's hint doesn't flash up before the list. */
    val shown: StateFlow<List<String>?> =
        recent.observe().stateIn<List<String>?>(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(text: String) {
        viewModelScope.launch { recent.save(text) }
    }

    fun clear() {
        viewModelScope.launch { recent.clear() }
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
 * typing that device's address and code. See [SyncBundle] for what travels and how each item is
 * identified on the other device.
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
            graph.catalogueRefresh,
        )

    val state: StateFlow<ManagerServer.State> = server.state

    fun start() = server.start()

    fun stop() = server.stop()

    override fun onCleared() {
        server.stop()
    }
}
