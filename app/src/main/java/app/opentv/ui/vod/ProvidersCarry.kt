/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import app.opentv.data.model.Source

/**
 * What the viewer's providers can fill. Movies and Shows need this as well as whether there are any:
 * with playlists or portals alone they'd otherwise wait for titles that never come (#466).
 */
enum class ProvidersCarry {
    NO_PROVIDER,

    /** Providers, but none switched on carries movies and shows. */
    LIVE_ONLY,

    MOVIES_AND_SHOWS,
    ;

    val hasProvider: Boolean get() = this != NO_PROVIDER
}

fun providersCarry(providers: List<Source>): ProvidersCarry = when {
    providers.isEmpty() -> ProvidersCarry.NO_PROVIDER
    providers.any { it.enabled && it.kind.carriesMoviesAndShows } -> ProvidersCarry.MOVIES_AND_SHOWS
    else -> ProvidersCarry.LIVE_ONLY
}
