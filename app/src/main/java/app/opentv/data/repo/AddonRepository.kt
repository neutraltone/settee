/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.core.AppDispatchers
import app.opentv.core.AppSettings
import app.opentv.data.model.AddonManifest
import app.opentv.data.model.StremioAddon
import app.opentv.data.remote.AddonFailure
import app.opentv.data.remote.StremioClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The viewer's Stremio add-ons: adding one by the address they typed or pasted, checked against the
 * live add-on first, and the stored list.
 */
class AddonRepository(
    private val settings: AppSettings,
    private val client: StremioClient,
    private val dispatchers: AppDispatchers,
) {
    // Two screens opening at once would otherwise read the same old add-on twice.
    private val rereading = Mutex()

    /** What came of adding an add-on. */
    sealed interface Outcome {
        /** It's in the list, under [name]. */
        data class Accepted(val name: String) : Outcome

        /** It wasn't added, and [failure] is why, for `ErrorWording.ofAddon` to word. */
        data class Refused(val failure: AddonFailure) : Outcome
    }

    /** Checks the add-on at [typed] and keeps it if it is one. */
    suspend fun add(typed: String): Outcome {
        val address = normaliseAddress(typed) ?: return Outcome.Refused(AddonFailure.BadAddress())
        return try {
            val manifest = withContext(dispatchers.io) { client.fetchManifest(address) }
            settings.addStremioAddon(manifest.toAddon(address))
            Outcome.Accepted(manifest.name)
        } catch (failure: AddonFailure) {
            Outcome.Refused(failure)
        }
    }

    /**
     * The stored add-ons, in the viewer's order, each knowing what it serves. One stored before Settee
     * read that is read again here, once; if it can't be read now it's used as it is, and tried again
     * next time.
     */
    suspend fun addons(): List<StremioAddon> = rereading.withLock {
        settings.stremioAddons.value.filter { it.resources == null }.forEach { old ->
            val manifest = try {
                withContext(dispatchers.io) { client.fetchManifest(old.manifestUrl) }
            } catch (_: AddonFailure) {
                return@forEach
            }
            settings.refreshStremioAddon(manifest.toAddon(old.manifestUrl))
        }
        settings.stremioAddons.value
    }

    private fun AddonManifest.toAddon(address: String) =
        StremioAddon(manifestUrl = address, name = name, resources = resources, types = types)

    companion object {
        private const val STREMIO_SCHEME = "stremio://"

        /**
         * The manifest address [typed] stands for, or null when it can't be one. As the provider form
         * does with a provider's address (`SourceRepository.normaliseUrl`), the scheme is optional; and
         * an add-on page's `stremio://` install link is its manifest over https.
         */
        fun normaliseAddress(typed: String): String? {
            val trimmed = typed.trim()
            val withScheme = when {
                trimmed.startsWith(STREMIO_SCHEME, ignoreCase = true) -> HTTPS + trimmed.drop(STREMIO_SCHEME.length)
                "://" !in trimmed -> HTTPS + trimmed
                else -> trimmed
            }
            // An add-on's own page often gives its base address, with or without the slash.
            return withScheme.toHttpUrlOrNull()?.let { url ->
                val path = url.encodedPath
                val manifestPath = if (path.endsWith("/$MANIFEST")) path else path.trimEnd('/') + "/$MANIFEST"
                url.newBuilder().encodedPath(manifestPath).build()
            }?.toString()
        }

        private const val HTTPS = "https://"
        private const val MANIFEST = "manifest.json"
    }
}
