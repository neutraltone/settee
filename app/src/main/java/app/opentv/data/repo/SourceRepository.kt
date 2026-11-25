/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.core.AppDispatchers
import app.opentv.core.ViewerError
import app.opentv.data.db.SourceDao
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.ProviderException
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

class SourceRepository(
    private val dao: SourceDao,
    private val api: XtreamApi,
    private val stalkerApi: StalkerApi,
    private val http: OkHttpClient,
    private val renames: ProviderRenames,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) {
    fun observeAll(): Flow<List<Source>> = dao.observeAll()

    suspend fun enabled(): List<Source> = dao.enabled()

    suspend fun all(): List<Source> = dao.all()

    suspend fun byId(id: Long): Source? = dao.byId(id)

    /** Saves how [sourceId]'s latest channel download went: [failure], or null when it worked. */
    suspend fun saveRefreshResult(sourceId: Long, failure: ViewerError?) =
        dao.setRefreshFailure(sourceId, failure?.stored())

    /**
     * Saves a provider, its name trimmed and made unique. A new name for an existing one takes along
     * everything kept by its name, and is recorded so the other devices rename theirs (ADR 0004).
     */
    suspend fun save(source: Source): Long = withContext(dispatchers.io) {
        renames.naming {
            val normalised = source.copy(url = normaliseUrl(source.url, source.kind), name = uniqueName(source))
            if (source.id == 0L) {
                dao.insert(normalised).also { renames.added() }
            } else {
                renames.edited(normalised)
                source.id
            }
        }
    }

    // Sync matches a title by its source's name (ADR 0004), and provider ids repeat across providers,
    // so two sources sharing a name would swap progress and hidden channels. Leaving the name blank
    // gives every source the same default, so this has to resolve a clash rather than refuse it.
    // Names match as another device matches them (SourceDirectory.sameName). The Add-ons source's name
    // is always taken, whether or not this device has one yet: another device's may arrive by sync.
    private suspend fun uniqueName(source: Source): String {
        val wanted = source.name.trim()
        val taken = dao.all().filter { it.id != source.id }.map { it.name } + Source.ADDONS_NAME
        fun isTaken(name: String) = taken.any { SourceDirectory.sameName(it, name) }
        if (!isTaken(wanted)) return wanted
        return generateSequence(2) { it + 1 }.map { "$wanted $it" }.first { !isTaken(it) }
    }

    /** Checks the details work before the user is committed to them. */
    suspend fun test(source: Source): Result<TestOutcome> = withContext(dispatchers.io) {
        runCatching {
            val normalised = source.copy(url = normaliseUrl(source.url, source.kind))
            when (source.kind) {
                SourceKind.XTREAM -> api.authenticate(normalised).let {
                    TestOutcome.Xtream(it.username, it.maxConnections, it.expiryMillis)
                }
                SourceKind.M3U -> {
                    checkPlaylist(normalised)
                    TestOutcome.PlaylistFound
                }
                SourceKind.STALKER -> {
                    stalkerApi.handshakeTest(normalised)
                    TestOutcome.StalkerAccepted
                }
                // Never offered: the Add-ons source isn't a Provider and has nothing to sign in to.
                SourceKind.ADDONS -> error("The Add-ons source has nothing to test")
            }
        }
    }

    private fun checkPlaylist(source: Source) {
        val request = Request.Builder().url(source.url).header("User-Agent", source.userAgent).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ProviderException(
                    "Playlist answered ${response.code}",
                    problem = ProviderException.Problem.HTTP_STATUS,
                    status = response.code,
                )
            }
            // Only the start: a playlist can be tens of MB, and its first lines are enough to tell it
            // from a sign-in page or an error. The channels themselves are Save's to load.
            val start = Buffer()
            val body = response.body.source()
            while (start.size < PLAYLIST_START_BYTES && body.read(start, PLAYLIST_START_BYTES - start.size) != -1L) Unit
            val text = start.readUtf8()
            if (PLAYLIST_MARKERS.none { it in text }) {
                throw ProviderException("Not a playlist", problem = ProviderException.Problem.NOT_A_PLAYLIST)
            }
        }
    }

    companion object {
        private const val PLAYLIST_START_BYTES = 16L * 1024
        private val PLAYLIST_MARKERS = listOf("#EXTM3U", "#EXTINF")

        /**
         * Users paste all sorts of things. Accept them all rather than making someone guess
         * the format: a trailing slash, a missing scheme, or a full `get.php` URL copied out
         * of a provider's welcome email.
         *
         * For an Xtream source we want the bare host, so API paths are stripped. For an M3U
         * source the URL *is* the playlist — stripping the path there would destroy it, which
         * is why [kind] is not optional.
         *
         * Pure and static so it can be tested without standing up the repository.
         */
        fun normaliseUrl(raw: String, kind: SourceKind): String {
            var url = raw.trim()
            if (url.isEmpty()) return url
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                url = "http://$url"
            }
            if (kind == SourceKind.M3U) return url
            // A Stalker portal URL is the portal path itself (e.g. .../stalker_portal or .../c) —
            // stripping Xtream API paths would be wrong; just tidy the trailing slash.
            if (kind == SourceKind.STALKER) return url.trimEnd('/')

            url = url.substringBefore("/player_api.php")
                .substringBefore("/panel_api.php")
                .substringBefore("/get.php")
                .substringBefore("/xmltv.php")
            return url.trimEnd('/')
        }
    }
}
