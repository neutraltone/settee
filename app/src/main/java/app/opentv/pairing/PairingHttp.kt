/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * The pure, testable half of the phone-pairing server.
 *
 * Request parsing and form decoding live here, apart from sockets, so they can be tested
 * without standing up a server or a device. Everything here is a pure function, except
 * [readHead] and [Head.readBody], which read from any stream rather than a socket.
 */
object PairingHttp {

    /**
     * The most a request's line and headers may take. A phone's are a kilobyte or two; without a
     * limit, anything on the network trickling bytes with no line break would grow the TV's memory
     * until the app fell over, since only the body had a cap.
     */
    const val MAX_HEAD_BYTES = 32 * 1024

    /** A request's line and headers. Its body stays unread until [readBody] asks for it. */
    class Head internal constructor(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        private val stream: RequestStream,
    ) {
        /**
         * Reads the body to `Content-Length` bytes, or [maxBodyBytes] if that's fewer, and only
         * then decodes it, since `Content-Length` counts bytes, not characters. A stream that ends
         * early, or times out, gives what arrived, marked incomplete; so does a body cut at the cap.
         */
        fun readBody(maxBodyBytes: Int): Body {
            val declared = (headers["content-length"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
            val read = stream.readBody(minOf(declared, maxBodyBytes.toLong()).toInt())
            return Body(read.toString(Charsets.UTF_8), complete = read.size.toLong() == declared)
        }
    }

    /**
     * A request's body as it arrived. [complete] is false when fewer bytes came than
     * `Content-Length` promised: a form cut off partway would otherwise be taken at its word,
     * and a truncated password saved as if it were the whole one.
     */
    data class Body(val text: String, val complete: Boolean)

    /**
     * Reads a request's line and headers from [input], leaving the body for [Head.readBody], so a
     * server can turn a request away before reading what it sent. Null if the stream ends first,
     * the first line isn't a request line, or the head is longer than [MAX_HEAD_BYTES].
     *
     * The head is read as bytes, not through a `BufferedReader`, because a reader pulls the start
     * of the body into its own buffer, and a single read of the rest then returns only what has
     * arrived so far: a `/reorder` of a big group, spread over several TCP segments, was cut short
     * (#358).
     */
    fun readHead(input: InputStream): Head? {
        val stream = RequestStream(input)
        val (method, path, query) = stream.readLine()?.let(::parseRequestLine) ?: return null
        val headers = generateSequence { stream.readLine() }
            .takeWhile { it.isNotEmpty() }
            .mapNotNull(::parseHeader)
            .toMap()
        return if (stream.headTooLong) null else Head(method, path, query, headers, stream)
    }

    /** Parses a request line such as `GET /pair?t=abc HTTP/1.1`. Null if it is not one. */
    fun parseRequestLine(line: String): Triple<String, String, Map<String, String>>? {
        val parts = line.trim().split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = parseFormEncoded(target.substringAfter('?', ""))
        return Triple(method, path, query)
    }

    /**
     * Decodes `application/x-www-form-urlencoded` data, used for both query strings and
     * POST bodies.
     *
     * Tolerant on purpose: a malformed pair is skipped rather than throwing, because the
     * only thing on the other end is a form the user is typing into on a phone.
     */
    fun parseFormEncoded(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            if (key.isBlank()) return@mapNotNull null
            runCatching {
                decode(key) to decode(value)
            }.getOrNull()
        }.toMap()
    }

    private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%20"), "UTF-8")

    /** Parses one header line into a lowercased name and its value. */
    fun parseHeader(line: String): Pair<String, String>? {
        val index = line.indexOf(':')
        if (index <= 0) return null
        return line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
    }

    /**
     * Constant-time-ish comparison for the pairing token.
     *
     * The token is only valid for a few minutes on a local network, so this is not load
     * bearing — but comparing secrets with `==` is a habit worth not forming.
     */
    fun secretsMatch(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var difference = 0
        for (i in a.indices) {
            difference = difference or (a[i].code xor b[i].code)
        }
        return difference == 0
    }

    /**
     * True for addresses on a private network.
     *
     * The server binds to the LAN, so a request from a public address should be impossible.
     * Checking anyway costs nothing and means a misconfigured router cannot turn this into
     * an internet-facing form that accepts IPTV credentials.
     */
    fun isPrivateAddress(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val address = host.substringAfterLast('%') // strip IPv6 zone id
        if (address == "127.0.0.1" || address == "::1" || address == "0:0:0:0:0:0:0:1") return true

        val octets = address.split('.')
        if (octets.size != 4) {
            // IPv6 unique-local (fc00::/7) and link-local (fe80::/10).
            val lower = address.lowercase()
            return lower.startsWith("fd") || lower.startsWith("fc") || lower.startsWith("fe80")
        }
        val numbers = octets.map { it.toIntOrNull() ?: return false }
        return when {
            numbers[0] == 10 -> true
            numbers[0] == 192 && numbers[1] == 168 -> true
            numbers[0] == 172 && numbers[1] in 16..31 -> true
            numbers[0] == 169 && numbers[1] == 254 -> true
            // 100.64.0.0/10 — CGNAT range, which is what Tailscale hands out. Treat it as private
            // so device sync works over a tailnet (advertised as an address and accepted as a peer).
            numbers[0] == 100 && numbers[1] in 64..127 -> true
            else -> false
        }
    }

    fun httpResponse(status: String, contentType: String, body: String): ByteArray =
        httpResponse(status, "$contentType; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    /** A response of raw [bytes], such as a font file. */
    fun httpResponse(status: String, contentType: String, bytes: ByteArray): ByteArray {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            // This page exists for a few minutes on a private network and handles
            // credentials. Nothing about it should be cached or indexed anywhere.
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("\r\n")
        }
        return head.toByteArray(Charsets.US_ASCII) + bytes
    }
}

/** The bytes of one request, read as [PairingHttp.readHead] and [PairingHttp.Head.readBody] need them. */
internal class RequestStream(input: InputStream) {
    private val stream = input as? BufferedInputStream ?: BufferedInputStream(input)
    private var headBytes = 0

    /** True once the head ran past [PairingHttp.MAX_HEAD_BYTES]; [readLine] gives nothing more. */
    var headTooLong = false
        private set

    /** One line of the head, without its CRLF (or bare LF). Null if the stream has ended or the head is too long. */
    fun readLine(): String? {
        val line = ByteArrayOutputStream()
        var byte = nextHeadByte()
        while (byte != -1 && byte != '\n'.code) {
            line.write(byte)
            byte = nextHeadByte()
        }
        if (headTooLong || (byte == -1 && line.size() == 0)) return null
        return line.toString(Charsets.UTF_8.name()).removeSuffix("\r")
    }

    private fun nextHeadByte(): Int {
        if (headBytes >= PairingHttp.MAX_HEAD_BYTES) headTooLong = true
        if (headTooLong) return -1
        headBytes++
        return stream.read()
    }

    /** Up to [length] bytes of body: all of them, or as many as came before the stream ended or timed out. */
    fun readBody(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var filled = 0
        try {
            while (filled < length) {
                val read = stream.read(buffer, filled, length - filled)
                if (read == -1) break
                filled += read
            }
        } catch (_: SocketTimeoutException) {
            // The phone stopped sending: answer with what arrived rather than not at all.
        }
        return buffer.copyOf(filled)
    }
}
