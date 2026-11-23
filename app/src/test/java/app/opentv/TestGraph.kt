/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.IdlingResource
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
import java.time.Duration
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
 * own, so a sync test's second device doesn't.
 */
@Suppress("LongParameterList", "TooManyFunctions") // Each is a Graph setting a test may need.
@OptIn(ExperimentalCoroutinesApi::class)
class TestGraph(
    private val compose: ComposeTestRule? = null,
    private val name: String? = null,
    private val virtualTime: Boolean = false,
    private val http: OkHttpClient? = null,
    private val clock: (() -> Long)? = null,
    private val decoders: DecoderList? = null,
    private val swapIn: Boolean = true,
) : TestRule,
    IdlingResource {
    init {
        require(!(virtualTime && compose != null)) { "A screen runs in real time: Compose has its own clock" }
    }

    val app: Application = ApplicationProvider.getApplicationContext()

    /** Virtual time's one dispatcher, with its scheduler; there's none in real time. */
    val dispatcher: TestDispatcher? = if (virtualTime) StandardTestDispatcher() else null

    private val work = Work()
    private val jobs: MutableSet<Job> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val io = dispatcher ?: Tracked(Dispatchers.IO, "io")
    private val default = dispatcher ?: Tracked(Dispatchers.Default, "default")
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
                        rule.registerIdlingResource(RoomWork)
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
            compose != null -> compose.waitForIdle()
            else -> drain("the graph's work to finish") { isIdleNow && RoomWork.isIdleNow }
        }
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

    @Volatile
    private var gate: Gate? = null

    /**
     * Holds every read of the catalogue's rows (movies, series, episodes, channels) on the thread
     * making it, until [release]: the way a big catalogue is slow on a Stick, so a test can check
     * the screen while it loads. Counts, EXISTS checks and other tables still run. A held read isn't
     * counted as work in flight, or a screen could never be checked while it waits; once released,
     * it is, so the next assertion sees what it brought. Can hold again after a release.
     */
    fun hold() {
        check(dispatcher == null) { "A held read would hold virtual time's only thread" }
        synchronized(this) { if (gate == null) gate = Gate() }
    }

    // Forgotten before it opens, so its reads count as running from the moment a test releases them.
    fun release() = synchronized(this) {
        val held = gate
        gate = null
        held?.open()
    }

    // ---- IdlingResource ----

    override val isIdleNow: Boolean
        get() = work.busy() - (gate?.waiting?.get() ?: 0) <= 0

    override fun getDiagnosticMessageIfBusy(): String =
        "${work.busy()} task(s) of the test graph still running (${gate?.waiting?.get() ?: 0} read(s) held)"

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
            if (opened) stopBackgroundWork()
        } finally {
            if (swapIn) ServiceLocator.swap(before)
            if (opened) {
                graph.database.close()
                app.deleteDatabase(fileName)
            }
            dispatcher?.let { Dispatchers.resetMain() }
        }
    }

    private fun stopBackgroundWork() {
        val stopping = CoroutineScope(dispatcher ?: Dispatchers.Default).launch { graph.stopBackgroundWork() }
        val viewModelWork = tracked.map { it.viewModelScope.coroutineContext.job }
        val stopped = { stopping.isCompleted && viewModelWork.all { it.isCompleted } }
        if (dispatcher != null) {
            dispatcher.scheduler.advanceUntilIdle()
            check(stopped()) { "The graph's background work didn't stop" }
        } else {
            drain("the test's work to stop") { stopped() && isIdleNow }
        }
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
            pool.execute {
                try {
                    task.run()
                } finally {
                    work.finished()
                }
            }
        } catch (e: RuntimeException) {
            work.finished()
            throw e
        }
    }

    private fun onStatement(sql: String) {
        val held = gate ?: return
        if (readsCatalogueRows(sql.trimStart())) held.pass()
    }

    // A count or an EXISTS is a quick check, however big the table: only reads of rows are held.
    private fun readsCatalogueRows(sql: String): Boolean =
        sql.startsWith("SELECT", ignoreCase = true) && !QUICK.containsMatchIn(sql) && CATALOGUE.containsMatchIn(sql)

    /** One [hold]: reads wait at it until it opens. */
    private class Gate {
        private val opened = CountDownLatch(1)
        val waiting = AtomicInteger()

        fun pass() {
            waiting.incrementAndGet()
            try {
                opened.await(HELD_SECONDS, TimeUnit.SECONDS)
            } finally {
                waiting.decrementAndGet()
            }
        }

        fun open() = opened.countDown()
    }

    // ---- Counting ----

    /** Work in flight on the graph's threads, and a way to wake when any of it ends. */
    private class Work {
        private val inFlight = AtomicInteger()
        private val lock = Object()

        fun busy() = inFlight.get()

        fun started() {
            inFlight.incrementAndGet()
        }

        fun finished() {
            inFlight.decrementAndGet()
            synchronized(lock) { lock.notifyAll() }
        }

        // A short wait, not a sleep: it returns as soon as a task ends, and the main looper's own work
        // (which doesn't come through here) is picked up on the next turn.
        fun awaitChange() = synchronized(lock) { lock.wait(WAKE_MILLIS) }
    }

    /** [delegate], counting what it runs and remembering whose it is, so teardown can stop it. */
    private inner class Tracked(private val delegate: CoroutineDispatcher, private val label: String) :
        CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext) = delegate.isDispatchNeeded(context)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            context[Job]?.let { jobs += it }
            work.started()
            try {
                delegate.dispatch(context) {
                    try {
                        block.run()
                    } finally {
                        work.finished()
                    }
                }
            } catch (e: RuntimeException) {
                work.finished()
                throw e
            }
        }

        override fun toString() = "TestGraph.$label"
    }

    private companion object {
        val QUICK = Regex("""^SELECT\s+(COUNT\(|EXISTS\()""", RegexOption.IGNORE_CASE)
        val CATALOGUE = Regex("""\b(movies|series|episodes|channels)\b""", RegexOption.IGNORE_CASE)

        /** Long enough to outlast a test's checks, short enough that a forgotten release can't hang a run. */
        const val HELD_SECONDS = 10L

        /** Far longer than any test's work takes to stop; only work that never stops reaches it. */
        const val STOP_SECONDS = 10L

        const val WAKE_MILLIS = 10L
    }
}
