/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.ui.settings.hostOnly
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Settings shows a source's host, never its full address, which can carry the login (#487). */
class HostOnlyTest {

    @Test
    fun `an address that parses gives its host, as before`() {
        assertThat(hostOnly("https://example.com/manifest.json")).isEqualTo("example.com")
    }

    @Test
    fun `an M3U link with a User-Agent suffix gives its host, not the login in its query`() {
        assertThat(hostOnly("http://example.com/get.php?username=u&password=p|User-Agent=x")).isEqualTo("example.com")
    }

    @Test
    fun `a login and port before the host are left out`() {
        assertThat(hostOnly("http://user:pass@example.com:8080/x")).isEqualTo("example.com")
    }

    @Test
    fun `a host with an underscore, which java's URI gives no host for, still gives its host`() {
        assertThat(hostOnly("http://my_host.example.com/list.m3u")).isEqualTo("my_host.example.com")
    }

    @Test
    fun `a space in the path doesn't let the query through`() {
        val host = hostOnly("http://example.com/a b?password=p")
        assertThat(host).isEqualTo("example.com")
        assertThat(host).doesNotContain("password")
    }

    @Test
    fun `an address with no scheme gives the host it starts with`() {
        assertThat(hostOnly("example.com/get.php?username=u&password=p")).isEqualTo("example.com")
    }

    @Test
    fun `a login before the host of an address with no scheme is left out`() {
        assertThat(hostOnly("u:p@example.com/list.m3u")).isEqualTo("example.com")
    }

    @Test
    fun `a hash in the password doesn't leave the username showing`() {
        assertThat(hostOnly("http://u:p#x@my_host.example.com/list.m3u")).isEqualTo("my_host.example.com")
    }

    @Test
    fun `an IPv6 host keeps its brackets and loses its port`() {
        assertThat(hostOnly("[::1]:8080/list.m3u")).isEqualTo("[::1]")
    }

    @Test
    fun `text with no spaces that isn't a host gives nothing, not the text`() {
        assertThat(hostOnly("key=p")).isEmpty()
        assertThat(hostOnly("token:p")).isEmpty()
    }

    @Test
    fun `something that isn't an address gives nothing`() {
        assertThat(hostOnly("not a url")).isEmpty()
        assertThat(hostOnly("")).isEmpty()
        assertThat(hostOnly("   ")).isEmpty()
    }
}
