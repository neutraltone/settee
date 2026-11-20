/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.diagnostics.redact
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What Diagnostics stores, shows and exports never names a provider or a login (#586). */
class DiagnosticsRedactionTest {

    @Test
    fun `a provider's stream URL is taken out of a line`() {
        val line = redact("Source error · http://tv.example.com:8080/live/user/pass/123.ts · HTTP 403")

        assertThat(line).isEqualTo("Source error · [address] · HTTP 403")
    }

    @Test
    fun `a host without a scheme is taken out, with or without its port`() {
        val line =
            redact(
                "Unable to resolve host tv.example.com, then 192.168.1.20:25461, " +
                    "then portal.example.org:80/c/, then nas:8000.",
            )

        assertThat(line).isEqualTo("Unable to resolve host [address], then [address], then [address], then [address].")
    }

    @Test
    fun `logins, tokens and MACs are taken out wherever they appear`() {
        val line = redact("username=u&password=p token: abc123 Authorization=Bearer xyz MAC 00:1A:79:00:00:01")

        // Split so no one line looks like an Xtream login to gitleaks (.gitleaks.toml).
        val logins = "username=[login]" +
            "&password=[login]"
        assertThat(line).isEqualTo("$logins token: [login] Authorization=[login] MAC [mac]")
    }

    @Test
    fun `a detail line with no address in it reads as it was`() {
        val detail = "IO bad HTTP status · HTTP 503 · H.265 Main 10, 3840x2160 · java.net.UnknownHostException · " +
            "application/vnd.apple.mpegurl · at 12:30, 1.5 s"

        assertThat(redact(detail)).isEqualTo(detail)
    }

    @Test
    fun `a host in quotes, before a colon, or in capitals is taken out too`() {
        val line = redact("Unable to resolve host \"tv.example.com\": No address; TV.EXAMPLE.COM:8080: refused")

        assertThat(line).isEqualTo("Unable to resolve host \"[address]\": No address; [address]: refused")
    }
}
