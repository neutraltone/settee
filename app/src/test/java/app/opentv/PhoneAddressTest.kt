/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.pairing.PhoneAddress
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The addresses a phone opens, shared by setup and Manage on phone or laptop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhoneAddressTest {

    private val opened = mutableListOf<PhoneAddress>()

    @After
    fun close() = opened.forEach { it.close() }

    private fun open(): PhoneAddress {
        val result = PhoneAddress.open()
        assumeTrue("needs a private LAN address to serve on", result is PhoneAddress.Opening.Opened)
        return (result as PhoneAddress.Opening.Opened).address.also { opened += it }
    }

    @Test
    fun `the typed code opens it, and the TV's address and port alone do not`() {
        val address = open()

        assertThat(address.check("GET", "/${address.shortToken}", null))
            .isEqualTo(PhoneAddress.Check.Typed)
        val refused = address.check("GET", "/", null)
        assertThat(refused).isInstanceOf(PhoneAddress.Check.Refused::class.java)
        assertThat((refused as PhoneAddress.Check.Refused).status).isEqualTo("404 Not Found")
        assertThat(refused.body).isEqualTo("Not found.")
    }

    @Test
    fun `the QR code's token opens every route, for any method`() {
        val address = open()
        val token = address.token

        assertThat(address.check("GET", "/", token)).isEqualTo(PhoneAddress.Check.Scanned)
        assertThat(address.check("POST", "/submit", token)).isEqualTo(PhoneAddress.Check.Scanned)
        // Swap the last hex digit for a different one: a fixed "0" left the token unchanged 1 run in 16.
        val tampered = token.dropLast(1) + if (token.last() == '0') "1" else "0"
        assertThat(address.check("POST", "/submit", tampered))
            .isInstanceOf(PhoneAddress.Check.Refused::class.java)
    }

    @Test
    fun `a mistyped code tells the viewer to check it against the TV`() {
        val address = open()

        val refused = address.check("GET", "/${wrong(address)}", null) as PhoneAddress.Check.Refused

        assertThat(refused.status).isEqualTo("404 Not Found")
        assertThat(refused.contentType).isEqualTo("text/html")
        assertThat(refused.body).contains("Check the address")
    }

    @Test
    fun `a path that isn't shaped like a code gets a bare 404`() {
        val address = open()

        val refused = address.check("GET", "/favicon.ico", null) as PhoneAddress.Check.Refused

        assertThat(refused.body).isEqualTo("Not found.")
    }

    @Test
    fun `after too many wrong codes the typed address stops working but the QR code still does`() {
        val address = open()
        val code = address.shortToken
        val token = address.token
        repeat(PhoneAddress.MAX_SHORT_TOKEN_GUESSES) { address.check("GET", "/${wrong(address)}", null) }

        val typed = address.check("GET", "/$code", null) as PhoneAddress.Check.Refused

        assertThat(typed.body).contains("scan the QR code")
        assertThat(address.check("GET", "/", token)).isEqualTo(PhoneAddress.Check.Scanned)
    }

    @Test
    fun `one wrong code short of the limit, the typed address still works`() {
        val address = open()
        val code = address.shortToken
        repeat(PhoneAddress.MAX_SHORT_TOKEN_GUESSES - 1) { address.check("GET", "/${wrong(address)}", null) }

        assertThat(address.check("GET", "/$code", null)).isEqualTo(PhoneAddress.Check.Typed)
    }

    @Test
    fun `the typed address is short enough to read off a TV`() {
        val address = open()

        assertThat(address.shortUrl).matches("""[0-9.]+:[0-9]+/[a-z3-9]{7}""")
        assertThat(address.shortUrl).isEqualTo("${address.host}:${address.port}/${address.shortToken}")
    }

    @Test
    fun `the typed address never uses characters that look alike across a room`() {
        // Many addresses, since one code is only seven of the characters it could have used.
        val codes = (1..30).joinToString("") { open().shortToken }

        // One of each look-alike group is dropped: 0/o, 1/l/i, 2/z, 5/s, 6/b, 9/g/q, u/v/y.
        assertThat(codes).doesNotContainMatch("""[01ilo2z5sbgquvy]""")
    }

    @Test
    fun `the typed code only opens the page, never a submit`() {
        val address = open()

        assertThat(address.check("POST", "/${address.shortToken}", null))
            .isInstanceOf(PhoneAddress.Check.Refused::class.java)
    }

    @Test
    fun `a closed address lets nothing in`() {
        val address = open()
        val code = address.shortToken
        val token = address.token

        address.close()

        assertThat(address.check("GET", "/", token)).isInstanceOf(PhoneAddress.Check.Refused::class.java)
        assertThat(address.check("GET", "/$code", null)).isInstanceOf(PhoneAddress.Check.Refused::class.java)
    }

    private fun wrong(address: PhoneAddress) = address.shortToken.map { if (it == '3') '4' else '3' }.joinToString("")
}
