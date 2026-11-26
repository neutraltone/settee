/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What a stream the player couldn't read turned out to start with. */
enum class StreamStart {
    /** HTML: an expired account's page, a blocked link's, or a Wi-Fi sign-in page. */
    WEB_PAGE,

    /** Anything else: a file, most likely, in a container the player has no extractor for. */
    SOMETHING_ELSE,
}

/**
 * Looks at the first bytes of a stream the player has already failed to read, so [PlaybackErrors] can tell a web
 * page from a file it can't play (both fail as an unsupported container or manifest).
 *
 * The bytes are read rather than the Content-Type trusted: #462 turned down a text/html check because some panels
 * label real streams as HTML, and it would have stopped those before they played. Asked only after the player
 * has failed, the bytes can't stop anything playing, and say what was sent whatever it was labelled.
 */
internal object StreamPeek {

    /**
     * Fetches the start of [url] with [headers], the ones the stream itself was asked for with (an add-on's, or
     * the Provider's User-Agent), since a host that checks them would answer differently without. Null when it
     * couldn't be read. Never logs [url]: it can hold a login.
     */
    suspend fun startOf(client: OkHttpClient, url: String, headers: Map<String, String>): StreamStart? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (name, value) -> header(name, value) }
                    header("Range", "bytes=0-${PEEK_BYTES - 1}")
                }.build()
                client.newBuilder().callTimeout(PEEK_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
                    .newCall(request).execute().use { response ->
                        val source = response.body.source()
                        source.request(PEEK_BYTES.toLong())
                        val bytes = source.buffer.readByteArray(minOf(source.buffer.size, PEEK_BYTES.toLong()))
                        if (looksLikeWebPage(bytes)) StreamStart.WEB_PAGE else StreamStart.SOMETHING_ELSE
                    }
            }.getOrNull()
        }

    /** Whether [bytes] start as HTML does, after any byte order mark and blank space, in any case. */
    fun looksLikeWebPage(bytes: ByteArray): Boolean {
        val text = String(bytes, Charsets.ISO_8859_1).removePrefix(UTF8_BOM).trimStart().lowercase(Locale.ROOT)
        return HTML_STARTS.any(text::startsWith)
    }

    /** Plenty for a page's start past a byte order mark and blank lines, and a few packets of a stream. */
    private const val PEEK_BYTES = 512

    /** The player has already given up: the viewer waits on this, so not for long. */
    private const val PEEK_TIMEOUT_SECONDS = 5L

    /** UTF-8's byte order mark, as the three Latin-1 characters its bytes read as. */
    private const val UTF8_BOM = "ï»¿"

    private val HTML_STARTS = listOf("<!doctype", "<html")
}
