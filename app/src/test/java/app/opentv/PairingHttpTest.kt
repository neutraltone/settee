/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.pairing.PairingHttp
import com.google.common.truth.Truth.assertThat
import java.io.InputStream
import java.io.SequenceInputStream
import org.junit.Test

/**
 * The pairing server accepts IPTV credentials over the network. Its parsing and its access
 * checks are the parts worth testing hardest — everything else is sockets.
 */
class PairingHttpTest {

    @Test
    fun `parses a request line with a query string`() {
        val parsed = PairingHttp.parseRequestLine("GET /?t=abc123 HTTP/1.1")

        assertThat(parsed).isNotNull()
        val (method, path, query) = parsed!!
        assertThat(method).isEqualTo("GET")
        assertThat(path).isEqualTo("/")
        assertThat(query["t"]).isEqualTo("abc123")
    }

    @Test
    fun `parses a request line without a query string`() {
        val (method, path, query) = PairingHttp.parseRequestLine("POST /submit HTTP/1.1")!!

        assertThat(method).isEqualTo("POST")
        assertThat(path).isEqualTo("/submit")
        assertThat(query).isEmpty()
    }

    @Test
    fun `rubbish request lines are rejected rather than throwing`() {
        assertThat(PairingHttp.parseRequestLine("")).isNull()
        assertThat(PairingHttp.parseRequestLine("GET")).isNull()
    }

    @Test
    fun `decodes form bodies including the awkward characters in passwords`() {
        // Real provider passwords contain +, &, = and spaces, and every one of them means
        // something else in a form body.
        val body = "username=lee%40example.com&password=p%40ss%2Bw%26rd%3D1&name=Living+Room"

        val form = PairingHttp.parseFormEncoded(body)

        assertThat(form["username"]).isEqualTo("lee@example.com")
        assertThat(form["password"]).isEqualTo("p@ss+w&rd=1")
        assertThat(form["name"]).isEqualTo("Living Room")
    }

    @Test
    fun `a malformed pair does not discard the rest of the form`() {
        val form = PairingHttp.parseFormEncoded("a=1&&=nokey&b=2")

        assertThat(form["a"]).isEqualTo("1")
        assertThat(form["b"]).isEqualTo("2")
    }

    @Test
    fun `empty values are preserved rather than dropped`() {
        val form = PairingHttp.parseFormEncoded("epg=&url=http%3A%2F%2Fx.com")

        assertThat(form["epg"]).isEmpty()
        assertThat(form["url"]).isEqualTo("http://x.com")
    }

    @Test
    fun `header names are lowercased`() {
        assertThat(PairingHttp.parseHeader("Content-Length: 42"))
            .isEqualTo("content-length" to "42")
        assertThat(PairingHttp.parseHeader("not a header")).isNull()
    }

    @Test
    fun `secret comparison rejects mismatches and length differences`() {
        assertThat(PairingHttp.secretsMatch("abc123", "abc123")).isTrue()
        assertThat(PairingHttp.secretsMatch("abc123", "abc124")).isFalse()
        assertThat(PairingHttp.secretsMatch("abc", "abc123")).isFalse()
        assertThat(PairingHttp.secretsMatch("", "")).isTrue()
    }

    @Test
    fun `private addresses are recognised`() {
        assertThat(PairingHttp.isPrivateAddress("192.168.0.14")).isTrue()
        assertThat(PairingHttp.isPrivateAddress("10.0.0.5")).isTrue()
        assertThat(PairingHttp.isPrivateAddress("172.16.4.1")).isTrue()
        assertThat(PairingHttp.isPrivateAddress("172.31.255.254")).isTrue()
        assertThat(PairingHttp.isPrivateAddress("169.254.10.1")).isTrue()
        assertThat(PairingHttp.isPrivateAddress("127.0.0.1")).isTrue()
        assertThat(PairingHttp.isPrivateAddress("fd00::1")).isTrue()
    }

    @Test
    fun `public addresses are refused`() {
        // The pairing form takes IPTV credentials. If a router ever exposes it, the request
        // must be turned away rather than served.
        assertThat(PairingHttp.isPrivateAddress("8.8.8.8")).isFalse()
        assertThat(PairingHttp.isPrivateAddress("172.32.0.1")).isFalse()
        assertThat(PairingHttp.isPrivateAddress("172.15.0.1")).isFalse()
        assertThat(PairingHttp.isPrivateAddress("193.168.0.1")).isFalse()
        assertThat(PairingHttp.isPrivateAddress("2606:4700::1")).isFalse()
        assertThat(PairingHttp.isPrivateAddress(null)).isFalse()
        assertThat(PairingHttp.isPrivateAddress("")).isFalse()
    }

    @Test
    fun `responses carry a correct byte length for multibyte content`() {
        val response = PairingHttp.httpResponse("200 OK", "text/html", "café ☕")
        val text = String(response, Charsets.UTF_8)

        // Content-Length is bytes, not characters. Getting this wrong truncates the page in
        // exactly the sort of way that only shows up for people whose provider name has an
        // accent in it.
        assertThat(text).contains("Content-Length: ${"café ☕".toByteArray(Charsets.UTF_8).size}")
        assertThat(text).contains("no-store")
    }

    @Test
    fun `a body that arrives in several pieces is read whole`() {
        val body = """{"ids":[1,2,3,4,5,6]}"""
        // The first piece carries the headers and the start of the body together, as TCP often does.
        val stream = Pieces(
            "POST /reorder?t=abc HTTP/1.1\r\nContent-Length: ${body.length}\r\n\r\n{\"ids\":[1,",
            "2,3,",
            "4,5,",
            "6]}",
        )

        val read = PairingHttp.readHead(stream)?.readBody(maxBodyBytes = 1024)

        assertThat(read).isEqualTo(PairingHttp.Body(body, complete = true))
    }

    @Test
    fun `non-ASCII text comes back intact when Content-Length counts its bytes`() {
        val body = "Ünïcödé 📺"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "POST /channel HTTP/1.1\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.UTF_8)
        // Split inside the television's four bytes, so a piece ends partway through a character.
        val stream = Pieces(head + bytes.copyOfRange(0, bytes.size - 2), bytes.copyOfRange(bytes.size - 2, bytes.size))

        val read = PairingHttp.readHead(stream)?.readBody(maxBodyBytes = 1024)

        assertThat(read).isEqualTo(PairingHttp.Body(body, complete = true))
    }

    @Test
    fun `a body longer than the cap is cut at the cap, and says it isn't all there`() {
        val stream = Pieces("POST /reorder HTTP/1.1\r\nContent-Length: 100\r\n\r\n", "a".repeat(100))

        val read = PairingHttp.readHead(stream)?.readBody(maxBodyBytes = 10)

        assertThat(read).isEqualTo(PairingHttp.Body("aaaaaaaaaa", complete = false))
    }

    @Test
    fun `a stream that ends before Content-Length gives back what arrived, and says it isn't all there`() {
        val stream = Pieces("POST /reorder HTTP/1.1\r\nContent-Length: 50\r\n\r\nshort")

        val read = PairingHttp.readHead(stream)?.readBody(maxBodyBytes = 1024)

        assertThat(read).isEqualTo(PairingHttp.Body("short", complete = false))
    }

    @Test
    fun `a request with no body has a complete, empty one`() {
        val stream = Pieces("GET /meta?t=abc HTTP/1.1\r\nHost: 192.168.1.2\r\n\r\n")

        val read = PairingHttp.readHead(stream)?.readBody(maxBodyBytes = 1024)

        assertThat(read).isEqualTo(PairingHttp.Body("", complete = true))
    }

    @Test
    fun `the request line and headers are read without touching the body`() {
        val head = "GET /channels?t=abc&category=7 HTTP/1.1\r\nHost: 192.168.1.2\r\nX-Thing:  yes \r\n" +
            "Content-Length: 10\r\n\r\n"
        // Anything read past the head fails the test: the body is only read when it's asked for.
        val stream = SequenceInputStream(Pieces(head), NothingMore)

        val request = PairingHttp.readHead(stream)!!

        assertThat(request.method).isEqualTo("GET")
        assertThat(request.path).isEqualTo("/channels")
        assertThat(request.query).containsExactly("t", "abc", "category", "7")
        assertThat(request.headers).containsExactly("host", "192.168.1.2", "x-thing", "yes", "content-length", "10")
    }

    @Test
    fun `a stream with no request in it gives no request`() {
        assertThat(PairingHttp.readHead(Pieces(""))).isNull()
        assertThat(PairingHttp.readHead(Pieces("\r\n"))).isNull()
    }

    @Test
    fun `a head too long to be a phone's gives no request, rather than filling the TV's memory`() {
        // One header line with no end, as a stream of rubbish without a newline would be.
        val stream = Pieces("GET /?t=abc HTTP/1.1\r\nX-Rubbish: ", "a".repeat(PairingHttp.MAX_HEAD_BYTES))

        assertThat(PairingHttp.readHead(stream)).isNull()
    }

    /** What a test's stream gives once it should have been left alone. */
    private object NothingMore : InputStream() {
        override fun read(): Int = throw AssertionError("read past the head")
    }

    /** A stream that hands over at most one piece per read, the way a socket hands over segments. */
    private class Pieces(vararg pieces: ByteArray) : InputStream() {
        constructor(vararg pieces: String) : this(*pieces.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray())

        private val queue = ArrayDeque(pieces.filter { it.isNotEmpty() })
        private var current = ByteArray(0)
        private var position = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position == current.size) {
                current = queue.removeFirstOrNull() ?: return -1
                position = 0
            }
            val count = minOf(len, current.size - position)
            current.copyInto(b, off, position, position + count)
            position += count
            return count
        }
    }
}
