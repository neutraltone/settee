/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import app.opentv.data.model.SourceKind

/**
 * The kind of Source a report is about (ADR 0010), as its `source-kind` [tag] names it: never which
 * Source. A Stremio add-on is no [SourceKind], but a stream can come from one, so it's here too.
 */
enum class ReportedSource(val tag: String) {
    XTREAM("xtream"),
    M3U("m3u"),
    STALKER("stalker"),
    STREMIO("stremio"),
    ;

    companion object {
        /** A Source of [kind], or null for none. */
        fun of(kind: SourceKind?): ReportedSource? = when (kind) {
            SourceKind.XTREAM -> XTREAM
            SourceKind.M3U -> M3U
            SourceKind.STALKER -> STALKER
            null -> null
        }
    }
}
