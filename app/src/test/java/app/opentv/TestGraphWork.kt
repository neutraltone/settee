/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job

/*
 * How a TestGraph counts its work (#616): what's in flight on its threads, the short timers that work
 * sets, and the reads a test holds. TestGraph puts them together; nothing else uses them.
 */

/** The graph whose counted task the current thread is running, if any. */
internal val runningWork = ThreadLocal<Work?>()

/** Work in flight on a graph's threads, and a way to wake when any of it ends. */
internal class Work {
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

    /** Runs [task] as this work's, so a [TestGraph.Held] wait in it knows whose count to leave. */
    fun run(task: Runnable) {
        val before = runningWork.get()
        runningWork.set(this)
        try {
            task.run()
        } finally {
            runningWork.set(before)
            finished()
        }
    }

    fun park() = finished()

    fun unpark() = started()

    // A short wait, not a sleep: it returns as soon as a task ends, and the main looper's own work
    // (which doesn't come through here) is picked up on the next turn.
    fun awaitChange() = synchronized(lock) { lock.wait(WAKE_MILLIS) }

    private companion object {
        const val WAKE_MILLIS = 10L
    }
}

/**
 * The counted timers still to fire. Once they're all the graph has left to do, and [othersIdle] (the
 * main thread has nothing due either), the next idle check fires the earliest at once instead of
 * letting the test sit it out: nothing can be checked before it fires anyway (it's counted), so the
 * wait only cost real time, two seconds a settle for the guide's paced rebuilds. Fired in the order
 * they're due, one per check, so whatever each sets off runs before the next.
 */
internal class SoonTimers(
    private val work: Work,
    private val timers: ScheduledExecutorService,
    private val othersIdle: () -> Boolean,
) {
    private class Timer(val due: Long, val task: ScheduledFuture<*>, val run: Runnable)

    private val pending = mutableListOf<Timer>()

    /** Remembers [task], due in [millis], and returns how to forget it. */
    fun add(millis: Long, task: ScheduledFuture<*>, run: Runnable): () -> Unit {
        val timer = Timer(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis), task, run)
        synchronized(this) { pending += timer }
        return { synchronized(this) { pending.remove(timer) } }
    }

    fun fireIfAllThatsLeft() {
        val idleElsewhere = othersIdle()
        val next = synchronized(this) {
            pending.removeAll { it.task.isDone }
            pending.minByOrNull { it.due }?.takeIf { idleElsewhere && work.busy() == pending.size }
        }
        // Only if it hadn't started: a timer already firing finishes by itself.
        if (next != null && next.task.cancel(false)) {
            synchronized(this) { pending.remove(next) }
            timers.execute(next.run)
        }
    }
}

/**
 * [delegate], counting what it runs on [work] and remembering whose it is in [jobs], so teardown can
 * stop it. Its timers count too while they're due within [SOON_MILLIS]: a debounce of half a second
 * or a timeout of three, set by work still settling, is that work not yet done, and an idle check
 * that missed it ran before the result. A longer one (a gap before regrouping, a retry in half a
 * minute) is the work finished and resting, and counting it would keep a screen busy for ever.
 */
@OptIn(InternalCoroutinesApi::class)
internal class TrackedDispatcher(
    private val delegate: CoroutineDispatcher,
    private val label: String,
    private val work: Work,
    private val timers: ScheduledExecutorService,
    private val soon: SoonTimers,
    private val jobs: MutableSet<Job>,
) : CoroutineDispatcher(),
    Delay {
    override fun isDispatchNeeded(context: CoroutineContext) = delegate.isDispatchNeeded(context)

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val timer = after(timeMillis) { continuation.resume(Unit) }
        continuation.invokeOnCancellation { timer.dispose() }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
        after(timeMillis, block::run)

    // Resumed before it stops counting: resuming dispatches here, which counts it again first.
    private fun after(millis: Long, fire: () -> Unit): DisposableHandle {
        val counts = millis <= SOON_MILLIS
        if (counts) work.started()
        val done = AtomicBoolean()
        val finish = { if (done.compareAndSet(false, true) && counts) work.finished() }
        val run = Runnable {
            try {
                fire()
            } finally {
                finish()
            }
        }
        val task = timers.schedule(run, millis, TimeUnit.MILLISECONDS)
        val forget = if (counts) soon.add(millis, task, run) else null
        return DisposableHandle {
            task.cancel(false)
            forget?.invoke()
            finish()
        }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        context[Job]?.let { jobs += it }
        work.started()
        try {
            delegate.dispatch(context) { work.run(block) }
        } catch (e: RuntimeException) {
            work.finished()
            throw e
        }
    }

    override fun toString() = "TestGraph.$label"

    companion object {
        /** A timer due within this is work still settling. */
        const val SOON_MILLIS = 3_000L
    }
}

/**
 * [TestGraph.hold]'s gate: holds each read of the catalogue's rows (movies, series, episodes,
 * channels) on the thread making it, until released or [heldMillis] pass. A read that waits that long
 * was held by a test that forgot to release it, and [forgotten] says so, for teardown to fail it.
 */
internal class ReadGate(var heldMillis: Long) {
    @Volatile
    private var gate: TestGraph.Held? = null

    @Volatile
    var forgotten = false
        private set

    fun hold() = synchronized(this) { if (gate == null) gate = TestGraph.Held() }

    fun release() = synchronized(this) {
        gate?.release()
        gate = null
    }

    /** Holds [sql] here, on the thread about to run it, when it's a read of rows and a hold is on. */
    fun onStatement(sql: String) {
        val held = gate ?: return
        if (readsCatalogueRows(sql.trimStart()) && !held.awaitMillis(heldMillis)) forgotten = true
    }

    // A count or an EXISTS is a quick check, however big the table: only reads of rows are held.
    private fun readsCatalogueRows(sql: String): Boolean =
        sql.startsWith("SELECT", ignoreCase = true) && !QUICK.containsMatchIn(sql) && CATALOGUE.containsMatchIn(sql)

    private companion object {
        val QUICK = Regex("""^SELECT\s+(COUNT\(|EXISTS\()""", RegexOption.IGNORE_CASE)
        val CATALOGUE = Regex("""\b(movies|series|episodes|channels)\b""", RegexOption.IGNORE_CASE)
    }
}
