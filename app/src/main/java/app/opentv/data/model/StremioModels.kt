/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.model

import kotlinx.serialization.Serializable

/**
 * A user-added Stremio add-on: a small web service (identified by its manifest URL) that, given a
 * title, returns playable stream links. OpenTV ships with none — the user pastes the manifest URL
 * of an add-on they have configured themselves (including any debrid key, which is baked into that
 * personalised URL on the add-on's own site, never entered into OpenTV). Stored on-device only,
 * exactly like a provider credential.
 *
 * This is the same open protocol Stremio and Kodi speak; OpenTV is only a neutral client for it and
 * hosts, indexes and bundles no content of its own.
 */
@Serializable
data class StremioAddon(
    /** The manifest URL, as https://…/manifest.json however it was typed. Its identity for de-dup. */
    val manifestUrl: String,
    /** Display name read from the add-on's manifest at add time. */
    val name: String,
    /**
     * What its manifest says it answers (`stream`, `catalog`, `meta`, ...), or null for one stored
     * before Settee read them, which is read again the first time it's used.
     */
    val resources: List<String>? = null,
    /** The kinds of title it serves (`movie`, `series`, ...), null as [resources] is. */
    val types: List<String>? = null,
)

/** What an add-on's manifest says about it. */
data class AddonManifest(val name: String, val resources: List<String>, val types: List<String>)

/** Where an add-on's stream is: a link to play, or a torrent to fetch. */
sealed interface StreamLink {
    /** A link that plays as it is, sent with [headers] when the add-on asks for them. */
    data class Direct(val url: String, val headers: Map<String, String> = emptyMap()) : StreamLink

    /**
     * A torrent by its [infoHash] (lower case), the file in it to play ([fileIdx], null when the add-on
     * leaves it to the largest), and the trackers and peers it names ([sources]).
     */
    data class Torrent(val infoHash: String, val fileIdx: Int? = null, val sources: List<String> = emptyList()) :
        StreamLink
}

/**
 * One stream an add-on offered for a title, as it said it. Entries that only open YouTube or another
 * app (`ytId`, `externalUrl`) are never made into one: nothing in Settee can play them.
 */
data class StremioStream(
    val link: StreamLink,
    /** The add-on's own line about it (its `title` or `description`, else its `name`), or "Stream". */
    val title: String,
    /** Which add-on sent it. */
    val addonName: String,
    /** The add-on's `name` column, which often holds the Quality, when it gave one apart from [title]. */
    val name: String? = null,
    /** Streams of one kind across episodes share it, for picking the next episode's (`bingeGroup`). */
    val bingeGroup: String? = null,
    /** The file's name, when the add-on knows it. */
    val filename: String? = null,
    /** The file's size in bytes, when the add-on knows it. */
    val videoSize: Long? = null,
)
