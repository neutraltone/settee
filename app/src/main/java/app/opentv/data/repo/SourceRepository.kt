/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.core.AppDispatchers
import app.opentv.core.AppSettings
import app.opentv.core.ViewerError
import app.opentv.data.db.SourceDao
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.ProviderException
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.sync.SyncProviderRename
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

class SourceRepository(
    private val dao: SourceDao,
    private val api: XtreamApi,
    private val stalkerApi: StalkerApi,
    private val http: OkHttpClient,
    private val settings: AppSettings,
    private val dispatchers: AppDispatchers = AppDispatchers(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Name checks read before they write; two saves at once, or a save during a sync's renames, would
    // otherwise let two sources take one name.
    private val names = Mutex()

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
        names.withLock {
            val normalised = source.copy(url = normaliseUrl(source.url, source.kind), name = uniqueName(source))
            if (source.id == 0L) return@withLock dao.insert(normalised)
            val before = dao.byId(source.id)?.name
            dao.updateEdit(normalised)
            if (before != null && before != normalised.name) {
                settings.setProviderRenames(
                    settings.providerRenames() + SyncProviderRename(before, normalised.name, clock()),
                )
                carryLocks(before, normalised.name)
            }
            source.id
        }
    }

    // Sync matches a title by its source's name (ADR 0004), and provider ids repeat across providers,
    // so two sources sharing a name would swap progress and hidden channels. Leaving the name blank
    // gives every source the same default, so this has to resolve a clash rather than refuse it.
    // Names match ignoring case and surrounding spaces, as another device matches them.
    private suspend fun uniqueName(source: Source): String {
        val wanted = source.name.trim()
        val taken = dao.all().filter { it.id != source.id }.map { it.name }
        fun isTaken(name: String) = taken.any { sameName(it, name) }
        if (!isTaken(wanted)) return wanted
        return generateSequence(2) { it + 1 }.map { "$wanted $it" }.first { !isTaken(it) }
    }

    /** Every provider rename made here or heard of, as they travel to the other side. */
    fun renames(): List<SyncProviderRename> = settings.providerRenames()

    /** This device's providers by name, following the renames known here, for labelling a bundle's items. */
    internal suspend fun directory(): SourceDirectory = dao.directory(settings.providerRenames())

    /**
     * Applies the provider renames from another device this one hasn't seen, oldest first and each only
     * once, and keeps them to pass on. A rename is skipped where no provider here has its old name,
     * where another already has the new one (two providers can't become one), and where this device
     * named its provider after the rename was made, since its viewer chose that name later.
     */
    suspend fun importRenames(renames: List<SyncProviderRename>) = withContext(dispatchers.io) {
        names.withLock {
            val fresh = renames.filter { it.to.isNotBlank() }.distinct() - settings.providerRenames().toSet()
            for (rename in fresh.sortedBy { it.atMillis }) {
                applyRename(rename)
                settings.setProviderRenames(settings.providerRenames() + rename)
            }
        }
    }

    private suspend fun applyRename(rename: SyncProviderRename) {
        val sources = dao.all()
        val from = sources.firstOrNull { sameName(it.name, rename.from) } ?: return
        val to = rename.to.trim()
        val namedHereAt = settings.providerRenames().filter { sameName(it.to, from.name) }.maxOfOrNull { it.atMillis }
        val clash = sources.any { it.id != from.id && sameName(it.name, to) }
        if (clash || from.name == to || (namedHereAt != null && namedHereAt > rename.atMillis)) return
        dao.rename(from.id, to)
        carryLocks(from.name, to)
    }

    // The database's rows keyed by a provider's name move with SourceDao.carryName; Parental Lock's
    // keys for nameless categories hold the name too, and live in settings.
    private fun carryLocks(from: String, to: String) = settings.rekeyCategoryLocks { key ->
        NamelessCategory.relabelled(key) { name -> if (name == from) to else name }
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
        /** Whether two provider names are the same provider's: ignoring case and surrounding spaces. */
        fun sameName(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

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
