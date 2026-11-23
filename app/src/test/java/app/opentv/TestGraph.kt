/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.ui.test.IdlingResource
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppDispatchers
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.player.DecoderList
import app.opentv.player.DeviceDecoders
import app.opentv.recording.RecordingStorage
import app.opentv.recording.StreamCapture
import java.time.Duration
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf

/**
 * The one way a UI or view-model test reaches the app (#616): a [ServiceLocator.Graph] whose
 * background work the test can see, swapped in for the app's own for the length of the test, and
 * everything it started stopped at the end. Tests know when that work has finished through this,
 * never by waiting a set amount of real time.
 *
 * Real time (the default): Room, and the graph's io and default dispatchers, run on real threads that
 * count the work they have in flight. Given a [compose] rule, the graph runs it inside itself (so
 * don't make it a `@Rule` as well: the screen has to be gone before the graph closes) and registers
 * as one of its idling resources, so `waitForIdle` and every assertion wait for that work too.
 * Without one, [awaitIdle] does the same for a view model. The main thread keeps Robolectric's clock,
 * which [advanceBy] moves on, only once nothing is running, so a delay never jumps past a read.
 *
 * Virtual time ([virtualTime]): the dispatchers, `Dispatchers.Main` and Room's queries all run on
 * one [dispatcher], so a test written with [runTest] never skips a delay while a read is out on a
 * real thread. The two are never mixed: there's no [hold] and no [compose] in virtual time.
 *
 * [name] gives the graph settings and a database file of its own, for two devices in one test or a
 * test that changes settings the process's other tests would see; without one, it has the process's
 * settings, as `ServiceLocator.Graph(app)` does. Only the graph with [swapIn] stands in for the app's
 * own, so a sync test's second device doesn't. [statements] hears every statement the database
 * runs, on the thread running it, for a test that counts reads. [openRecordingFile] and
 * [captureReconnectMillis] are the graph's own, for a recording's storage and its reconnects.
 */
// Each parameter is a Graph setting a test may need, and each function one of the ways a test waits;
// the counting behind them is in TestGraphWork.kt.
@Suppress("LongParameterList", "TooManyFunctions")
@OptIn(ExperimentalCoroutinesApi::class)
class TestGraph(
    private val compose: ComposeTestRule? = null,
    private val name: String? = null,
    private val virtualTime: Boolean = false,
    private val http: OkHttpClient? = null,
    private val clock: (() -> Long)? = null,
    private val decoders: DecoderList? = null,
    private val swapIn: Boolean = true,
    private val statements: ((String) -> Unit)? = null,
    private val openRecordingFile: ((String) -> RecordingStorage.Sink)? = null,
    private val captureReconnectMillis: Long = StreamCapture.RECONNECT_BACKOFF_MILLIS,
) : TestRule,
    IdlingResource {
    init {
        require(!(virtualTime && compose != null)) { "A screen runs in real time: Compose has its own clock" }
    }

    val app: Application = ApplicationProvider.getApplicationContext()

    /** Virtual time's one dispatcher, with its scheduler; there's none in real time. */
    val dispatcher: TestDispatcher? = if (virtualTime) StandardTestDispatcher() else null

    private val work = Work()
    private val timers = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "TestGraph timers").also { it.isDaemon = true }
    }
    private val soon = SoonTimers(work, timers) {
        Looper.myLooper() != Looper.getMainLooper() || shadowOf(Looper.getMainLooper()).isIdle
    }
    private val jobs: MutableSet<Job> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val io: CoroutineDispatcher = dispatcher ?: tracked(Dispatchers.IO, "io")
    private val default: CoroutineDispatcher = dispatcher ?: tracked(Dispatchers.Default, "default")
    private val reads = ReadGate(HELD_MILLIS)

    private fun tracked(delegate: CoroutineDispatcher, label: String) =
        TrackedDispatcher(delegate, label, work, timers, soon, jobs)
    private val fileName = "${name ?: "test-graph"}.db"
    private var opened = false

    /** Built on first use, so a test's fields can reach it; [graph] isn't swapped in until the test starts. */
    val graph: ServiceLocator.Graph by lazy {
        ServiceLocator.Graph(
            app,
            http = http,
            settingsFile = name,
            clock = clock ?: System::currentTimeMillis,
            dispatchers = AppDispatchers(io, default),
            decoders = decoders ?: DeviceDecoders(),
            openRecordingFile = openRecordingFile,
            captureReconnectMillis = captureReconnectMillis,
        ) { openDatabase().also { opened = true } }
    }

    val database: OpenTvDatabase get() = graph.database

    private var before: ServiceLocator.Graph? = null
    private val viewModels = ViewModelStore()
    private val tracked = mutableListOf<ViewModel>()
    private val drawn = CoroutineScope(SupervisorJob() + (dispatcher ?: Dispatchers.Main.immediate))

    override fun apply(base: Statement, description: Description): Statement {
        val screen = compose?.let { rule ->
            rule.apply(
                object : Statement() {
                    override fun evaluate() {
                        rule.registerIdlingResource(this@TestGraph)
                        base.evaluate()
                    }
                },
                description,
            )
        } ?: base
        return object : Statement() {
            override fun evaluate() {
                start()
                try {
                    screen.evaluate()
                } finally {
                    stop()
                }
            }
        }
    }

    private fun start() {
        dispatcher?.let { Dispatchers.setMain(it) }
        if (swapIn) before = ServiceLocator.swap(graph)
    }

    // ---- View models ----

    /** The view model [create] builds, made once per test, as a screen's store makes it, and cleared at the end. */
    inline fun <reified VM : ViewModel> viewModel(noinline create: () -> VM): VM = viewModel(VM::class.java, create)

    fun <VM : ViewModel> viewModel(type: Class<VM>, create: () -> VM): VM = ViewModelProvider(
        viewModels,
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                @Suppress("UNCHECKED_CAST")
                (create() as T).also { tracked += it }
        },
    )[type]

    /** A view model built some other way, stopped at the end with the rest. */
    fun <VM : ViewModel> track(viewModel: VM): VM = viewModel.also { tracked += it }

    /**
     * Runs [block] on the main thread until the test ends, as a screen collecting a view model's
     * state would: its flows keep going between two looks, as they do while the screen is drawn.
     */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job =
        drawn.launch(start = CoroutineStart.UNDISPATCHED, block = block)

    // ---- Waiting ----

    /**
     * Returns once nothing the graph started is running: no Room work but reads [hold] keeps, nothing
     * on its dispatchers, nothing due on the main thread. Through Compose when there's a screen.
     */
    fun awaitIdle() {
        when {
            dispatcher != null -> dispatcher.scheduler.runCurrent()
            showsScreen() -> compose?.waitForIdle()
            else -> drain("the graph's work to finish") { isIdleNow }
        }
    }

    // Compose's waitForIdle waits two seconds for a screen to appear when none has been set yet, so a
    // view model checked before its screen is drawn (or without one) is waited for directly.
    private fun showsScreen(): Boolean = when (val rule = compose) {
        null -> false
        is AndroidComposeTestRule<*, *> -> rule.activity.findViewById<ViewGroup>(android.R.id.content).childCount > 0
        else -> true
    }

    /**
     * The first value of [flow] that [predicate] accepts, collected on the main thread as a screen
     * would, once nothing is running: a view model's state shared on its own scope can't be waited
     * for by blocking the main thread it's shared on.
     */
    fun <T> firstWhenIdle(flow: Flow<T>, predicate: (T) -> Boolean = { true }): T {
        val found = mutableListOf<T>()
        val looking = launch { found += flow.first(predicate) }
        awaitIdle()
        looking.cancel()
        check(found.isNotEmpty()) { "Nothing matched once the graph's work had finished" }
        return found.single()
    }

    /**
     * Runs [block] on the main thread, as a screen's coroutine would, and returns once it and
     * everything it set off have finished, throwing what it threw. For a test that calls a view
     * model's suspending functions in turn: blocking the main thread around them, as runBlocking
     * does, would never let the work they leave there finish. A [block] still waiting once nothing
     * is running waits on something the graph doesn't count, and fails the test saying so.
     */
    fun runOnMain(block: suspend CoroutineScope.() -> Unit) {
        check(dispatcher == null) { "In virtual time, use runTest" }
        var failure: Throwable? = null
        val body = launch { failure = runCatching { block() }.exceptionOrNull() }
        awaitIdle()
        failure?.let { throw it }
        check(body.isCompleted) { "The test was still waiting once the graph's work had finished" }
    }

    /** Moves the clock on by [millis] once nothing is running, then waits for what that sets off. */
    fun advanceBy(millis: Long) {
        awaitIdle()
        if (dispatcher != null) {
            dispatcher.scheduler.advanceTimeBy(millis)
        } else {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        }
        awaitIdle()
    }

    /** [kotlinx.coroutines.test.runTest] on [dispatcher], for virtual time. */
    fun runTest(block: suspend TestScope.() -> Unit) {
        val virtual = checkNotNull(dispatcher) { "runTest needs TestGraph(virtualTime = true)" }
        kotlinx.coroutines.test.runTest(virtual, testBody = block)
    }

    // ---- Holding reads ----

    /**
     * Holds every read of the catalogue's rows (movies, series, episodes, channels) on the thread
     * making it, until [release]: the way a big catalogue is slow on a Stick, so a test can check
     * the screen while it loads. Counts, EXISTS checks and other tables still run. A held read isn't
     * counted as work in flight, or a screen could never be checked while it waits; once released,
     * it is, so the next assertion sees what it brought. Can hold again after a release. A read
     * still held after [heldMillis] carries on, and the test fails at teardown for the forgotten
     * release, rather than hanging the run or passing with the read let through by the clock.
     */
    fun hold() {
        check(dispatcher == null) { "A held read would hold virtual time's only thread" }
        reads.hold()
    }

    fun release() = reads.release()

    /** How long a held read waits for [release] before it's taken as forgotten. */
    internal var heldMillis: Long
        get() = reads.heldMillis
        set(value) {
            reads.heldMillis = value
        }

    // ---- IdlingResource ----

    // Asked over and over while a test waits, so it's also where the timers left waiting are run early.
    override val isIdleNow: Boolean
        get() = work.busy() <= 0 || false.also { soon.fireIfAllThatsLeft() }

    override fun getDiagnosticMessageIfBusy(): String = "${work.busy()} task(s) of the test graph still running"

    // ---- Teardown ----

    /**
     * Stops every view model and job the test started, waits for them while running the main
     * thread (their work ends there, so blocking it would wait for ever, as #547's CI did), then
     * closes the database and puts the app's graph back.
     */
    private fun stop() {
        try {
            release()
            drawn.cancel()
            viewModels.clear()
            tracked.forEach { it.viewModelScope.cancel() }
            synchronized(jobs) { jobs.toList() }.forEach { it.cancel() }
            // Even work that hasn't reached the database yet: cancelled on its way to a thread, it
            // only ends once it gets there, which can be after this test has finished.
            if (dispatcher == null) {
                val viewModelWork = tracked.map { it.viewModelScope.coroutineContext.job }
                drain("the test's view models to stop") { viewModelWork.all { it.isCompleted } && isIdleNow }
            }
            // A test that closed the database itself (through TestCatalog, say) stopped its work first.
            if (opened && graph.database.isOpen) stopBackgroundWork()
            check(!reads.forgotten) { "A read held by hold() waited ${heldMillis / 1_000} s and was never released" }
        } finally {
            if (swapIn) ServiceLocator.swap(before)
            if (opened) {
                graph.database.close()
                app.deleteDatabase(fileName)
            }
            dispatcher?.let { Dispatchers.resetMain() }
            timers.shutdownNow()
        }
    }

    private fun stopBackgroundWork() {
        // Async, so a failure is thrown here, into this test, rather than reported in whichever runs next.
        val stopping = CoroutineScope(dispatcher ?: Dispatchers.Default).async { graph.stopBackgroundWork() }
        val viewModelWork = tracked.map { it.viewModelScope.coroutineContext.job }
        val stopped = { stopping.isCompleted && viewModelWork.all { it.isCompleted } }
        if (dispatcher != null) {
            dispatcher.scheduler.advanceUntilIdle()
            check(stopped()) { "The graph's background work didn't stop" }
        } else {
            drain("the test's work to stop") { stopped() && isIdleNow }
        }
        stopping.getCompletionExceptionOrNull()?.let { throw it }
    }

    /**
     * Runs the main looper until [done], waking whenever a counted task finishes. Bounded, so work
     * that never stops fails the test that left it, with what it was, instead of hanging the run.
     */
    private fun drain(what: String, done: () -> Boolean) {
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(STOP_SECONDS)
        while (true) {
            if (onMain) shadowOf(Looper.getMainLooper()).idle()
            if (done() && (!onMain || shadowOf(Looper.getMainLooper()).isIdle)) return
            check(System.nanoTime() < until) { "Gave up waiting for $what: ${getDiagnosticMessageIfBusy()}" }
            work.awaitChange()
        }
    }

    // ---- Room ----

    private fun openDatabase(): OpenTvDatabase = if (dispatcher != null) {
        Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java)
            .setQueryCoroutineContext(dispatcher)
            .allowMainThreadQueries()
            .build()
    } else {
        // On a file with WAL, as the app's is: in memory there is one connection, so a held read
        // would hold up the quick checks too, which on the device read alongside it.
        Room.databaseBuilder(app, OpenTvDatabase::class.java, fileName)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .setQueryExecutor(counted(Executors.newCachedThreadPool()))
            .setQueryCallback({ sql, _ -> onStatement(sql) }, Runnable::run)
            .build()
    }

    // Threads to spare: a held read keeps its thread, where a slow one only borrows it.
    private fun counted(pool: Executor) = Executor { task ->
        work.started()
        try {
            pool.execute { work.run(task) }
        } catch (e: RuntimeException) {
            work.finished()
            throw e
        }
    }

    private fun onStatement(sql: String) {
        statements?.invoke(sql)
        reads.onStatement(sql)
    }

    /**
     * A wait of the test's own making on one of a graph's threads, such as a held read or a fake
     * server holding a request ([FakePanel.hold]). It isn't counted as work in flight while it waits,
     * or a screen could never be checked meanwhile, and it's counted again from the moment the test
     * calls [release], before the thread wakes, so the next check waits for what it brings. On any
     * other thread it's a plain latch.
     */
    class Held {
        private val gate = CountDownLatch(1)
        private val parked = mutableListOf<Work>()
        private var released = false

        /** Waits for [release], up to [seconds]: a forgotten one fails its test rather than hanging it. */
        fun await(seconds: Long): Boolean = awaitMillis(TimeUnit.SECONDS.toMillis(seconds))

        internal fun awaitMillis(millis: Long): Boolean {
            val work = runningWork.get()
            synchronized(this) {
                if (released) return true
                work?.let {
                    it.park()
                    parked += it
                }
            }
            try {
                return gate.await(millis, TimeUnit.MILLISECONDS)
            } finally {
                synchronized(this) { if (work != null && parked.remove(work)) work.unpark() }
            }
        }

        fun release() {
            synchronized(this) {
                released = true
                parked.forEach { it.unpark() }
                parked.clear()
            }
            gate.countDown()
        }
    }

    private companion object {
        /** Long enough to outlast a test's checks, short enough that a forgotten release can't hang a run. */
        const val HELD_MILLIS = 10_000L

        /** Far longer than any test's work takes to stop; only work that never stops reaches it. */
        const val STOP_SECONDS = 10L
    }
}

/**
 * A repository test's body in real time: Room answers on its own threads, so there's no virtual clock
 * to skip ahead while a read is still out on one (ADR 0008). Returns nothing, as JUnit wants of a test.
 */
fun testing(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)
