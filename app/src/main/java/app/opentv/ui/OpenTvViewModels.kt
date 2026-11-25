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
import app.opentv.core.ErrorWording
import app.opentv.core.ServerProblem
import app.opentv.core.ServiceLocator
import app.opentv.core.StatusBus
import app.opentv.core.ViewerError
import app.opentv.core.ViewerTime
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Episode
import app.opentv.data.model.GuideProgramme
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Movie
import app.opentv.data.model.Profile
import app.opentv.data.model.Programme
import app.opentv.data.model.Series
import app.opentv.data.model.SeriesRef
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.model.liveAt
import app.opentv.data.model.ref
import app.opentv.data.model.refreshFailure
import app.opentv.data.parser.VodTitleCleaner
import app.opentv.data.parser.displayTitle
import app.opentv.data.remote.AddonFailure
import app.opentv.data.remote.ProviderException
import app.opentv.data.repo.AddonOutcome
import app.opentv.data.repo.AddonStreamResults
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.data.repo.CategoryGroup
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.CoverStatuses
import app.opentv.data.repo.GenreGroup
import app.opentv.data.repo.HomeArrangement
import app.opentv.data.repo.HomeRowKind
import app.opentv.data.repo.KeyedCategories
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.LineupScope
import app.opentv.data.repo.LivePreview
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Opened
import app.opentv.data.repo.RefreshMessages
import app.opentv.data.repo.ShowEpisodes
import app.opentv.data.repo.SourceRepository
import app.opentv.data.repo.TitleCover
import app.opentv.data.repo.TitleIndex
import app.opentv.data.repo.VodTitle
import app.opentv.data.repo.coverStatuses
import app.opentv.data.repo.movies
import app.opentv.data.repo.nowOn
import app.opentv.data.repo.sameTmdbTitle
import app.opentv.data.repo.series
import app.opentv.data.repo.splitGenres
import app.opentv.data.repo.titleCovers
import app.opentv.data.repo.versionsOf
import app.opentv.data.work.SyncWorker
import app.opentv.pairing.ManagerServer
import app.opentv.player.playsQualityRanked
import app.opentv.sync.LastNasSync
import app.opentv.sync.NasSync
import app.opentv.sync.SyncClient
import app.opentv.sync.SyncEngine
import app.opentv.sync.SyncServer
import app.opentv.ui.vod.AddonSearch
import app.opentv.ui.vod.PageVersions
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
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
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * View models for the whole app.
 *
 * Grouped in one file on purpose: they are small, they share the same dependency graph, and
 * a contributor tracking down "where does the channel list come from" finds it in one place.
 */

class SourcesViewModel @JvmOverloads constructor(
    app: Application,
    /** Public for the OpenSubtitles step, which signs in through the graph this provider is saved through. */
    val graph: ServiceLocator.Graph = ServiceLocator.get(app),
) : AndroidViewModel(app) {

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
        val testError: ViewerError? = null,
        val adding: Boolean = false,
        val refreshing: Boolean = false,
        val syncMessage: String? = null,
        /** How the latest periodic or manual refresh went, for its row: never adding a provider's news. */
        val refreshResult: String? = null,
        /** Why adding this provider just failed, shown in place of [syncMessage]. */
        val addFailure: ViewerError? = null,
        /**
         * The provider the open form's last Save stored and kept, so saving again edits it. Here, not
         * in the form, because a phone turning round mid-save rebuilds the form before the save ends.
         */
        val storedSourceId: Long? = null,
        /** Why the first enabled provider whose channels couldn't be downloaded failed, as saved on it. */
        val loadFailure: ProviderFailure? = null,
        /** The provider whose save has just finished with its channels in, until its page leaves. */
        val saved: SavedProvider? = null,
        /**
         * Back on the step after Setup's save is taking it back. The provider is still listed until
         * this ends, so nothing may decide meanwhile whether the next is the first.
         */
        val undoingSetup: Boolean = false,
    ) {
        val syncing: Boolean get() = adding || refreshing
    }

    /**
     * A provider saved from its page, added or edited, now its channels are in. The page that saved
     * it may have been left, or rebuilt by a phone turning round, while the save ran, so it's state
     * here that whichever page is showing then acts on, not a callback holding the page it started on.
     */
    data class SavedProvider(val sourceId: Long, val added: Boolean)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val refresh = graph.catalogueRefresh
    private val messages = graph.refreshMessages

    init {
        viewModelScope.launch {
            graph.sourceRepository.observeAll().collect { sources ->
                // Saved on the providers by every refresh, so it's there after a restart and gone once
                // one works, whichever refresh that was.
                _ui.update {
                    it.copy(sources = sources, loaded = true, loadFailure = sources.providerFailures().firstOrNull())
                }
            }
        }
        // A refresh runs as background work, so this is how its progress and result reach the screen.
        // What was already there when this screen model appeared isn't news.
        viewModelScope.launch {
            var shown = refresh.state.value.last
            var wasRefreshing = false
            var announced = emptyMap<RefreshMessages.Step, CornerLine>()
            try {
                refresh.state.collect { state ->
                    val news = state.last.takeIf { it !== shown || (wasRefreshing && !state.refreshing) }
                    shown = state.last
                    wasRefreshing = state.refreshing
                    val running = cornerLines(state)
                    (announced - running.keys).values.forEach { StatusBus.clear(it.message) }
                    running.forEach { (step, line) ->
                        if (announced[step] !=
                            line
                        ) {
                            StatusBus.show(line.message, line.progress)
                        }
                    }
                    announced = running
                    _ui.update {
                        it.copy(
                            refreshing = state.refreshing,
                            syncMessage = when {
                                state.refreshing -> messages.of(RefreshMessages.Step.Refreshing)
                                news != null -> messages.of(news)
                                else -> it.syncMessage
                            },
                            refreshResult = (news as? CatalogueRefresh.Outcome.Refreshed)?.let(messages::of)
                                ?: it.refreshResult,
                        )
                    }
                }
            } finally {
                // The corner outlives this screen model, and the next one can't know this one set it.
                announced.values.forEach { StatusBus.clear(it.message) }
            }
        }
    }

    /**
     * Each refresh step running now, as its line in the main screen's corner. One line per step, not
     * one for the refresh: steps overlap (a new provider's guide while another's movies download),
     * and picking one by a fixed order hid whichever started last. Only the guide knows how far
     * through it is, so only it brings a bar, empty until its first share arrives.
     */
    private fun cornerLines(state: CatalogueRefresh.State) = buildMap {
        fun line(step: RefreshMessages.Step, progress: Float? = null) =
            put(step, CornerLine(messages.of(step), progress))
        if (state.refreshingChannels) line(RefreshMessages.Step.RefreshingChannels)
        if (state.refreshingMoviesAndShows) line(RefreshMessages.Step.RefreshingMoviesAndShows)
        if (state.refreshingGuide) line(RefreshMessages.Step.RefreshingGuide, state.guideProgress ?: 0f)
    }

    /** What the corner says for one refresh step, and how far through it is when it can tell. */
    private data class CornerLine(val message: String, val progress: Float?)

    /**
     * Forgets the last test and save, so a form doesn't open showing another's. A refresh still
     * running keeps its progress: that's news about now, not about another form.
     */
    /** Forgets [UiState.saved]: its page has left, or a page opened after it finished mustn't. */
    fun forgetSaved() = _ui.update { it.copy(saved = null) }

    fun clearFormMessages() {
        _ui.update {
            it.copy(
                testResult = null,
                testError = null,
                addFailure = null,
                storedSourceId = null,
                syncMessage = it.syncMessage.takeIf { _ ->
                    it.syncing
                },
            )
        }
    }

    fun test(draft: Source) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(testing = true, testResult = null, testError = null)
            val result = graph.sourceRepository.test(draft)
            _ui.value = _ui.value.copy(
                testing = false,
                testResult = result.getOrNull()?.let(graph.errorWording.tests::of),
                testError = result.exceptionOrNull()?.let {
                    graph.errorWording.ofProvider(it, R.string.error_test_failed)
                },
            )
        }
    }

    /** The running or latest Setup, for its screen. */
    val setup: StateFlow<CatalogueRefresh.Setup?> = refresh.state.map { it.setup }
        .stateIn(viewModelScope, SharingStarted.Eagerly, refresh.state.value.setup)

    val liveEnabled: StateFlow<Boolean> = graph.settings.liveEnabled

    /**
     * Saves, then immediately pulls the channels so the viewer sees them, not a spinner. The first
     * provider, saved while none was, goes through Setup instead: [onSetup] opens its screen as soon
     * as it's saved. Any other, once its channels are in, is [UiState.saved] for its page to leave;
     * Setup's screen decides where the first goes next.
     * [onChannels] hears either way once the channels are in (null) or why they didn't load.
     * Without [keepIfFails], a new provider whose channels don't load is removed again: the phone
     * puts the login right and sends it again, which would otherwise save a second one.
     * A provider kept other than through Setup becomes [UiState.storedSourceId], so a form left open
     * after a failed load saves that row again rather than adding another. So [adds] says whether
     * this came from adding a provider, which a draft's id alone no longer tells.
     */
    fun saveAndSync(
        draft: Source,
        keepIfFails: Boolean = true,
        onSetup: () -> Unit = {},
        onChannels: (failure: ViewerError?) -> Unit = {},
        adds: Boolean = draft.id == 0L,
    ) {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    adding = true,
                    addFailure = null,
                    saved = null,
                    syncMessage = messages.of(RefreshMessages.Step.SavingSource),
                )
            }
            // Editing a provider is never the first.
            val existing = if (draft.id == 0L) graph.sourceRepository.all() else null
            val first = existing != null && !hasProvider(existing)
            // What a Setup closed before its channels came in left behind; it never was a provider.
            // Only that one: another that never loaded was still added on purpose.
            val leftover = graph.settings.setupSourceId
            if (first && existing.any { it.id == leftover && it.lastCatalogSyncMillis == 0L }) {
                graph.catalogRepository.deleteSource(leftover)
            }
            val id = graph.sourceRepository.save(draft)
            val saved = graph.sourceRepository.byId(id)
            if (saved == null) {
                val failed = messages.of(RefreshMessages.Step.SaveFailed)
                _ui.update { it.copy(adding = false, syncMessage = failed) }
                onChannels(ViewerError(failed))
                return@launch
            }
            if (first) {
                // Started before Setup's screen opens, so the screen never sees the last try's state
                // (a failed login would send it straight back to the form).
                val setup = async(start = CoroutineStart.UNDISPATCHED) {
                    refresh.refresh(CatalogueRefresh.Trigger.Setup(saved))
                }
                onSetup()
                val outcome = setup.await()
                // Back on the step after saving is taking the provider away, and the form says nothing:
                // channels that came in just before would otherwise say they're loaded.
                if (outcome == CatalogueRefresh.Outcome.Undone || _ui.value.undoingSetup) return@launch
                // A failed login is back on the form, with why.
                val failure = (outcome as? CatalogueRefresh.Outcome.AddFailed)?.reason
                _ui.update { it.copy(adding = false, addFailure = failure, syncMessage = messages.of(outcome)) }
                onChannels(failure)
                return@launch
            }

            _ui.update { it.copy(syncMessage = messages.of(RefreshMessages.Step.LoadingChannels)) }
            // Editing a provider never removes it, whatever its channels do.
            val keep = keepIfFails || draft.id != 0L
            val outcome = refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(saved, keepIfFails = keep))
            val failure = (outcome as? CatalogueRefresh.Outcome.AddFailed)?.reason
            val removed = failure != null && !keep
            _ui.update {
                it.copy(
                    adding = false,
                    addFailure = failure,
                    storedSourceId = saved.id.takeUnless { removed },
                    syncMessage = messages.of(outcome),
                    saved = SavedProvider(saved.id, added = adds)
                        .takeIf { outcome is CatalogueRefresh.Outcome.ChannelsLoaded },
                )
            }
            onChannels(failure)
        }
    }

    /**
     * Takes back the first provider's save, for Back on the step after it: Setup stops, the provider
     * goes, and its form, still filled in, can save again once it has gone, never before, or the
     * second save would be a second provider.
     */
    fun undoSetup() {
        _ui.update { it.copy(adding = true, undoingSetup = true, addFailure = null, saved = null) }
        viewModelScope.launch {
            var removed: Long? = null
            try {
                removed = refresh.undoSetup()
            } finally {
                _ui.update { ui ->
                    // Gone from the list now, not when the database's next read lands.
                    val sources = ui.sources.filterNot { it.id == removed }
                    ui.copy(sources = sources, adding = false, undoingSetup = false, syncMessage = null)
                }
            }
        }
    }

    fun delete(source: Source) {
        viewModelScope.launch {
            graph.catalogRepository.deleteSource(source.id)
            // Every Profile's saved Home: another provider can be given this one's row ids (#595). Only
            // here, where a viewer removes a provider: Setup's other removals are of one that never loaded.
            graph.homeSnapshots.forgetAll()
        }
    }

    /**
     * Change an Xtream source's live-stream container. The repository rewrites the source's live
     * channel URLs in place, so the switch takes effect without a re-sync; the observed source list
     * then re-emits with the new [Source.liveFormat]. A no-op for M3U sources and unchanged values.
     * Runs in [ServiceLocator.Graph.appScope], not this screen's: leaving Sources mustn't cut it off.
     */
    fun setLiveFormat(source: Source, format: LiveStreamFormat) {
        if (source.kind != SourceKind.XTREAM || source.liveFormat == format) return
        graph.appScope.launch { graph.catalogRepository.setLiveFormat(source.id, format) }
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

    /**
     * The time now, from the graph's clock rather than the system's, so a test can pin the guide's
     * day: today's page ends at local midnight, and a guide built from the real clock moves its
     * programmes between days when a test runs late in the evening.
     */
    fun now(): Long = graph.clock()

    private val nowTick = MutableStateFlow(now())

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
        /**
         * What was on when the row was built. Rows aren't rebuilt every minute, so it may since have
         * ended: anything that draws what's on now asks [liveAt] with the screen's clock instead.
         */
        val now: GuideProgramme? = null,
        val next: GuideProgramme? = null,
        /** Every programme for this channel inside the guide window, start-ordered. */
        val programmes: List<GuideProgramme> = emptyList(),
    ) {
        val primary: Channel get() = channel.primary

        /** The programme on at [millis], from [programmes]. */
        fun liveAt(millis: Long): GuideProgramme? = programmes.liveAt(millis)

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
    private val guideWindow: StateFlow<ViewerTime.Window> =
        combine(nowTick, _guideDayOffset, graph.zone, ViewerTime::guideWindow)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ViewerTime.guideWindow(now(), 0, graph.zone.value),
            )

    /** The guide's left edge, where programme cells are measured from. */
    val windowStartMillis: StateFlow<Long> = guideWindow.map { it.startUtcMillis }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), guideWindow.value.startUtcMillis)

    /** Where the guide's day ends: today's at midnight, where tomorrow's starts. */
    val windowEndMillis: StateFlow<Long> = guideWindow.map { it.endUtcMillis }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), guideWindow.value.endUtcMillis)

    /** Which Channels the guide shows now; channel up and down follow it from the guide. */
    private val guideScope: Flow<LineupScope> =
        combine(selectedCategory, favouritesOnly, ::guideScopeOf).distinctUntilChanged()

    private fun guideScopeOf(category: String?, favs: Boolean): LineupScope = when {
        favs -> LineupScope.Favourites()
        category != null -> LineupScope.Group(category)
        else -> LineupScope.All()
    }

    /** A programme the guide lists, in full: the rows hold only what the guide's cells draw. */
    suspend fun programmeInFull(programme: GuideProgramme): Programme? = graph.epgRepository.programme(programme)

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
     * re-emits on its own when an EPG sync lands new programmes, at most every couple of seconds
     * however many batches it writes (`EpgRepository.observeGuideWindow`, #257).
     *
     * Held only while the guide is on screen (#327). Settings, Search and title pages keep the Live
     * screen built under them (`KeptShell`) but drop the main screen's lifecycle below STARTED, and
     * the screen collects [guideRows] only while it's started. So under them [guideRows] stops after
     * five seconds, and this five seconds after that, rather than regrouping every EPG write behind
     * them. Back shows the last rows at once, and they refresh from what was written meanwhile.
     * `GuideCollectionTest` keeps every collector of [guideRows] lifecycle-aware.
     */
    private val programmeIndex: StateFlow<ProgrammeIndex> =
        guideWindow
            .guideIndex(graph.epgRepository::observeGuideWindow)
            .flowOn(graph.dispatchers.default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgrammeIndex.Empty)

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

    /** What [guideRows] were last built from, so coming back to Live TV doesn't build them twice. */
    private val guideBuilt = GuideBuildMemory<Pair<LineupScope, List<LineupChannel>>>()

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
                // guideBuilds paces rebuilds for more listings from the end of the last build, so
                // a guide sync doesn't keep a slow build running back to back (#257).
                // Building the guide is the screen being built, which its skeleton rows show; the
                // main screen's corner is for background work (#581, #583).
                // The scope goes in with the channels it remembers, so a category whose channels
                // match the last one's still builds, with its own scope (#374).
                lineup.channels(scope).map { scope to it }
                    .guideBuilds(programmeIndex, guideBuilt) { (_, channels), byEpgChannel ->
                        GuideRows(scope, buildRows(channels, byEpgChannel, now()))
                    }
            }
            // Grouping thousands of channels against a 12-hour, all-feeds programme window is heavy
            // enough to freeze the UI for a big provider — a category tap that took minutes. Run the
            // whole pipeline off the main thread so the list just appears when it's ready.
            .flowOn(graph.dispatchers.default)
            .onEach { _rowsBuilt.value = true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuideRows())

    private fun buildRows(
        channels: List<LineupChannel>,
        byEpgChannel: Map<String, List<GuideProgramme>>,
        now: Long,
    ): List<Row> {
        // The programme→channel grouping is done once upstream (see [programmeIndex]) and passed
        // in ready-made, rather than regrouping the whole EPG on every category tap. Grouping is
        // kept out of SQL because the programme query cannot take a channel-id list without
        // hitting SQLite's bound-variable cap.
        return channels.map { channel ->
            val list = channel.guideId { it in byEpgChannel }
                ?.let { byEpgChannel.getValue(it).sortedBy { p -> p.startUtcMillis } }
                .orEmpty()

            Row(
                channel,
                now = list.liveAt(now),
                next = list.firstOrNull { it.startUtcMillis > now },
                programmes = list,
            )
        }
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
        nowTick.value = now()
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
            .flowOn(graph.dispatchers.default)
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
        viewModelScope.launch {
            // A guide whose channel list goes leaves matches to redo, which no later guide pass would
            // notice (#582).
            val guide = graph.epgRepository
            if (guide.ensureFeeds()) withContext(graph.dispatchers.default) { guide.runMatcher() }
        }
        viewModelScope.launch {
            graph.epgRepository.observeFeeds().collect { feeds ->
                _ui.value = _ui.value.copy(feeds = feeds)
            }
        }
    }

    fun setEnabled(feed: EpgFeed, enabled: Boolean) {
        viewModelScope.launch {
            graph.epgRepository.setFeedEnabled(feed, enabled)
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
    /**
     * Which title the card stands for, by the provider's ids, so Home can find the card again after
     * it has moved on: a show's [key] changes each time its card moves on to the next episode, and
     * local ids can change in a catalogue refresh. Required, though only [resumeItem] knows the
     * title: a fallback to [key] would quietly bring that back for any card built without it.
     */
    val titleKey: String,
)

/**
 * A show on its page: [series] as far as it's filled in, and whether its episodes have been [fetched].
 * Once they have, whether it has [noEpisodes] stored for any Version, and if so why, when every
 * Version's fetch failed ([failure]).
 */
data class OpenedShow(
    val series: Series,
    val fetched: Boolean,
    val noEpisodes: Boolean = false,
    val failure: ViewerError? = null,
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

/**
 * A Movies or Shows download that failed: which provider, so Provider setup can open it, why, and
 * its kind, for its Error Report.
 */
data class VodLoadFailure(val sourceId: Long, val reason: ViewerError, val sourceKind: SourceKind? = null)

class VodViewModel @JvmOverloads constructor(
    app: Application,
    private val graph: ServiceLocator.Graph = ServiceLocator.get(app),
) : AndroidViewModel(app) {
    private val settings = graph.settings

    init {
        // Asked here, off the main thread: the grid collapses its movies while composing, and listing a
        // cheap box's decoders the first time is slow enough to drop frames.
        viewModelScope.launch(graph.dispatchers.default) { graph.decoders.tallestListed }
    }

    /**
     * Which of For you's rows to hold places for before their cards come, from quick checks of the
     * Profile's own small tables (#581); null until those are back. So a row never lands above the
     * Highlight and pushes the row it was on off the screen.
     */
    val movieRowsExpected: StateFlow<ExpectedRows?> = expectedRows(
        graph.viewerLibrary.quickChecks.filmToContinue(),
        graph.viewerLibrary.quickChecks.hasFavourite(StreamKind.MOVIE),
    )

    /** As [movieRowsExpected], for Shows. */
    val seriesRowsExpected: StateFlow<ExpectedRows?> = expectedRows(
        graph.viewerLibrary.quickChecks.showToContinue(),
        graph.viewerLibrary.quickChecks.hasFavourite(StreamKind.SERIES),
    )

    private fun expectedRows(played: Flow<Boolean>, favourites: Flow<Boolean>) =
        combine(played, favourites, ::ExpectedRows)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(KEEP_EXPECTED_MILLIS), null)

    // null until loaded, which For you shows as a skeleton when [movieRowsExpected] expects the row.
    @OptIn(ExperimentalCoroutinesApi::class)
    val continueWatchingMovies: StateFlow<List<ResumeItem>?> =
        graph.viewerLibrary.continueWatchingMovies()
            .mapLatest { cards -> resumeItems(graph.catalogRepository, cards) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val continueWatchingSeries: StateFlow<List<ResumeItem>?> =
        graph.viewerLibrary.continueWatchingSeries()
            .mapLatest { cards -> resumeItems(graph.catalogRepository, cards) }
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
        graph.sourceRepository.observeAll(),
        settings.hiddenCategories,
        settings.hiddenUnlocked,
    ) { raw, sources, locked, unlocked ->
        if (unlocked) {
            raw
        } else {
            val keyed = KeyedCategories(raw, sources)
            raw.filter { keyed.keyOf(it) !in locked }
        }
    }

    private companion object {
        /** As long as the tab's other flows: through a quick trip to a title's page and back. */
        const val KEEP_CATEGORIES_MILLIS = 5_000L

        /** As long as the tab's other flows, so a trip to a title's page and back doesn't ask again. */
        const val LOAD_FAILURE_KEEP_MILLIS = 5_000L

        /** As long as the rows they hold places for, so coming back draws no skeleton the rows don't need. */
        const val KEEP_EXPECTED_MILLIS = 5_000L

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
     * What the viewer's add-ons offer for [movie], growing as each answers. The film is matched to IMDb
     * through TMDB first, since that's what add-ons are asked by; with no match they can't be asked.
     */
    fun addonStreams(movie: Movie): Flow<AddonSearch> = flow {
        graph.catalogRepository.imdbIdFor(movie).fold(
            onSuccess = { imdb ->
                if (imdb == null) {
                    // IMDb's id is what add-ons are asked by, so with none they can't be asked at all.
                    val noMatch = graph.errorWording.of(R.string.error_addon_no_match, "NoImdbMatch")
                    emit(AddonSearch.CantAsk(noMatch, canRetry = false))
                } else {
                    emitAll(graph.addonStreams.forFilm(imdb).map { it.toSearch() })
                }
            },
            onFailure = {
                emit(AddonSearch.CantAsk(graph.errorWording.ofAddon(AddonFailure.NoLookup(it)), canRetry = true))
            },
        )
    }

    private fun AddonStreamResults.toSearch() = AddonSearch.Results(
        streams = streams,
        finished = finished,
        // Read as each answer lands rather than watched: the switch can't change under an open picker.
        torrentsOn = settings.torrentsEnabled.value,
        failure = outcomes.firstNotNullOfOrNull { (it as? AddonOutcome.Unreachable)?.failure }
            ?.let(graph.errorWording::ofAddon),
    )

    // ---- Netflix-style home rows ----------------------------------------------------------------
    // "Recently added" is reactive: it fills in live as a VOD sync lands. The computed feeds
    // (recommended, by-genre) are held in the StateFlows below and (re)built by [loadHomeFeeds] from
    // one shared scan of the library — guarded so opening the tab twice, or recomposing, does not
    // re-scan a 20k-title catalogue; it recomputes only when the profile, catalogue or started titles
    // change.

    // Each title once, where its newest Version puts it (ADR 0007). A provider adds a title's Versions
    // together, so more rows are read than shown.
    /** Null until first read, while For you holds the row's place with a skeleton. */
    val recentlyAddedMovies: StateFlow<List<Movie>?> =
        graph.titleIndex.distinctMovies(graph.catalogRepository.recentlyAddedMovies(RECENT_ROWS_READ)) { it.id }
            .map { it.take(RECENT_TITLES_SHOWN) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val recentlyAddedSeries: StateFlow<List<Series>?> =
        graph.titleIndex.distinctSeries(graph.catalogRepository.recentlyAddedSeries(RECENT_ROWS_READ)) { it.id }
            .map { it.take(RECENT_TITLES_SHOWN) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    /** Serialises [loadHomeFeeds] so two near-simultaneous calls (screen open + a profile switch)
     *  can't both start the heavy scan — the second waits, sees the guard satisfied, returns.
     *  Declared before the `init` block below, which collects a StateFlow during construction. */
    private val homeFeedsMutex = Mutex()

    // Set by the first [loadHomeFeeds], not when it finishes: a Profile switched during that first
    // scan, which read the Profile before the switch, still needs the rows rebuilt.
    @Volatile private var homeFeedsWanted = false

    init {
        // The home feeds are per profile and per catalogue (Recommended and the genre rows). Movies
        // and Shows build them when they open; this rebuilds them when the active profile changes,
        // once one has. Not at start: the app's one model is built while Home is still drawing, and
        // reading the whole library then, with Movies and Shows perhaps switched off, cost every cold
        // start (#258). Routed through the guarded [loadHomeFeeds] so a profile switch triggers
        // exactly one library scan.
        viewModelScope.launch {
            settings.activeProfileId.collect { if (homeFeedsWanted) loadHomeFeeds() }
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
    fun loadHomeFeeds(): Job {
        homeFeedsWanted = true
        return viewModelScope.launch { refreshHomeFeeds() }
    }

    private suspend fun refreshHomeFeeds() {
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

        // One row per title, and a title started in any Version counts as started in all (#181).
        val titles = graph.titleIndex.movies.filterNotNull().first()
        val every = runCatching { graph.catalogRepository.allMovies() }.getOrDefault(emptyList())
        val started = key.started.flatMapTo(HashSet()) { id -> titles.of(id)?.versionIds?.asList() ?: listOf(id) }
        val all = titles.distinct(every) { it.id }
        _recommendedMovies.value = runCatching {
            graph.catalogRepository.recommendedMoviesFrom(all, started)
        }.getOrDefault(emptyList())
        _recommendingMovies.value = false
        // Watching something takes it out of its rows and can move its genres to the top.
        _movieGenreRows.value = runCatching {
            graph.catalogRepository.moviesByGenreFrom(all, started, key.recent)
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

        // As in [refreshMovieFeeds]: one row per title, started or dismissed in any Version.
        val titles = graph.titleIndex.series.filterNotNull().first()
        val every = runCatching { graph.catalogRepository.allSeries() }.getOrDefault(emptyList())
        val started = acrossVersions(every, key.started, titles)
        val dismissed = acrossVersions(every, key.dismissed, titles)
        val all = titles.distinct(every) { it.id }
        _recommendedSeries.value = runCatching {
            graph.catalogRepository.recommendedSeriesFrom(all, started, dismissed)
        }.getOrDefault(emptyList())
        _recommendingSeries.value = false
        _seriesGenreRows.value = runCatching {
            graph.catalogRepository.seriesByGenreFrom(all, started, dismissed, key.recent)
        }.getOrDefault(emptyList())
        loadedSeriesFeeds = key
    }

    // The first provider's movies and shows are fetched during Setup, while the viewer is waiting
    // anyway. This is the backup: the first time Movies or Shows opens in an app run, any list more
    // than 12 hours old is fetched, which covers a Setup step that failed or was left, a provider
    // added later from Settings (no Setup), and Movies or Shows switched on afterwards. A list Setup
    // has just fetched is fresh, so opening the tab doesn't fetch it again.
    val vodLoading: StateFlow<Boolean> = graph.catalogueRefresh.state
        .map { it.loadingVod }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    @Volatile private var vodRequested = false

    fun ensureVodLoaded() {
        if (vodRequested) return
        vodRequested = true
        checkVod()
    }

    /**
     * Try again on a tab whose download failed: asks again however many times this run already has.
     * A failed list is never recorded as fresh, so the check downloads it without being forced.
     */
    fun retryVod() {
        vodRequested = true
        checkVod()
    }

    private fun checkVod() {
        viewModelScope.launch {
            graph.catalogueRefresh.refresh(CatalogueRefresh.Trigger.OpenedMoviesOrShows)
            // The catalogue may have grown — recompute the computed home rows off the fresh data.
            loadHomeFeeds()
        }
    }

    /**
     * Why Movies' or Shows' latest download failed, for the first enabled provider it failed for;
     * null when none did. The tab shows it only with nothing of that kind stored, since titles win.
     */
    val moviesLoadFailure: StateFlow<VodLoadFailure?> =
        vodLoadFailure(graph.database.movies().observeAny()) { it.movies }
    val showsLoadFailure: StateFlow<VodLoadFailure?> =
        vodLoadFailure(graph.database.series().observeAny()) { it.shows }

    // Only once the store has said it holds none: the tab's rows are worked out after it opens, and
    // waiting on them instead flashed the error over titles that were there all along. Against the
    // providers there are now, too: a deleted one's failure would offer to edit nothing.
    private fun vodLoadFailure(stored: Flow<Boolean>, kind: (CatalogueRefresh.VodFailures) -> Map<Long, ViewerError>) =
        combine(
            graph.catalogueRefresh.state.map { kind(it.vodFailures) }.distinctUntilChanged(),
            graph.sourceRepository.observeAll(),
            stored,
        ) { failures, sources, anyStored ->
            if (anyStored) return@combine null
            sources.firstNotNullOfOrNull { source ->
                failures[source.id]?.takeIf { source.enabled }?.let { VodLoadFailure(source.id, it, source.kind) }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(LOAD_FAILURE_KEEP_MILLIS), null)

    // ---- VOD search (shared by the unified search screen) --------------------------------------
    private val vodSearchInput = MutableStateFlow("")

    fun setVodSearchQuery(text: String) {
        vodSearchInput.value = text
    }

    /** The latest visit to Search: see [searchVisitStarted]. Main thread only. */
    private var searchVisit = 0

    /**
     * Search has opened, as visit number returned. Its [searchVisitEnded] empties the search only if
     * no visit has started since: a visit ends once Search has finished leaving, and a new one opened
     * during that would otherwise lose what it had just searched for.
     */
    fun searchVisitStarted(): Int = ++searchVisit

    fun searchVisitEnded(visit: Int) {
        if (visit == searchVisit) setVodSearchQuery("")
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val movieResults: StateFlow<List<Movie>> =
        vodSearchInput.map { it.trim() }.debounceSearch().distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) {
                    flowOf(emptyList())
                } else {
                    graph.titleIndex.distinctMovies(graph.catalogRepository.searchMovies(q)) { it.id }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val seriesResults: StateFlow<List<Series>> =
        vodSearchInput.map { it.trim() }.debounceSearch().distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) {
                    flowOf(emptyList())
                } else {
                    graph.titleIndex.distinctSeries(graph.catalogRepository.searchSeries(q)) { it.id }
                }
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
     * A show opened on its page: the series as [CatalogRepository.openSeries] gives it, whose one
     * `get_series_info` brings its episodes too, so stored episodes show before TMDB answers. Each
     * other Version's episodes are asked for alongside, as the page lists episodes from all of them
     * (#181), and once TMDB has answered, any Version its TMDB id newly matches; [OpenedShow.fetched]
     * once they all have been. Episodes are fetched here rather than during catalogue sync: pulling
     * every episode of every series up front is what makes a first sync take twenty minutes on a
     * large provider, and most of it is never looked at. Collected again for the page's Try again.
     * [Opened.Gone] if the show isn't stored. [CatalogRepository.OPENING_LIMIT] covers all of it, the
     * other Versions too (#495): once it passes, the show is sent fetched, and with no episodes stored
     * yet, the page says the provider stopped responding, with Try again; episodes still on their way
     * replace that as they're stored. A page that leaves before then cancels the fetches.
     */
    fun openShow(id: Long): Flow<Opened<OpenedShow>> = channelFlow {
        val opening = TimeSource.Monotonic.markNow()
        // A set the fetches share, as they run side by side: each Version is asked for once.
        val asked: MutableSet<Long> = ConcurrentHashMap.newKeySet<Long>().apply { add(id) }
        // Set on the repository's thread, read here once the series has stopped coming.
        val own = AtomicReference<Result<Unit>?>(null)
        val fetching = mutableListOf<Deferred<List<Result<Unit>>>>()
        var opened: Series? = null
        var outlived = false
        try {
            graph.catalogRepository.openSeriesPage(id, onFetched = own::set).collect { page ->
                when (page) {
                    Opened.Gone -> send(Opened.Gone)
                    is Opened.Found -> {
                        val shown = page.title
                        if (fetching.isEmpty()) fetching += fetchVersions(shown, asked)
                        opened = shown
                        send(Opened.Found(OpenedShow(shown, fetched = false)))
                    }
                }
            }
            val series = opened ?: return@channelFlow
            // Again once TMDB has answered, for any Version its TMDB id newly matches.
            fetching += fetchVersions(series, asked)
            val left = CatalogRepository.OPENING_LIMIT - opening.elapsedNow()
            val others = withTimeoutOrNull(left) { fetching.awaitAll().flatten() }
            outlived = others == null
            // The show's own fetch unheard of once its flow has ended at the limit, too.
            val timedOut = outlived || (own.get() == null && opening.elapsedNow() >= CatalogRepository.OPENING_LIMIT)
            val outcomes = listOfNotNull(own.get()) + others.orEmpty()
            // Read here, after the fetches stored them, rather than left to the page's live read, which can
            // come a moment later and would have the page say there are none just before they show.
            val none = showEpisodes(series).first().all.isEmpty()
            val failure = when {
                !none -> null
                timedOut -> graph.errorWording.ofProvider(SocketTimeoutException(), R.string.vod_episodes_failed)
                else -> episodesFailure(outcomes)
            }
            send(Opened.Found(OpenedShow(series, fetched = true, noEpisodes = none, failure = failure)))
        } finally {
            // Nothing's waiting for them any more; past the limit they carry on, and store what they find.
            if (!outlived) fetching.forEach { it.cancel() }
        }
    }

    /**
     * Episodes for each of [series]' Versions not [asked] for yet, all at once, and how each fetch went;
     * on [viewModelScope], so that past the limit they outlive the page that asked.
     */
    private fun fetchVersions(series: Series, asked: MutableSet<Long>): Deferred<List<Result<Unit>>> =
        viewModelScope.async {
            val versions = graph.database.versionsOf(series, graph.titleIndex.versionIds).filter { asked.add(it.id) }
            versions.map { version ->
                async {
                    val source = graph.sourceRepository.byId(version.sourceId) ?: return@async null
                    graph.catalogRepository.fetchEpisodes(source, version.seriesId)
                }
            }.awaitAll().filterNotNull()
        }

    /**
     * Why a show's episodes didn't come, worded for its page, when every fetch for its Versions failed;
     * the opened Version's failure first. Not when any worked: even with nothing in it, the provider has
     * answered, and the viewer can't do anything about the other Versions' panels.
     */
    private fun episodesFailure(outcomes: List<Result<Unit>>): ViewerError? =
        outcomes.takeIf { all -> all.none { it.isSuccess } }
            ?.firstOrNull()
            ?.exceptionOrNull()
            ?.let { graph.errorWording.ofProvider(it, R.string.vod_episodes_failed) }

    /** [series]' episodes across its Versions, for its page (#181, ADR 0007). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun showEpisodes(series: Series): Flow<ShowEpisodes> = flow {
        emit(graph.database.versionsOf(series, graph.titleIndex.versionIds))
    }
        .flatMapLatest { versions ->
            val ordered = listOf(series) + versions.filter { it.id != series.id }
            combine(ordered.map { graph.catalogRepository.observeEpisodes(it.sourceId, it.seriesId) }) { lists ->
                ShowEpisodes.of(lists.first(), lists.drop(1))
            }
        }

    /** Where the active Profile is with the series of [episodes], for its page's hero. */
    fun seriesStatus(episodes: List<Episode>): Flow<CoverStatus?> = graph.viewerLibrary.titleStatus.series(episodes)

    /** Where the active Profile is with [movie], for its page's hero. */
    fun movieStatus(movieId: Long): Flow<CoverStatus?> = graph.viewerLibrary.titleStatus.movie(movieId)

    /** The series page's main button over that series' [episodes]: Resume, Up Next or the first episode. */
    fun seriesMainAction(episodes: List<Episode>) = graph.viewerLibrary.seriesMainAction(episodes)

    /** For the series detail screen: look the series up by row id. */
    fun seriesById(id: Long): kotlinx.coroutines.flow.Flow<Series?> =
        kotlinx.coroutines.flow.flow { emit(graph.catalogRepository.series(id)) }

    suspend fun movieById(id: Long): Movie? = graph.catalogRepository.movie(id)

    // ---- Detail screens -------------------------------------------------------------------------
    // Thin wrappers over the repository's detail feeds. The screens drive them from `produceState`
    // keyed on the id, so each detail instance owns its own state and a "More like this" hop to
    // another title loads cleanly without two screens fighting over one shared StateFlow.

    /**
     * A film opened on its page, lazily enriching backdrop/cast/genre on first open, or [Opened.Gone].
     * See [CatalogRepository.openMoviePage].
     */
    suspend fun openMovie(id: Long): Opened<Movie> = graph.catalogRepository.openMoviePage(id)

    /** Whether playing [movie] would pick up partway: the Quality that plays keeps the resume point. */
    /** A film's More like this. */
    suspend fun moreLikeThis(movie: Movie): List<Movie> = graph.catalogRepository.moreLikeThis(movie)

    /**
     * A show's More like this, as the covers its page's grid draws: one per title, badged as a category's
     * are, and never another Version of the show itself, even one TMDB doesn't know (ADR 0007).
     */
    suspend fun moreLikeThis(series: Series): List<TitleCover> {
        val titles = graph.titleIndex.series.filterNotNull().first()
        val own = titles.of(series.id)
        return titles.distinct(graph.catalogRepository.moreLikeThis(series)) { it.id }
            .filter { own == null || titles.of(it.id) !== own }
            .map { TitleCover(it.id, it.name, it.displayTitle, it.posterUrl, titles.badgeFor(it.id, it.name)) }
    }

    /** Everything in the library with this person in it, for their page. */
    suspend fun titlesWithPerson(name: String): List<VodTitle> =
        graph.titleIndex.distinct(graph.catalogRepository.titlesWithPerson(name))

    /**
     * Whether the page opened on [movie] shows it a Favourite, given the Profile's [favourites]: a title is
     * one when any of its Versions is, and the list holds only the one starred last (ADR 0007).
     */
    fun isFavourite(movie: Movie, favourites: List<Movie>): Boolean = favourites.any {
        it.id == movie.id ||
            (graph.titleIndex.sameTitle(StreamKind.MOVIE, it.id, movie.id) ?: sameTmdbTitle(it.tmdbId, movie.tmdbId))
    }

    /** As [isFavourite], for the page opened on [series]. */
    fun isFavourite(series: Series, favourites: List<Series>): Boolean = favourites.any {
        it.id == series.id ||
            (graph.titleIndex.sameTitle(StreamKind.SERIES, it.id, series.id) ?: sameTmdbTitle(it.tmdbId, series.tmdbId))
    }

    /** What a film's or show's page shows of its Versions, for the one opened at [key] (#181). */
    suspend fun pageVersions(key: MediaKey): PageVersions {
        val (plan, choices) = graph.vodQualities.pageFor(key)
        return PageVersions.of(plan, choices, plan.playsNothing(graph.decoders))
    }

    /** The viewer chose [version] in a Quality pill, or agreed to it in another language. */
    suspend fun chooseVersion(version: MediaKey) = graph.vodQualities.choose(version)

    suspend fun resumesPartway(movie: Movie): Boolean {
        val playing = graph.vodQualities.planFor(MediaKey.Movie(movie.id)).start.key
        return graph.viewerLibrary.resumeFrom(playing.stored) > 0
    }

    /**
     * The picked category's grid as covers, one per film however many of its Versions are there
     * ([titleCovers]), or null while it loads. Off the main thread: it parses every name, again each
     * time a refresh touches the movies table, and done while composing it froze a Fire TV Stick long
     * enough for Android to close the app (#180).
     *
     * No category means For you, which doesn't read this: observing every row for nothing would
     * hold the whole catalogue in memory (#180).
     */
    val movieGrid: StateFlow<List<TitleCover>?> =
        grid(movieCategory, StreamKind.MOVIE) { category ->
            graph.catalogRepository.observeMovieCovers(category.sourceId, category.id)
                .combine(graph.titleIndex.movies.filterNotNull()) { rows, titles ->
                    titleCovers(rows, titles, graph.decoders)
                }
        }

    /** The picked category's shows as covers, or null while they load. */
    val seriesGrid: StateFlow<List<TitleCover>?> =
        grid(seriesCategory, StreamKind.SERIES) { category ->
            graph.catalogRepository.observeSeriesCovers(category.sourceId, category.id)
                .combine(graph.titleIndex.series.filterNotNull()) { rows, titles ->
                    titleCovers(rows, titles, graph.decoders)
                }
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
        .flowOn(graph.dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(KEEP_CATEGORIES_MILLIS), null)

    fun setSeriesFavourite(series: Series, favourite: Boolean) {
        viewModelScope.launch { graph.viewerLibrary.favourites.set(series, favourite) }
    }
}

/**
 * [cards] as Continue Watching draws them, one per title: a row keys its cards by [ResumeItem.titleKey],
 * and two cards under one key would crash it. ViewerLibrary already gives each title one card, so
 * this only holds should that ever slip.
 */
internal suspend fun resumeItems(catalog: CatalogRepository, cards: List<ContinueWatchingCard>): List<ResumeItem> =
    cards.mapNotNull { resumeItem(catalog, it) }.distinctBy { it.titleKey }

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
            // As Home's cover rows key a title, so a refresh renumbering local ids can't lose the card.
            titleKey = VodTitle.OfMovie(it).pickKey,
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
                // The show, not the episode, which changes each time the card moves on to Up Next.
                titleKey = VodTitle.OfSeries(it).pickKey,
            )
        }
    }
}

/** Home's Live Preview and the titles on its channel now and next, when the guide has them. */
data class LiveNow(val preview: LivePreview, val nowTitle: String?, val nextTitle: String?)

/** Which of For you's rows Movies or Shows hold places for while they load: Recently Added always does. */
data class ExpectedRows(val continueWatching: Boolean, val favourites: Boolean)

/** A row's value once it has come; before it, the row is null in [HomeArrivals]. */
private class Arrived<T>(val value: T)

private fun <T> Flow<T>.arrival(): Flow<Arrived<T>?> = map<T, Arrived<T>?> { Arrived(it) }.onStart { emit(null) }

/** Home's rows, each null until it has come. */
private class HomeArrivals(
    val watching: Arrived<Pair<List<ResumeItem>, CoverStatuses>>?,
    val favourites: Arrived<List<VodTitle>>?,
    val channels: Arrived<List<HomeChannel>>?,
    val recentShows: Arrived<List<VodTitle>>?,
    val recentMovies: Arrived<List<VodTitle>>?,
) {
    /** The rows whose cards have come. */
    fun kinds(): Set<HomeRowKind> = buildSet {
        if (watching != null) add(HomeRowKind.CONTINUE_WATCHING)
        if (favourites != null) addAll(listOf(HomeRowKind.FAVOURITE_SHOWS, HomeRowKind.FAVOURITE_MOVIES))
        if (channels != null) add(HomeRowKind.FAVOURITE_CHANNELS)
        if (recentShows != null) add(HomeRowKind.RECENT_SHOWS)
        if (recentMovies != null) add(HomeRowKind.RECENT_MOVIES)
    }
}

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
    /** How the Profile arranged Home; the Live Preview is in [live] even when it turned it off, for Home's hint. */
    val arrangement: HomeArrangement = HomeArrangement.USUAL,
    val becauseYouWatched: BecauseYouWatched? = null,
    /**
     * The rows still on their way, each empty above until its cards arrive: Home holds their places
     * with skeletons, so none moves a row below it when it lands (#581).
     */
    val loading: Set<HomeRowKind> = emptySet(),
    /** Whether the Live Preview is still on its way, which Home holds the place of above the rows. */
    val liveLoading: Boolean = false,
    /**
     * Whether [live] is the one Home last saved, drawn while the fresh one comes (#595): a still tile,
     * as nothing has checked its channel can play yet.
     */
    val liveStill: Boolean = false,
    /**
     * Whether Because you watched is still being worked out, after a cold start: Home draws the saved
     * one meanwhile rather than have it come in later and move the rows below (#595).
     */
    val becausePending: Boolean = false,
)

/**
 * Home's Because you watched: up to 20 films or shows like [basedOn], the title the Profile finished
 * last, never that title itself.
 */
data class BecauseYouWatched(val basedOn: String, val covers: List<Cover>)

/**
 * A provider's saved refresh failure, with its [name] for the screen to say whose it is, and its
 * [kind] for its Error Report, which never sends the name.
 */
data class ProviderFailure(val sourceId: Long, val name: String, val error: ViewerError, val kind: SourceKind? = null)

/**
 * Each enabled provider's saved refresh failure, in the providers' order. A disabled one's is left out:
 * it isn't refreshed, so nothing would ever clear it.
 */
fun List<Source>.providerFailures(): List<ProviderFailure> = mapNotNull { source ->
    source.refreshFailure?.takeIf { source.enabled }?.let { ProviderFailure(source.id, source.name, it, source.kind) }
}

/**
 * Home's banner: each enabled provider whose last refresh failed in a way only the viewer can put right,
 * in the providers' order. Failures that usually go by the next refresh aren't here.
 */
data class ProviderBanner(val providers: List<ProviderFailure>)

/**
 * Whether only the viewer can put this right: a login, account or address the provider turned down, or an
 * address that answered like a website.
 * Read from the detail line's codes, the one part of a saved failure that isn't in the viewer's language.
 */
internal fun ViewerError.isTheViewersToFix(): Boolean = detail.split(" · ").any { it in VIEWERS_TO_FIX }

private val VIEWERS_TO_FIX = ProviderException.Problem.entries.filter { it.viewersToFix }
    .map { ErrorWording.codeName(it.name) }
    .toSet() + setOf("HTTP 401", "HTTP 403")

/** A show's or movie's cover on Home, with where the active Profile is with it; null when never started. */
data class Cover(val title: VodTitle, val status: CoverStatus? = null)

/** A Favourite channel on Home, with the title of what's on it now when the guide on the device has it. */
data class HomeChannel(val channel: LineupChannel, val nowOn: String? = null)

/**
 * Home: the active Profile's Continue Watching across movies and series, its Favourites, and what
 * the providers have Recently Added. The state is null only until quick checks say which rows to
 * expect; then each row comes as its cards do, the rest held as skeletons ([HomeState.loading]), so
 * Home is never blank for seconds and never flashes its nothing-here hint at a Profile that has rows.
 * A content type switched off in settings is left off Home too, as its tab is from the side menu.
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
            resumeItems(graph.catalogRepository, home.continueWatching) to home.covers
        },
        settings.moviesEnabled,
        settings.seriesEnabled,
    ) { (items, covers), movies, series -> items.filter { if (it.episode == null) movies else series } to covers }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val recentShows: Flow<List<VodTitle>> =
        settings.seriesEnabled.flatMapLatest { on -> recentlyAdded(movies = false, shows = on) }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val recentMovies: Flow<List<VodTitle>> =
        settings.moviesEnabled.flatMapLatest { on -> recentlyAdded(movies = on, shows = false) }

    /** Home's Recently Added rows, each title once where its newest Version puts it (ADR 0007). */
    private fun recentlyAdded(movies: Boolean, shows: Boolean): Flow<List<VodTitle>> = graph.titleIndex
        .distinctTitles(graph.catalogRepository.recentlyAdded(movies, shows, limit = RECENT_ROWS_READ))
        .map { it.take(HOME_RECENT_TITLES_SHOWN) }

    /** The last Because you watched worked out, and for which Profile; see [becauseYouWatched]. */
    @Volatile
    private var lastBecause: Pair<Long, Pair<String, List<VodTitle>>?>? = null

    /**
     * What's like the title the Profile finished last, of a kind Home shows, and its name: films for a
     * film, shows for a show, one cover per title. Worked out again only when it finishes something else,
     * so the row stays put.
     *
     * Choosing reads every title sharing a genre, which takes a moment on a Stick, so Home doesn't wait
     * for it: back on Home it starts from the row it last had for this Profile, or none.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val becauseYouWatched: Flow<BecauseRow> =
        combine(settings.moviesEnabled, settings.seriesEnabled, ::Pair)
            .flatMapLatest { (movies, shows) ->
                graph.viewerLibrary.lastFinished { if (it is VodTitle.OfMovie) movies else shows }
            }
            .distinctUntilChanged { a, b -> a?.kind == b?.kind && a?.id == b?.id }
            .mapLatest { basis ->
                val profile = settings.activeProfileId.value
                val row = basis?.let { VodTitleCleaner.clean(it.name) to likeThis(it) }.also {
                    lastBecause =
                        profile to it
                }
                BecauseRow(row)
            }
            .onStart {
                val last = lastBecause?.takeIf { it.first == settings.activeProfileId.value }
                emit(if (last == null) BecauseRow(null, pending = true) else BecauseRow(last.second))
            }

    private suspend fun likeThis(basis: VodTitle): List<VodTitle> {
        val catalog = graph.catalogRepository
        val limit = BECAUSE_YOU_WATCHED_TITLES
        val titles = when (basis) {
            is VodTitle.OfMovie -> catalog.moreLikeThis(basis.movie, limit).map(VodTitle::OfMovie)
            is VodTitle.OfSeries -> catalog.moreLikeThis(basis.series, limit).map(VodTitle::OfSeries)
        }
        return graph.titleIndex.distinct(titles)
            .filterNot { graph.titleIndex.sameTitle(basis.kind, it.id, basis.id) == true }
    }

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
                is VodTitle.OfMovie -> filmStatus(title.movie.id, statuses)
                is VodTitle.OfSeries -> statuses.ofSeries(title.series.ref)
            },
        )
    }

    /** A film's cover status across its Versions: Watched when any is, else the one it has (ADR 0007). */
    private fun filmStatus(movieId: Long, statuses: CoverStatuses): CoverStatus? {
        val versions = graph.titleIndex.movies.value?.of(movieId)?.versionIds ?: longArrayOf(movieId)
        val found = versions.asList().mapNotNull { statuses.ofMovie(it) }
        return found.firstOrNull { it == CoverStatus.Watched } ?: found.firstOrNull()
    }

    /** The time now, again every minute, so what's on the Live Preview's channel moves on. */
    private val minute: Flow<Long> = flow {
        while (true) {
            emit(graph.clock())
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
    //
    // A Live Preview the Profile turned off is only there for Home's hidden-rows hint, so it goes
    // without its channel or guide rather than reading them every minute for a tile nobody sees.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<LiveNow?> = combine(
        graph.viewerLibrary.live.preview(),
        settings.liveEnabled,
        graph.profileSettings.homeArrangement.map { it.livePreview }.distinctUntilChanged(),
    ) { preview, liveOn, shown -> preview?.takeIf { liveOn }?.let { it to shown } }
        .distinctUntilChanged()
        .flatMapLatest { found ->
            when {
                found == null -> flowOf(null)
                !found.second -> flowOf(LiveNow(found.first, null, null))
                else -> shownLive(found.first)
            }
        }

    /** The Live Preview of [preview] as Home shows it: its channel's Quality and what's on, a minute at a time. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun shownLive(preview: LivePreview): Flow<LiveNow?> {
        val channelId = preview.channel?.id
        val shown: Flow<Pair<LivePreview, LineupChannel?>?> = if (channelId == null) {
            flowOf(preview to null)
        } else {
            lineup.channel(channelId).map { channel -> channel?.let { shownQuality(preview, it) to it } }
        }
        return shown.distinctUntilChanged().flatMapLatest { found ->
            if (found == null) {
                flowOf(null)
            } else {
                minute.map { now -> liveNow(found.first, found.second, now) }.distinctUntilChanged()
            }
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

    /**
     * The rows Home holds places for before their cards are built: what a quick look at the Profile's
     * own small tables says will have cards, never the cards' own reads, which on a big catalogue take
     * seconds. Recently Added always does; a Profile's history or Favourites decide the rest (#581).
     * Because you watched has none: it starts from the row it last had, at once.
     */
    private val expected: Flow<Set<HomeRowKind>> = combine(
        graph.viewerLibrary.quickChecks.anythingToContinue(),
        graph.viewerLibrary.quickChecks.hasFavourite(StreamKind.SERIES),
        graph.viewerLibrary.quickChecks.hasFavourite(StreamKind.MOVIE),
        graph.viewerLibrary.quickChecks.hasFavourite(StreamKind.LIVE),
        combine(settings.moviesEnabled, settings.seriesEnabled, settings.liveEnabled, ::Triple),
    ) { played, shows, movies, channels, (moviesOn, showsOn, liveOn) ->
        buildSet {
            if (played && (moviesOn || showsOn)) add(HomeRowKind.CONTINUE_WATCHING)
            if (showsOn) add(HomeRowKind.RECENT_SHOWS)
            if (moviesOn) add(HomeRowKind.RECENT_MOVIES)
            if (shows && showsOn) add(HomeRowKind.FAVOURITE_SHOWS)
            if (movies && moviesOn) add(HomeRowKind.FAVOURITE_MOVIES)
            if (channels && liveOn) add(HomeRowKind.FAVOURITE_CHANNELS)
        }
    }.distinctUntilChanged()

    /** Each row as it arrives, none waiting on another: Home draws a row's cards over its place as they come. */
    private val arrivals: Flow<HomeArrivals> = combine(
        watching.arrival(),
        favourites.arrival(),
        favouriteChannels.arrival(),
        recentShows.arrival(),
        recentMovies.arrival(),
        ::HomeArrivals,
    )

    // The quick reads first: combine starts collecting in this order, and Room has four threads, which
    // the slow row reads queued ahead would otherwise take, leaving Home blank behind them.
    /** Whether Home holds the Live Preview's place before its tile is built: as [expected] does for the rows. */
    private val expectedLive: Flow<Boolean> = combine(
        graph.viewerLibrary.live.hasPreview(),
        settings.liveEnabled,
    ) { preview, liveOn -> preview && liveOn }

    private val rows: Flow<HomeState> = combine(
        combine(expected, expectedLive, ::Pair),
        // The arrangement is in the same state as the places, so Home is never drawn once in the usual
        // order and then moved.
        graph.profileSettings.homeArrangement,
        // The Live Preview comes as the rows do, its place held until then; Home opens on it either way.
        live.arrival(),
        becauseYouWatched,
        arrivals,
    ) { (expected, expectedLive), arrangement, live, because, arrived ->
        // Covers show before their statuses have come, and gain them when they do.
        val statuses = arrived.watching?.value?.second ?: CoverStatuses()
        // Favourites carry no status: Home shows only their titles.
        val (favouriteMovies, favouriteShows) = arrived.favourites?.value.orEmpty().map(::Cover)
            .partition { it.title is VodTitle.OfMovie }
        HomeState(
            continueWatching = arrived.watching?.value?.first.orEmpty(),
            favouriteShows = favouriteShows,
            favouriteMovies = favouriteMovies,
            favouriteChannels = arrived.channels?.value.orEmpty(),
            recentShows = arrived.recentShows?.value.orEmpty().withStatuses(statuses),
            recentMovies = arrived.recentMovies?.value.orEmpty().withStatuses(statuses),
            live = live?.value,
            arrangement = arrangement,
            becauseYouWatched = because.row
                ?.let { (basedOn, titles) -> BecauseYouWatched(basedOn, titles.withStatuses(statuses)) }
                ?.takeIf { it.covers.isNotEmpty() },
            becausePending = because.pending,
            loading = (expected - arrived.kinds()).filterTo(mutableSetOf(), arrangement::isShown),
            liveLoading = live == null && expectedLive && arrangement.livePreview,
        )
    }

    /**
     * Home's rows, kept updating while Home is drawn and while a screen covers it ([app.opentv.KeepHomeLive]), so
     * Back finds current rows and draws them at once (#192). Otherwise they stop a few seconds after
     * Home goes, and nothing is replayed: back from a player or the background, Home waits for fresh
     * rows rather than drawing old ones, where it would highlight a card that's about to move or go
     * (a title just finished, a Favourite just removed). Dropped as soon as the Profile changes, so
     * Back from Profiles never draws the last Profile's rows.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<HomeState?> = settings.activeProfileId
        .flatMapLatest(::homeOf)
        .stateIn(viewModelScope, WhileDrawnOrKeptLive(graph.homeKeptLive), null)

    /**
     * [profileId]'s Home: the rows it last finished seeing, drawn in the places of rows still on their
     * way, so a cold start shows cards at once rather than skeletons for seconds (#595). Each fresh row
     * replaces its saved one as it comes. Once every row has come and Home has settled, it's kept for
     * next time; never before, or the next cold start would bring back half a Home.
     */
    @OptIn(FlowPreview::class)
    private fun homeOf(profileId: Long): Flow<HomeState?> = channelFlow {
        // At once, so the last Profile's rows are dropped as soon as the Profile changes.
        send(null)
        // Drawn only with the fresh rows' quick checks, which say which rows to hold places for: a row the
        // saved Home lacks would otherwise come in a moment later and move every row below it.
        val saved = graph.homeSnapshots.readOnStart(profileId)
        val finished = MutableStateFlow<HomeState?>(null)
        launch {
            finished.filterNotNull().debounce(SAVE_PAUSE_MILLIS).collect { graph.homeSnapshots.save(profileId, it) }
        }
        rows.collect { fresh ->
            if (fresh.loading.isEmpty() && !fresh.liveLoading && !fresh.becausePending) finished.value = fresh
            send(saved?.let(fresh::over) ?: fresh)
        }
    }

    /** Apart from [state], so a provider failing or coming right never holds up or redraws the rows. */
    val providerBanner: StateFlow<ProviderBanner?> = graph.sourceRepository.observeAll()
        .map { sources ->
            sources.providerFailures()
                .filter { it.error.isTheViewersToFix() }
                .takeIf { it.isNotEmpty() }
                ?.let(::ProviderBanner)
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, WhileDrawnOrKeptLive(graph.homeKeptLive), null)

    /** Channel up and down go through every Channel, as Home has no list of its own. */
    fun zapFromAll() = lineup.zapFrom(LineupScope.All())

    private fun <T> Flow<List<T>>.onlyWhen(enabled: StateFlow<Boolean>): Flow<List<T>> =
        combine(this, enabled) { list, on -> if (on) list else emptyList() }

    private companion object {
        const val MINUTE_MILLIS = 60L * 1000

        /** How long Home stays as it is before it's kept: the Live Preview's guide and statuses land late. */
        const val SAVE_PAUSE_MILLIS = 2_000L
    }
}

/**
 * These fresh rows, with each row still on its way drawn from [saved] instead, when it has cards
 * there. A row [saved] has that's no longer expected stays off: it would only vanish a moment later.
 */
private fun HomeState.over(saved: HomeState): HomeState {
    val fromSaved = loading.filterTo(mutableSetOf()) { saved.hasCards(it) }
    fun <T> pick(kind: HomeRowKind, fresh: List<T>, kept: List<T>) = if (kind in fromSaved) kept else fresh
    val rows = copy(
        continueWatching = pick(HomeRowKind.CONTINUE_WATCHING, continueWatching, saved.continueWatching),
        favouriteShows = pick(HomeRowKind.FAVOURITE_SHOWS, favouriteShows, saved.favouriteShows),
        favouriteMovies = pick(HomeRowKind.FAVOURITE_MOVIES, favouriteMovies, saved.favouriteMovies),
        favouriteChannels = pick(HomeRowKind.FAVOURITE_CHANNELS, favouriteChannels, saved.favouriteChannels),
        recentShows = pick(HomeRowKind.RECENT_SHOWS, recentShows, saved.recentShows),
        recentMovies = pick(HomeRowKind.RECENT_MOVIES, recentMovies, saved.recentMovies),
        loading = loading - fromSaved,
        becauseYouWatched = becauseYouWatched ?: saved.becauseYouWatched?.takeIf { becausePending },
    )
    val live = saved.live?.takeIf { liveLoading } ?: return rows
    return rows.copy(live = live, liveLoading = false, liveStill = true)
}

private fun HomeState.hasCards(kind: HomeRowKind): Boolean = when (kind) {
    HomeRowKind.CONTINUE_WATCHING -> continueWatching.isNotEmpty()
    HomeRowKind.BECAUSE_YOU_WATCHED -> becauseYouWatched != null
    HomeRowKind.RECENT_SHOWS -> recentShows.isNotEmpty()
    HomeRowKind.RECENT_MOVIES -> recentMovies.isNotEmpty()
    HomeRowKind.FAVOURITE_SHOWS -> favouriteShows.isNotEmpty()
    HomeRowKind.FAVOURITE_MOVIES -> favouriteMovies.isNotEmpty()
    HomeRowKind.FAVOURITE_CHANNELS -> favouriteChannels.isNotEmpty()
}

/** Because you watched as [HomeViewModel] works it out: [pending] until it has, on a cold start. */
private data class BecauseRow(val row: Pair<String, List<VodTitle>>?, val pending: Boolean = false)

/**
 * Shares while anything collects or [keptLive] holds; once neither has for [STOP_MILLIS], stops and
 * drops the last value, as WhileSubscribed(STOP_MILLIS, replayExpirationMillis = 0) does.
 */
private class WhileDrawnOrKeptLive(private val keptLive: StateFlow<Boolean>) : SharingStarted {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun command(subscriptionCount: StateFlow<Int>): Flow<SharingCommand> =
        combine(subscriptionCount, keptLive) { collectors, kept -> collectors > 0 || kept }
            .distinctUntilChanged()
            .transformLatest { live ->
                if (live) {
                    emit(SharingCommand.START)
                } else {
                    delay(STOP_MILLIS)
                    emit(SharingCommand.STOP_AND_RESET_REPLAY_CACHE)
                }
            }
            .dropWhile { it != SharingCommand.START }
            .distinctUntilChanged()

    private companion object {
        const val STOP_MILLIS = 5_000L
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
class ProfilesViewModel @JvmOverloads constructor(
    app: Application,
    private val graph: ServiceLocator.Graph = ServiceLocator.get(app),
) : AndroidViewModel(app) {
    private val settings = graph.settings

    val profiles: StateFlow<List<Profile>> =
        graph.profiles.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The same list, but null until it has been read once, so launch can tell "not loaded" from "none". */
    val loadedProfiles: StateFlow<List<Profile>?> =
        graph.profiles.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeProfileId: StateFlow<Long> = settings.activeProfileId

    /**
     * A picture for each Profile on Who's watching?, by Profile id: a frame from the last film or
     * episode they played, so each card is recognisable at a glance and not just a letter. A Profile
     * that hasn't played anything still in the catalogue has none, and its card shows the letter alone.
     *
     * Never from a Parental-Locked category, even while the session is unlocked: Who's watching? is
     * what everyone in the room sees first, before anyone has chosen who they are.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val stills: StateFlow<Map<Long, String>> =
        combine(graph.profiles.observeAll(), settings.hiddenCategories) { profiles, locked -> profiles to locked }
            .mapLatest { (profiles, locked) ->
                val lockedCheck = LockedTitles(graph.catalogRepository, graph.sourceRepository, locked)
                profiles.mapNotNull { profile -> stillFor(profile.id, lockedCheck)?.let { profile.id to it } }.toMap()
            }
            .flowOn(graph.dispatchers.io)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private suspend fun stillFor(profileId: Long, locked: LockedTitles): String? {
        val catalog = graph.catalogRepository
        // A few, not one: the newest may have left the catalogue or be in a locked category.
        return graph.viewerLibrary.history.lastPlayed(profileId, STILL_CANDIDATES).firstNotNullOfOrNull { stored ->
            when (val key = MediaKey.parse(stored)) {
                is MediaKey.Movie -> catalog.movie(key.movieId)
                    ?.takeUnless { locked.has(StreamKind.MOVIE, it.sourceId, it.categoryId) }
                    // The card is square-ish and the picture sits behind a letter: the wide backdrop, a
                    // frame from the film, reads better cropped than a cover with its printed title.
                    ?.let { it.backdropUrl ?: it.posterUrl }
                is MediaKey.Episode -> catalog.episode(key.episodeId)?.let { ep ->
                    catalog.seriesOf(ep)
                        ?.takeUnless { locked.has(StreamKind.SERIES, it.sourceId, it.categoryId) }
                        ?.let { ep.stillUrl ?: it.backdropUrl ?: it.posterUrl }
                }
                else -> null
            }
        }
    }

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

    private companion object {
        const val STILL_CANDIDATES = 5
    }
}

/**
 * Whether a title is in one of the [locked] category groups. Each kind's categories are read once,
 * and only when something is locked.
 */
private class LockedTitles(
    private val catalog: CatalogRepository,
    private val providers: SourceRepository,
    private val locked: Set<String>,
) {
    private val keys = mutableMapOf<StreamKind, Map<Pair<Long, String>, String>>()
    private var sources: List<Source>? = null

    suspend fun has(kind: StreamKind, sourceId: Long, categoryId: String?): Boolean {
        if (locked.isEmpty() || categoryId == null) return false
        val known = sources ?: providers.all().also { sources = it }
        val byId = keys.getOrPut(kind) {
            val keyed = KeyedCategories(catalog.observeCategories(kind).first(), known)
            keyed.categories.associate { (it.sourceId to it.id) to keyed.keyOf(it) }
        }
        return byId[sourceId to categoryId] in locked
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
    private val client = SyncClient(graph.httpClient, graph.dispatchers)
    private val engine = SyncEngine(graph)

    val serverState: StateFlow<SyncServer.State> = server.state

    sealed interface ReceiveState {
        data object Idle : ReceiveState
        data object Connecting : ReceiveState
        data class Done(val merged: Int) : ReceiveState
        data class Failed(val reason: ViewerError) : ReceiveState
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
        data class Failed(val reason: ViewerError) : NasState
    }

    private val _nasState = MutableStateFlow<NasState>(NasState.Idle)

    /**
     * Until Sync now is pressed, the last sync's failure if it failed, the launch's included (#484):
     * otherwise the row's reassuring note stayed up while nothing had synced for weeks. Only while
     * automatic NAS sync is on and a NAS is set up: a failure of a sync no longer meant to run, or of a
     * NAS since removed, isn't news.
     */
    val nasState: StateFlow<NasState> =
        combine(_nasState, graph.lastNasSync.outcome, nasAutoSync, smbHost, ::nasStateOf)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                nasStateOf(_nasState.value, graph.lastNasSync.outcome.value, nasAutoSync.value, smbHost.value),
            )

    private fun nasStateOf(pressed: NasState, last: LastNasSync.Outcome?, auto: Boolean, host: String): NasState {
        val failure = last?.failure?.takeIf { auto && host.isNotBlank() }
        return if (pressed == NasState.Idle && failure != null) NasState.Failed(failure) else pressed
    }

    fun setNasAutoSync(enabled: Boolean) = graph.settings.setNasAutoSync(enabled)

    fun syncNas() {
        if (_nasState.value is NasState.Syncing) return
        viewModelScope.launch {
            _nasState.value = NasState.Syncing
            val ctx = getApplication<Application>()
            val result = nas.sync()
            val failure = graph.lastNasSync.record(result)
            _nasState.value = when {
                failure != null -> NasState.Failed(failure)
                result is NasSync.Result.Success && result.peers > 0 ->
                    NasState.Done(ctx.getString(R.string.sync_nas_done, result.merged, result.peers))
                else -> NasState.Done(ctx.getString(R.string.sync_nas_uploaded))
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
                .onFailure { _receiveState.value = ReceiveState.Failed(receiveFailure(it)) }
        }
    }

    /** The code not matching is the one failure the viewer can fix from here, so it says so. */
    private fun receiveFailure(error: Throwable): ViewerError =
        if (error is SyncClient.Refused && error.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            graph.errorWording.of(error, R.string.sync_wrong_code)
        } else {
            graph.errorWording.of(error, R.string.sync_receive_failed)
        }

    /** What sharing's failure says to the viewer. */
    fun describe(failed: SyncServer.State.Failed): ViewerError = when (failed.problem) {
        ServerProblem.NO_NETWORK ->
            graph.errorWording.ofHardware(R.string.sync_share_no_network_tv, R.string.sync_share_no_network_device)
        ServerProblem.COULDNT_START ->
            graph.errorWording.ofHardware(R.string.sync_share_failed_tv, R.string.sync_share_failed_device, failed.code)
        ServerProblem.TIMED_OUT -> graph.errorWording.of(R.string.sync_share_timed_out)
        ServerProblem.TOO_MANY_CODES -> graph.errorWording.of(R.string.sync_share_too_many_codes)
    }

    fun resetReceive() {
        _receiveState.value = ReceiveState.Idle
    }

    override fun onCleared() {
        server.stop()
    }
}

/**
 * Owns the "manage channels from your phone or laptop" web server for Settings' Manage on phone or
 * laptop.
 *
 * Same ownership shape as [SyncViewModel]: the server is bound to [viewModelScope] (so its idle
 * watchdog is torn down with Settings) and stopped in [onCleared]. The pane also starts it when its
 * section is open and stops it when the section changes, mirroring the phone-pairing lifecycle, so
 * no socket is ever left listening on the user's network once they move on.
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

/** How many rows a Recently Added row reads, to fill [RECENT_TITLES_SHOWN] titles of several Versions each. */
private const val RECENT_ROWS_READ = 90

/** How many titles a Recently Added row shows. */
private const val RECENT_TITLES_SHOWN = 30

/** How many titles Home's Recently Added rows show. */
private const val HOME_RECENT_TITLES_SHOWN = 20

/** [refs] and every other Version of the shows they're Versions of, among [every] show. */
private fun acrossVersions(every: List<Series>, refs: Set<SeriesRef>, titles: TitleIndex.Titles): Set<SeriesRef> {
    if (refs.isEmpty()) return refs
    val marked = every.filter { it.ref in refs }.mapNotNullTo(HashSet()) { titles.of(it.id) }
    return refs + every.filter { titles.of(it.id) in marked }.map { it.ref }
}

/** How many titles Home's Because you watched row holds. */
private const val BECAUSE_YOU_WATCHED_TITLES = 20
