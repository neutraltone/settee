/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player.torrent

import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.alerts.TorrentAlert
import com.frostwire.jlibtorrent.alerts.TorrentErrorAlert
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [TorrentEngine] on libtorrent, through FrostWire's jlibtorrent bindings (ADR 0012).
 *
 * A libtorrent session is made for each torrent and shut down with it, so nothing of the library
 * runs, listens or holds memory between plays. Library calls that can block (starting and stopping
 * the session, removing the torrent, the start-up watchdog) run on one background thread, in
 * order, so [start] and [stop] return at once and a start always follows the previous stop's
 * clean-up. [read] runs on the caller's thread, Media3's loader thread in #670.
 *
 * Streaming follows libtorrent's own documentation: the torrent is added from a magnet link with
 * sequential download on, every file but the chosen one is set to be skipped, and each read sets
 * piece deadlines over a window from the read position, cleared and set again after a seek. Pieces
 * are written with the POSIX disk backend: no memory-mapped files (a 32-bit Stick has little address
 * space to map a multi-gigabyte file into) and no write cache of its own, so a piece libtorrent has
 * verified is already in the file [read] opens.
 */
class LibtorrentEngine(
    private val cache: TorrentCache,
    private val config: TorrentEngineConfig = TorrentEngineConfig(),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : TorrentEngine {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "TorrentEngine").apply { isDaemon = true }
    }

    private val _status = MutableStateFlow<TorrentStatus>(TorrentStatus.Idle)
    override val status: StateFlow<TorrentStatus> = _status

    // Guarded by [lock].
    private var current: RunningTorrent? = null

    override fun start(request: TorrentRequest) {
        stop()
        val run = RunningTorrent(request, cache.folderFor(request.infoHash))
        // Starting afresh deletes what the last one fetched, so each start is worth a line.
        Log.i(TAG, "Torrent starting, file ${request.fileIndex ?: "largest"}")
        lock.withLock {
            current = run
            _status.value = TorrentStatus.Connecting()
        }
        if (sdkInt < MIN_SDK) {
            fail(run, TorrentFailure.UNSUPPORTED_DEVICE, "API $sdkInt")
        } else {
            worker.execute { begin(run) }
        }
    }

    private fun begin(run: RunningTorrent) {
        if (run.stopped) return
        try {
            run.folder.mkdirs()
            val session = SessionManager(false)
            run.session = session
            session.addListener(listenerFor(run))
            session.start(sessionParams(config))
            session.download(magnetFor(run.request), run.folder, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            worker.schedule({ watch(run) }, WATCH_MILLIS, TimeUnit.MILLISECONDS)
        } catch (e: LinkageError) {
            // The native library didn't load: an ABI this APK leaves out (x86 emulators) or a device
            // missing something it links against.
            fail(run, TorrentFailure.UNSUPPORTED_DEVICE, e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            fail(run, TorrentFailure.ENGINE_ERROR, e.message)
        }
    }

    private fun listenerFor(run: RunningTorrent) = object : AlertListener {
        override fun types() = intArrayOf(
            AlertType.ADD_TORRENT.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.PIECE_FINISHED.swig(),
            AlertType.TORRENT_ERROR.swig(),
        )

        override fun alert(alert: Alert<*>) {
            if (run.stopped) return
            when (alert.type()) {
                AlertType.ADD_TORRENT -> {
                    val handle = ownedHandle(run, alert as TorrentAlert<*>) ?: return
                    run.handle = handle
                    // A torrent libtorrent already had the file list for sends no METADATA_RECEIVED.
                    if (handle.isValid) onFileList(run, handle)
                }
                AlertType.METADATA_RECEIVED ->
                    ownedHandle(run, alert as TorrentAlert<*>)?.let { onFileList(run, it) }
                AlertType.TORRENT_ERROR ->
                    fail(run, TorrentFailure.ENGINE_ERROR, (alert as TorrentErrorAlert).error().message())
                else -> Unit
            }
            lock.withLock { changed.signalAll() }
        }
    }

    private fun onFileList(run: RunningTorrent, handle: TorrentHandle) {
        val info = handle.torrentFile()
        if (info == null || _status.value is TorrentStatus.Ready) return
        val files = info.files()
        // Stremio's rule for a stream that names no file: the largest one.
        val index = run.request.fileIndex ?: (0 until files.numFiles()).maxByOrNull { files.fileSize(it) } ?: 0
        val size = if (index < files.numFiles()) files.fileSize(index) else -1L
        when {
            size < 0 -> fail(run, TorrentFailure.ENGINE_ERROR, "No file $index of ${files.numFiles()}")
            !cache.roomFor(size) -> fail(run, TorrentFailure.NOT_ENOUGH_SPACE, "$size bytes")
            else -> {
                val priorities = Array(files.numFiles()) { Priority.IGNORE }
                priorities[index] = Priority.NORMAL
                handle.prioritizeFiles(priorities)
                lock.withLock {
                    if (current === run) {
                        run.pieceLength = info.pieceLength()
                        run.fileOffset = files.fileOffset(index)
                        run.fileSize = size
                        run.lastPiece = run.pieceAt(size - 1)
                        run.path = File(files.filePath(index, run.folder.absolutePath))
                        _status.value = TorrentStatus.Ready(size, peers = run.peersSeen)
                        changed.signalAll()
                    }
                }
            }
        }
    }

    /** Once a second: the peer count for the player, and the start-up time limit. */
    private fun watch(run: RunningTorrent) {
        if (run.stopped) return
        val peers = run.handle?.takeIf { it.isValid }?.status()?.numPeers() ?: 0
        run.peersSeen = maxOf(run.peersSeen, peers)
        val keepWatching = when (val now = _status.value) {
            is TorrentStatus.Connecting ->
                if (SystemClock.elapsedRealtime() - run.startedAt > config.startTimeoutMillis) {
                    val reason =
                        if (run.peersSeen == 0) TorrentFailure.NOBODY_SHARING else TorrentFailure.START_TIMED_OUT
                    fail(run, reason, "${run.peersSeen} peers")
                    false
                } else {
                    if (now.peers != peers) _status.value = TorrentStatus.Connecting(peers)
                    true
                }
            is TorrentStatus.Ready -> {
                if (now.peers != peers) _status.value = now.copy(peers = peers)
                true
            }
            else -> false
        }
        if (keepWatching) worker.schedule({ watch(run) }, WATCH_MILLIS, TimeUnit.MILLISECONDS)
    }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val run = lock.withLock { current } ?: throw TorrentReadException("Torrent stopped")
        awaitUntil(run) { _status.value is TorrentStatus.Ready }
        if (position >= run.fileSize) return -1
        val handle = run.handle ?: throw TorrentReadException("Torrent stopped")
        val piece = run.pieceAt(position)
        run.raise(handle, piece, config)
        awaitUntil(run) { handle.havePiece(piece) }
        return run.readFile(position, buffer, offset, length)
    }

    /**
     * Blocks until [done], failing if the torrent fails or stops meanwhile. [done] is asked outside
     * the lock, so the alert thread is never held up behind a call into the library; the timed wait
     * covers a change that lands between the two.
     */
    private fun awaitUntil(run: RunningTorrent, done: () -> Boolean) {
        while (!done()) {
            lock.withLock {
                checkStillRunning(run)
                try {
                    changed.await(POLL_MILLIS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    throw InterruptedIOException("Interrupted waiting for the torrent").apply { initCause(e) }
                }
            }
        }
        lock.withLock { checkStillRunning(run) }
    }

    private fun checkStillRunning(run: RunningTorrent) {
        val now = _status.value
        if (now is TorrentStatus.Failed) throw TorrentReadException("Torrent failed: ${now.reason}")
        if (current !== run || run.stopped) throw TorrentReadException("Torrent stopped")
    }

    override fun stop() {
        val run = lock.withLock {
            val run = current ?: return
            Log.i(TAG, "Torrent stopping")
            current = null
            run.stopped = true
            _status.value = TorrentStatus.Idle
            changed.signalAll()
            run
        }
        worker.execute { run.tearDown() }
    }

    private fun fail(run: RunningTorrent, reason: TorrentFailure, detail: String?) {
        lock.withLock {
            if (current !== run || _status.value is TorrentStatus.Failed) return
            _status.value = TorrentStatus.Failed(reason, detail)
            changed.signalAll()
        }
        Log.w(TAG, "Torrent failed: $reason")
        run.stopped = true
        worker.execute { run.tearDown() }
    }

    companion object {
        /** jlibtorrent's native library is built for Android 8.0 (API 26) and up (ADR 0012). */
        const val MIN_SDK = 26
        private const val TAG = "TorrentEngine"
        private const val WATCH_MILLIS = 1_000L
        private const val POLL_MILLIS = 250L
    }
}

/**
 * The torrent an alert is about, as a handle that outlives the alert. An alert's own `handle()`
 * points into the alert, which libtorrent frees once it has been handled, so keeping it crashed the
 * Stick in native code a second later, when the per-second watch asked whether it was still valid. The session's
 * `find` returns a copy that stays valid until the torrent is removed.
 */
private fun ownedHandle(run: RunningTorrent, alert: TorrentAlert<*>): TorrentHandle? =
    run.session?.find(alert.handle().infoHash())?.takeIf { it.isValid }
