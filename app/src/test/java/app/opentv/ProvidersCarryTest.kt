/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.vod.ProvidersCarry
import app.opentv.ui.vod.providersCarry
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Only an Xtream login carries movies and shows, so Movies and Shows tell the viewer which case they're in (#466). */
class ProvidersCarryTest {

    private fun provider(kind: SourceKind, enabled: Boolean = true) =
        Source(name = kind.name, kind = kind, url = "https://example.com", enabled = enabled)

    @Test
    fun `no provider at all`() {
        assertThat(providersCarry(emptyList())).isEqualTo(ProvidersCarry.NO_PROVIDER)
    }

    @Test
    fun `only a playlist can't carry them`() {
        assertThat(providersCarry(listOf(provider(SourceKind.M3U))))
            .isEqualTo(ProvidersCarry.LIVE_ONLY)
    }

    @Test
    fun `only a Stalker portal can't carry them`() {
        assertThat(providersCarry(listOf(provider(SourceKind.STALKER))))
            .isEqualTo(ProvidersCarry.LIVE_ONLY)
    }

    @Test
    fun `a switched-off Xtream login beside a playlist doesn't count`() {
        val providers = listOf(provider(SourceKind.XTREAM, enabled = false), provider(SourceKind.M3U))

        assertThat(providersCarry(providers)).isEqualTo(ProvidersCarry.LIVE_ONLY)
    }

    @Test
    fun `a playlist and an Xtream login carry them`() {
        val providers = listOf(provider(SourceKind.M3U), provider(SourceKind.XTREAM))

        assertThat(providersCarry(providers)).isEqualTo(ProvidersCarry.MOVIES_AND_SHOWS)
    }
}
