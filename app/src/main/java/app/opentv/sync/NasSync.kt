/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import app.opentv.core.ServiceLocator
import app.opentv.recording.RecordingStorage
import app.opentv.recording.SmbClient
import app.opentv.recording.SmbConfig
import kotlinx.coroutines.withContext

/**
 * Free, server-less "cloud" sync through the user's own NAS.
 *
 * There is no server of ours and no account: every device drops its own [SyncBundle] as a small
 * JSON file into one shared folder on the SMB share the user already set up for recordings, and
 * reads back every other device's file. The folder is a plain rendezvous — the merge logic lives
 * in [SyncEngine], exactly the one used by LAN sync, so favourites, watch history and NAS
 * recordings converge the same way whether they arrive over wifi or via the NAS.
 *
 * Each device writes `<share>/<folder>/OpenTV/sync/<deviceId>.json`. Reading is additive and
 * newest-wins (see [SyncEngine]); nothing on the NAS is ever deleted, so a device that has been
 * offline for months still contributes when it next syncs.
 */
class NasSync(private val graph: ServiceLocator.Graph, private val files: NasFiles = SmbNasFiles) {

    private val engine = SyncEngine(graph)

    sealed interface Result {
        /**
         * [merged] items pulled in from [peers] other devices' files (this device's own excluded).
         * [unreadable] more couldn't be read or decoded, the first for [unreadableCause].
         */
        data class Success(
            val merged: Int,
            val peers: Int,
            val unreadable: Int = 0,
            val unreadableCause: Throwable? = null,
        ) : Result

        /** No SMB/NAS is configured yet — the user needs to set one up under Recording settings. */
        data object NotConfigured : Result

        /** Anything went wrong reaching or reading the NAS. [message] is best-effort, may be null. */
        /** [cause] is for the detail line and the log; its message is never shown. */
        data class Failed(val cause: Throwable) : Result
    }

    /**
     * Publish this device's bundle, then merge in every other device's. Safe to call from anywhere:
     * all I/O is on the graph's io dispatcher, a blank/unreachable NAS returns a [Result] rather than
     * throwing, and a single unreadable peer file is skipped rather than failing the whole run.
     */
    suspend fun sync(): Result = withContext(graph.dispatchers.io) {
        val config = RecordingStorage.smbConfig(graph.settings)
        if (!config.isConfigured) return@withContext Result.NotConfigured

        runCatching {
            val deviceId = graph.settings.syncDeviceId
            val ownFile = "$deviceId.json"

            // 1. Write this device's bundle. openForWrite creates the sync folder if missing.
            val bundleJson = engine.encode(engine.gather())
            files.writeText(config, locatorFor(config, ownFile), bundleJson)

            // 2. Read and merge every OTHER device's bundle.
            val peerFiles = files.list(config, syncFolderPath(config))
                .filter { it.endsWith(".json", ignoreCase = true) && it != ownFile }

            val bundles = peerFiles.mapNotNull { name ->
                val text = runCatching { files.readText(config, locatorFor(config, name)) }
                    .getOrNull() ?: return@mapNotNull null
                runCatching { engine.decode(text) }.getOrNull()
            }
            // Together, so a series several peers name is fetched once.
            Result.Success(engine.apply(*bundles.toTypedArray()).total, bundles.size)
        }.getOrElse { Result.Failed(it) }
    }

    /** `<folder>/OpenTV/sync` within the share — the shared rendezvous every device agrees on. */
    private fun syncFolderPath(config: SmbConfig): String {
        val base = config.folder.split('/', '\\').filter { it.isNotBlank() }
        return (base + listOf(NasFolder.NAME, "sync")).joinToString("/")
    }

    private fun locatorFor(config: SmbConfig, fileName: String): String =
        "smb://${config.host}/${config.share}/${syncFolderPath(config)}/$fileName"
}

/** The SMB calls NAS sync makes, so a test can stand in for the NAS. */
interface NasFiles {
    fun list(config: SmbConfig, folder: String): List<String>

    fun readText(config: SmbConfig, locator: String): String

    fun writeText(config: SmbConfig, locator: String, text: String)
}

/** The real NAS, through [SmbClient]. */
object SmbNasFiles : NasFiles {
    override fun list(config: SmbConfig, folder: String): List<String> = SmbClient.list(config, folder)

    override fun readText(config: SmbConfig, locator: String): String = SmbClient.readText(config, locator)

    override fun writeText(config: SmbConfig, locator: String, text: String) =
        SmbClient.writeText(config, locator, text)
}
