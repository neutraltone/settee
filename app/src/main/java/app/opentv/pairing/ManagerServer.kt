/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import android.util.Log
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.ServerProblem
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.shownName
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.data.repo.ChannelLineup
import app.opentv.data.repo.SourceRepository
import app.opentv.recording.SmbClient
import app.opentv.recording.SmbConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Serves a "manage your channels from your phone or laptop" web app off this television.
 *
 * ## Same ethos as [PairingServer]
 *
 * There is no cloud. The TV runs a tiny HTTP server on the local network for as long as the manage
 * screen is open; a phone or laptop on the same wifi opens a real page — with a keyboard and mouse
 * instead of a D-pad — and browses, hides, favourites, renames and reorders channels. Every change
 * writes straight to this device's database. It works with the internet unplugged.
 *
 * ## What stops the neighbours using it
 *
 * - The address comes from [PhoneAddress], the same as setup's: a **random high port** on the LAN,
 *   open only while the manage screen is, a QR code carrying a **192-bit random token** that every
 *   data route checks, and a typeable address with a **seven-character short token** that only
 *   opens the page (which carries the long token) and switches itself off after
 *   [PhoneAddress.MAX_SHORT_TOKEN_GUESSES] wrong guesses, while the QR keeps working. Both are
 *   only ever shown on the TV, so only someone who can see the telly can connect, which is the
 *   whole security model. Unlike pairing there is no six-digit code, because this handles no
 *   credentials and the point is to be frictionless.
 * - Requests from non-private addresses are refused outright.
 * - Request bodies are capped ([MAX_BODY_BYTES]).
 * - Unlike pairing (which closes after the first submit) this keeps serving while the screen is
 *   open, and only closes itself after [IDLE_TIMEOUT_MINUTES] with no requests, or on [stop].
 */
class ManagerServer(
    private val scope: CoroutineScope,
    private val catalog: CatalogRepository,
    private val settings: AppSettings,
    private val sourceRepository: SourceRepository,
    private val lineup: ChannelLineup,
    private val refresh: CatalogueRefresh,
) {
    /** Words a failed test or add for the page; it never sends an exception's own message. */
    private val wording get() = catalog.wording

    data class Session(
        /** What the QR code encodes, e.g. `http://192.168.0.14:41234/?t=…` */
        val url: String,
        val host: String,
        val port: Int,
        /** The path of [shortUrl]: seven characters that can't be misread for each other. */
        val shortToken: String,
    ) {
        /** The address to type if the QR will not scan, e.g. `192.168.0.14:41234/k7mx4ht`. */
        val shortUrl: String get() = "$host:$port/$shortToken"
    }

    sealed interface State {
        data object Idle : State
        data class Listening(val session: Session) : State

        /** [code] is the exception's type, for the detail line, when one was behind it. */
        data class Failed(val problem: ServerProblem, val code: String? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    // Written by start()/stop() on the main thread, read by the accept thread.
    @Volatile private var address: PhoneAddress? = null

    @Volatile private var running = false

    @Volatile private var lastActivityMillis = 0L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun start() {
        if (running) return
        val opened = when (val opening = PhoneAddress.open()) {
            is PhoneAddress.Opening.Opened -> opening.address
            PhoneAddress.Opening.NoNetwork -> {
                _state.value = State.Failed(ServerProblem.NO_NETWORK)
                return
            }
            is PhoneAddress.Opening.NoSocket -> {
                _state.value = State.Failed(ServerProblem.COULDNT_START, opening.code)
                return
            }
        }

        address = opened
        running = true
        lastActivityMillis = System.currentTimeMillis()

        _state.value = State.Listening(
            Session(url = opened.url, host = opened.host, port = opened.port, shortToken = opened.shortToken),
        )

        // A plain thread rather than a coroutine: ServerSocket.accept() blocks uninterruptibly,
        // and the only reliable way to stop it is to close the socket.
        thread(name = "opentv-manager", isDaemon = true) {
            while (running) {
                val client = try {
                    opened.accept()
                } catch (_: Exception) {
                    break // socket closed, which is how we stop
                }
                // Each request on its own, so a slow one (a reorder, an add that tests a login)
                // doesn't hold up the page's next request behind it on this loop.
                scope.launch(Dispatchers.IO) {
                    runCatching { handle(client) }
                        .onFailure { Log.w(TAG, "Manager request failed", it) }
                    runCatching { client.close() }
                }
            }
        }

        // Idle watchdog: keep serving while the screen is open, but do not leave a socket on the
        // user's network for hours if they wander off. Closes after a long unused stretch.
        scope.launch(Dispatchers.Default) {
            while (running) {
                delay(TimeUnit.MINUTES.toMillis(1))
                val idleFor = System.currentTimeMillis() - lastActivityMillis
                if (running && idleFor > TimeUnit.MINUTES.toMillis(IDLE_TIMEOUT_MINUTES)) {
                    stop()
                    _state.value = State.Failed(ServerProblem.TIMED_OUT)
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        address?.close()
        address = null
        if (_state.value is State.Listening) _state.value = State.Idle
    }

    // ---- Request handling ----------------------------------------------------------------------

    private fun handle(client: Socket) {
        client.soTimeout = SOCKET_TIMEOUT_MILLIS

        if (!PairingHttp.isPrivateAddress(client.inetAddress?.hostAddress)) {
            client.getOutputStream().write(
                PairingHttp.httpResponse("403 Forbidden", "text/plain", "Local network only."),
            )
            return
        }

        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val (method, path, query) = PairingHttp.parseRequestLine(requestLine) ?: return

        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            PairingHttp.parseHeader(line)?.let { (name, value) -> headers[name] = value }
        }

        val output = client.getOutputStream()

        // Every route checks a token. Without one, none of this exists. The typed address only
        // opens the page, which carries the long token for everything after.
        val address = address ?: return
        val check = address.check(method, path, query["t"])
        if (check is PhoneAddress.Check.Refused) {
            output.write(check.response())
            return
        }
        val token = address.token

        // A valid request counts as activity; the idle watchdog measures from here.
        lastActivityMillis = System.currentTimeMillis()

        val body = if (method == "POST") readBody(reader, headers) else ""

        when {
            check == PhoneAddress.Check.Typed || (method == "GET" && path == "/") ->
                output.write(PairingHttp.httpResponse("200 OK", "text/html", ManagerPage.page(token)))

            method == "GET" && path == "/meta" ->
                output.write(PairingHttp.httpResponse("200 OK", "application/json", metaJson()))

            method == "GET" && path == "/channels" ->
                output.write(PairingHttp.httpResponse("200 OK", "application/json", channelsJson(query)))

            method == "POST" && path == "/channel" -> handleChannelPatch(output, body)

            method == "POST" && path == "/reorder" -> handleReorder(output, body)

            method == "GET" && path == "/recording" ->
                output.write(PairingHttp.httpResponse("200 OK", "application/json", recordingJson()))

            method == "POST" && path == "/recording" -> handleRecording(output, body)

            method == "POST" && path == "/recording/test" -> handleRecordingTest(output, body)

            method == "POST" && path == "/source" -> handleAddSource(output, body)

            method == "POST" && path == "/source/test" -> handleTestSource(output, body)

            else -> output.write(PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found."))
        }
    }

    private fun readBody(reader: BufferedReader, headers: Map<String, String>): String {
        val contentLength = (headers["content-length"]?.toIntOrNull() ?: 0)
            .coerceAtMost(MAX_BODY_BYTES)
        if (contentLength <= 0) return ""
        val buffer = CharArray(contentLength)
        val read = reader.read(buffer, 0, contentLength)
        return if (read > 0) String(buffer, 0, read) else ""
    }

    // ---- Routes --------------------------------------------------------------------------------

    /** `{ sources:[{id,name}], categories:[{id,label,sourceId,count}] }` */
    private fun metaJson(): String = runBlocking {
        val sources = catalog.enabledSources()
        val sourceIds = sources.map { it.id }.toSet()
        val counts = catalog.categoryChannelCounts()
            .associateBy({ it.sourceId to it.categoryId }, { it.count })
        // Only categories that belong to an enabled source AND actually hold channels — an empty
        // category is noise on a phone, and a category count includes hidden rows so it stays > 0
        // for a category whose channels are all hidden (which is exactly what you'd browse to fix).
        val categories = catalog.liveCategories()
            .filter { it.sourceId in sourceIds }
            .mapNotNull { cat ->
                val count = counts[cat.sourceId to cat.id] ?: 0
                if (count <= 0) {
                    null
                } else {
                    CategoryDto(id = cat.id, label = cat.name, sourceId = cat.sourceId, count = count)
                }
            }
        json.encodeToString(
            MetaDto.serializer(),
            MetaDto(sources = sources.map { SourceDto(it.id, it.name) }, categories = categories),
        )
    }

    /** `GET /channels?cat=<id>[&source=<id>]` → the category's channels, hidden included. */
    private fun channelsJson(query: Map<String, String>): String = runBlocking {
        val serializer = ListSerializer(ChannelDto.serializer())
        val cat = query["cat"]
        if (cat.isNullOrBlank()) return@runBlocking json.encodeToString(serializer, emptyList())
        val source = query["source"]?.toLongOrNull()
        // Stars show the Favourites of whichever Profile is active on the TV, a whole Channel at a time.
        val favourites = lineup.favouriteRowIds()
        val rows = catalog.channelsInCategoryForManager(source, cat).map { it.toDto(it.id in favourites) }
        json.encodeToString(serializer, rows)
    }

    /** `POST /channel {id, hidden?|favourite?|name?}` → apply the given field(s). */
    private fun handleChannelPatch(output: OutputStream, body: String) {
        val patch = runCatching { json.decodeFromString(ChannelPatch.serializer(), body) }.getOrNull()
        if (patch == null || patch.id <= 0L) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Bad request."))
            return
        }
        // Unknown ids simply update zero rows — harmless, so no existence check.
        runBlocking {
            patch.hidden?.let { catalog.setChannelHidden(patch.id, it) }
            // Every Quality follows, as when favouriting from the guide.
            patch.favourite?.let { favourite ->
                lineup.channelOf(patch.id, manage = true)?.let { lineup.setFavourite(it, favourite) }
            }
            // A blank name clears the rename back to the provider's own (see setChannelCustomName).
            patch.name?.let { catalog.setChannelCustomName(patch.id, it) }
        }
        writeOk(output)
    }

    /** `POST /reorder {ids:[orderedChannelIds]}` → assign sortIndex by position, in one write. */
    private fun handleReorder(output: OutputStream, body: String) {
        val req = runCatching { json.decodeFromString(ReorderReq.serializer(), body) }.getOrNull()
        if (req == null) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Bad request."))
            return
        }
        runBlocking {
            // Ids ≤ 0 keep their position but match no channel, so nothing moves to it.
            catalog.setChannelOrder(req.ids)
        }
        writeOk(output)
    }

    private fun writeOk(output: OutputStream) {
        output.write(
            PairingHttp.httpResponse("200 OK", "application/json", json.encodeToString(OkDto.serializer(), OkDto())),
        )
    }

    /** `GET /recording` → the current recording target and NAS settings. The password is never sent
     *  back to the browser — only whether one is set. */
    private fun recordingJson(): String = json.encodeToString(
        RecordingDto.serializer(),
        RecordingDto(
            target = settings.recordingTarget.value.name,
            host = settings.smbHost.value,
            share = settings.smbShare.value,
            folder = settings.smbFolder.value,
            user = settings.smbUser.value,
            hasPassword = settings.smbPassword.value.isNotEmpty(),
        ),
    )

    /** `POST /recording {host,share,folder,user,password?}` → save the NAS details and switch
     *  recordings to it. A blank/absent password keeps the one already stored, so the fields can be
     *  filled without re-typing an existing password. */
    private fun handleRecording(output: OutputStream, body: String) {
        val req = runCatching { json.decodeFromString(RecordingReq.serializer(), body) }.getOrNull()
        if (req == null) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Bad request."))
            return
        }
        val password = req.password?.takeIf { it.isNotEmpty() } ?: settings.smbPassword.value
        settings.setSmbConfig(req.host.trim(), req.share.trim(), req.folder.trim(), req.user.trim(), password)
        settings.setRecordingTarget(AppSettings.RecordingTarget.SMB)
        writeOk(output)
    }

    /** `POST /recording/test {host,share,folder,user,password?}` → actually open the SMB share and
     *  report whether it worked. Blank fields fall back to what's stored, so the browser can test
     *  the saved settings without re-sending the password. Runs on this handler thread (already off
     *  the main thread), which is where a blocking network probe belongs. */
    private fun handleRecordingTest(output: OutputStream, body: String) {
        val req = runCatching { json.decodeFromString(RecordingReq.serializer(), body) }.getOrNull()
            ?: RecordingReq()
        val cfg = SmbConfig(
            host = req.host.trim().ifBlank { settings.smbHost.value },
            share = req.share.trim().ifBlank { settings.smbShare.value },
            folder = req.folder.trim().ifBlank { settings.smbFolder.value },
            username = req.user.trim().ifBlank { settings.smbUser.value },
            password = req.password?.takeIf { it.isNotEmpty() } ?: settings.smbPassword.value,
        )
        val result = runCatching { SmbClient.test(cfg) }
        val dto = if (result.isSuccess) {
            TestDto(ok = true, error = null)
        } else {
            val reason = wording.of(result.exceptionOrNull()!!, R.string.recset_status_failed)
            TestDto(ok = false, error = reason.sentence, detail = reason.detail)
        }
        output.write(
            PairingHttp.httpResponse("200 OK", "application/json", json.encodeToString(TestDto.serializer(), dto)),
        )
    }

    // ---- Add a provider ------------------------------------------------------------------------

    /**
     * `POST /source {kind,name,url,username?,password?,mac?}` → save the provider and pull its live
     * channels, so the catalogue fills without anyone typing a server address on the TV remote.
     * Mirrors the on-device Add-source screen: save, then load live channels, with the guide and
     * series rules following in the background.
     */
    private fun handleAddSource(output: OutputStream, body: String) {
        val draft = runCatching { json.decodeFromString(SourceReq.serializer(), body) }.getOrNull()?.toDraftOrNull()
        if (draft == null) {
            writeJson(
                output,
                AddResultDto.serializer(),
                AddResultDto(ok = false, error = "Fill in the required fields for this provider type."),
            )
            return
        }
        val result = runBlocking {
            val id = sourceRepository.save(draft)
            val saved = sourceRepository.byId(id)
                ?: return@runBlocking AddResultDto(
                    ok = false,
                    error = wording.of(R.string.refresh_save_failed).sentence,
                )
            when (val r = refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(saved))) {
                is CatalogueRefresh.Outcome.ChannelsLoaded -> AddResultDto(ok = true, channels = r.channels)
                is CatalogueRefresh.Outcome.AddFailed ->
                    AddResultDto(ok = false, error = r.reason.sentence, detail = r.reason.detail)
            }
        }
        writeJson(output, AddResultDto.serializer(), result)
    }

    /** `POST /source/test {…}` → validate the provider details without saving (handshake / auth). */
    private fun handleTestSource(output: OutputStream, body: String) {
        val draft = runCatching { json.decodeFromString(SourceReq.serializer(), body) }.getOrNull()?.toDraftOrNull()
        if (draft == null) {
            writeJson(
                output,
                TestResultDto.serializer(),
                TestResultDto(ok = false, error = "Fill in the required fields for this provider type."),
            )
            return
        }
        val result = runBlocking { sourceRepository.test(draft) }
        val dto = if (result.isSuccess) {
            TestResultDto(ok = true, message = result.getOrNull())
        } else {
            val reason = wording.ofProvider(result.exceptionOrNull()!!, R.string.error_test_failed)
            TestResultDto(ok = false, error = reason.sentence, detail = reason.detail)
        }
        writeJson(output, TestResultDto.serializer(), dto)
    }

    /** Turn a web request into a [Source] draft, or null if a field required for its kind is missing. */
    private fun SourceReq.toDraftOrNull(): Source? {
        val k = runCatching { SourceKind.valueOf(kind.trim().uppercase()) }.getOrNull() ?: return null
        if (url.isBlank()) return null
        when (k) {
            SourceKind.XTREAM -> if (username.isNullOrBlank() || password.isNullOrBlank()) return null
            SourceKind.STALKER -> if (mac.isNullOrBlank()) return null
            SourceKind.M3U -> Unit
        }
        return Source(
            name = name.trim().ifBlank { if (k == SourceKind.M3U) "Playlist" else "Provider" },
            kind = k,
            url = url.trim(),
            username = username?.takeIf { it.isNotBlank() },
            password = password?.takeIf { it.isNotBlank() },
            macAddress = mac?.takeIf { it.isNotBlank() },
            userAgent = Source.DEFAULT_USER_AGENT,
        )
    }

    private fun <T> writeJson(output: OutputStream, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        output.write(PairingHttp.httpResponse("200 OK", "application/json", json.encodeToString(serializer, value)))
    }

    private fun Channel.toDto(favourite: Boolean) = ChannelDto(
        id = id,
        name = shownName,
        original = displayName,
        hidden = hidden,
        favourite = favourite,
        sortIndex = sortIndex,
        logo = logoUrl,
        number = number,
    )

    private companion object {
        const val TAG = "ManagerServer"
        const val SOCKET_TIMEOUT_MILLIS = 15_000
        const val IDLE_TIMEOUT_MINUTES = 30L

        // Generous next to pairing's 16 KB: a /reorder body carries every channel id in a
        // category, which can be a few thousand on a big provider.
        const val MAX_BODY_BYTES = 256 * 1024
    }
}

// ---- JSON DTOs ---------------------------------------------------------------------------------
// kotlinx.serialization handles the escaping that matters here: channel names carry quotes, `|`
// and unicode, and hand-rolled string concatenation is exactly where that goes wrong.

@Serializable private data class MetaDto(val sources: List<SourceDto>, val categories: List<CategoryDto>)

@Serializable private data class SourceDto(val id: Long, val name: String)

@Serializable private data class CategoryDto(val id: String, val label: String, val sourceId: Long, val count: Int)

@Serializable private data class ChannelDto(
    val id: Long,
    val name: String,
    val original: String,
    val hidden: Boolean,
    val favourite: Boolean,
    val sortIndex: Int,
    val logo: String?,
    val number: Int?,
)

@Serializable private data class OkDto(val ok: Boolean = true)

@Serializable private data class ChannelPatch(
    val id: Long,
    val hidden: Boolean? = null,
    val favourite: Boolean? = null,
    val name: String? = null,
)

@Serializable private data class ReorderReq(val ids: List<Long>)

@Serializable private data class RecordingDto(
    val target: String,
    val host: String,
    val share: String,
    val folder: String,
    val user: String,
    val hasPassword: Boolean,
)

@Serializable private data class RecordingReq(
    val host: String = "",
    val share: String = "",
    val folder: String = "",
    val user: String = "",
    val password: String? = null,
)

@Serializable private data class TestDto(val ok: Boolean, val error: String?, val detail: String? = null)

@Serializable private data class SourceReq(
    val kind: String = "xtream",
    val name: String = "",
    val url: String = "",
    val username: String? = null,
    val password: String? = null,
    val mac: String? = null,
)

@Serializable private data class AddResultDto(
    val ok: Boolean,
    val channels: Int = 0,
    val error: String? = null,
    val detail: String? = null,
)

@Serializable private data class TestResultDto(
    val ok: Boolean,
    val message: String? = null,
    val error: String? = null,
    val detail: String? = null,
)
