/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import kotlinx.serialization.Serializable

/**
 * The state two OpenTV devices exchange when they sync.
 *
 * Nothing here is keyed by a local database id — those differ from one install to the next. An
 * item is identified by something stable for the same provider: a watch position, hidden channel
 * or Favourite by its **source name and provider id** (ADR 0004), a profile by its **name**, a
 * category by its **group key**, a NAS recording by its **smb:// locator**. So a bundle from one
 * device makes sense on another. A stream URL never travels: an Xtream one holds the account's
 * username and password, and this bundle is left as a plain file on the NAS.
 *
 * Two merge policies travel together, matching how people actually use this:
 *  - **Watch positions** are two-way, newest-wins per item (you watch on any device, resume on
 *    all), using [SyncPosition.updatedAtMillis]; a hand mark rides on its position. So does a
 *    series taken out of Continue Watching, by [SyncDismissal.dismissedAtMillis].
 *  - **Profile settings** are newest-wins per Profile and setting, using
 *    [SyncProfileSetting.updatedAtMillis]; only settings a Profile actually chose travel (ADR 0002).
 *  - **Locked categories** spread by the time they were locked, and an unlock stays on its device:
 *    a lock only applies where it's newer than that device's last unlock (ADR 0005).
 *  - **Favourites and hidden channels** are newest-wins per item, by the time each last changed, so
 *    unstarring and unhiding spread too. A time of zero means the bundle had none, and counts as the oldest possible.
 *  - **Profile Renames and adds** are dated records every device keeps and passes on, applied once
 *    each in time order, and anything labelled with an old name lands on the new one (ADR 0005).
 *  - **Recordings** are additive: NAS recordings from a peer are merged in without removing anything.
 *
 * Every field defaults to empty and the reader ignores unknown keys, so a positions-only device
 * and a full device interoperate without a flag day.
 */
@Serializable
data class SyncBundle(
    val version: Int = 7,
    val generatedAtMillis: Long = 0,
    /** Profile names the peer knows about. Others add any they are missing and haven't removed. */
    val profiles: List<String> = emptyList(),
    /** Every Profile Rename the peer has made or heard of. */
    val profileRenames: List<SyncProfileRename> = emptyList(),
    /** Every Profile the peer has added deliberately or heard of, which decides who owns a reused name. */
    val profileAdds: List<SyncProfileAdd> = emptyList(),
    /**
     * Two-way, newest-wins resume points. A new key, not `positions`, whose items were keyed by
     * stream URL: a peer's file that fails to decode is skipped whole, so reusing the key would
     * cost an older bundle its Favourites too. This way only its old positions are ignored.
     */
    val progress: List<SyncPosition> = emptyList(),
    /** Two-way, newest-wins series each Profile took out of Continue Watching. */
    val dismissals: List<SyncDismissal> = emptyList(),
    /** Every Profile's Favourites, unstarred ones too, each landing on the Profile with the same name. */
    val favourites: List<SyncFavourite> = emptyList(),
    /** Channels a viewer hid or showed, device-wide, by the identifiers noted above; a new key as [progress] is. */
    val hiddenStreams: List<SyncHiddenChannel> = emptyList(),
    /** The locked categories without their times, for older devices, which don't read [categoryLocks]. */
    val hiddenCategories: List<String> = emptyList(),
    /** Locked categories with when each was made; a new key, as [progress] is, since older files have none. */
    val categoryLocks: List<SyncCategoryLock> = emptyList(),
    /**
     * Recordings that live on the shared NAS (their [SyncRecording.filePath] is an `smb://`
     * locator) and so play on any device pointed at the same NAS. Device-local recordings are
     * never shared — they cannot be opened from another box.
     */
    val recordings: List<SyncRecording> = emptyList(),
    /** Every Profile's chosen settings, each landing on the Profile with the same name (newest-wins). */
    val profileSettings: List<SyncProfileSetting> = emptyList(),
)

/** "[from] became [to] at this time." */
@Serializable
data class SyncProfileRename(val from: String, val to: String, val atMillis: Long)

/** A viewer added a Profile called [name] at this time. */
@Serializable
data class SyncProfileAdd(val name: String, val atMillis: Long)

/** A locked category: [key] is its group key, [lockedAtMillis] when the lock was first made. */
@Serializable
data class SyncCategoryLock(val key: String, val lockedAtMillis: Long)

/** One setting a Profile chose; [name] and [value] are as ProfileSettings stores them. */
@Serializable
data class SyncProfileSetting(val profile: String, val name: String, val value: String, val updatedAtMillis: Long)

/**
 * One Profile's Favourite: [kind] is a StreamKind name, [id] the provider's stream or series id within
 * [source]. [removed] when it was unstarred, which has to travel for the other side to unstar it.
 */
@Serializable
data class SyncFavourite(
    val profile: String,
    val kind: String,
    val source: String,
    val id: String,
    val changedAtMillis: Long = 0,
    val removed: Boolean = false,
)

/**
 * A channel a viewer hid or showed on the device: [id] is the provider's stream id within [source],
 * by name. An older device only ever sent hidden ones, with no time.
 */
@Serializable
data class SyncHiddenChannel(
    val source: String,
    val id: String,
    val hidden: Boolean = true,
    val changedAtMillis: Long = 0,
)

@Serializable
data class SyncPosition(
    val profile: String,
    val source: String,
    /** "movie" or "ep" — which table to find [id] in on the receiving device. */
    val kind: String,
    /** The provider's stream id for a movie, or episode id for an episode, within [source]. */
    val id: String,
    /** The series an episode belongs to, so a device that hasn't fetched the episode yet can. */
    val seriesId: String? = null,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAtMillis: Long,
    /** A HandMark name when the Profile marked the title by hand, else null. */
    val mark: String? = null,
)

/**
 * One series a Profile took out of Continue Watching: [seriesId] is the provider's within [source],
 * by name; [lastSeason] and [lastEpisode] are the series' last episode then.
 */
@Serializable
data class SyncDismissal(
    val profile: String,
    val source: String,
    val seriesId: String,
    val dismissedAtMillis: Long,
    val lastSeason: Int,
    val lastEpisode: Int,
)

/**
 * A finished NAS recording, portable across devices. Everything needed to insert a playable
 * library row is snapshotted here; the receiving device opens [filePath] (an `smb://` locator)
 * straight off the shared NAS. De-duplicated on the receiver by that locator. No stream or logo URL:
 * either can point at the provider, so a recording from another device shows no logo.
 */
@Serializable
data class SyncRecording(
    val channelName: String,
    val title: String,
    val description: String? = null,
    /** The `smb://host/share/…/file.ts` locator on the shared NAS. */
    val filePath: String,
    val userAgent: String,
    val scheduledStartMillis: Long = 0,
    val scheduledEndMillis: Long = 0,
    val startedAtMillis: Long = 0,
    val endedAtMillis: Long = 0,
    val sizeBytes: Long = 0,
)
