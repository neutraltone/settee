/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.ProfileSetting
import app.opentv.sync.SyncProfileSetting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Whether the Profile wants subtitles shown, and in which language. A null [language] follows the
 * device's language, worked out when a title plays rather than stored.
 */
data class SubtitlePreference(val enabled: Boolean, val language: String?)

/**
 * Streams tag the same language as `eng`, `en` or `en-GB`; a viewer who picked English subtitles
 * means any of them, so languages are stored and compared by their base code.
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun baseLanguage(code: String?): String? =
    code?.let { Util.normalizeLanguageCode(it) }?.substringBefore('-')?.lowercase()

/**
 * The settings that belong to a Profile rather than the device (ADR 0002): the one owner of the
 * `profile_settings` table. Screens, players and sync ask it, never the DAO.
 */
class ProfileSettings(
    private val db: OpenTvDatabase,
    private val activeProfileId: StateFlow<Long>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.profileSettings()
    private val profiles = db.profiles()

    /** The active Profile's Subtitle Preference; follows Profile switches. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val subtitlePreference: Flow<SubtitlePreference> = activeProfileId
        .flatMapLatest { dao.observeFor(it) }
        .map { rows ->
            val byName = rows.associate { it.name to it.value }
            SubtitlePreference(
                enabled = byName[SUBTITLES_ON].toBoolean(),
                language = byName[SUBTITLE_LANGUAGE],
            )
        }
        .distinctUntilChanged()

    /** The viewer turned subtitles on or off, in a player or in Settings. */
    suspend fun setSubtitlesOn(enabled: Boolean) = store(SUBTITLES_ON, enabled.toString())

    /**
     * The viewer picked a subtitle track. A track with no language says nothing about which
     * language they want, so the one they picked before is kept.
     */
    suspend fun pickSubtitleTrack(language: String?) {
        setSubtitlesOn(true)
        baseLanguage(language)?.takeIf { it.isNotBlank() }?.let { store(SUBTITLE_LANGUAGE, it) }
    }

    /** A Version the active Profile chose for a title, and when: [VodQualities] weighs it against what played. */
    data class Choice(val version: String, val atMillis: Long)

    /** The Version the active Profile chose for [title], a key [VodQualities] makes, or null if it never did. */
    suspend fun chosenVersion(title: String): Choice? =
        dao.find(activeProfileId.value, VERSION + title)?.let { Choice(it.value, it.updatedAtMillis) }

    /**
     * The viewer chose a Version of [title] in the Quality pill, or agreed to one in another language.
     * Stored here so it follows the Profile to its other devices, with [version] naming it by source and
     * provider id like a Favourite (ADR 0004).
     */
    suspend fun chooseVersion(title: String, version: String) = store(VERSION + title, version)

    private suspend fun store(name: String, value: String) =
        dao.upsert(ProfileSetting(activeProfileId.value, name, value, clock()))

    /** The Profile is being deleted: its settings go with it. */
    suspend fun forgetProfile(profileId: Long) = dao.deleteFor(profileId)

    /** Profile [from] is merging into [into]: its settings follow, the newer choice winning. */
    suspend fun mergeProfile(from: Long, into: Long) {
        dao.all().filter { it.profileId == from }.forEach { mergeSynced(it.copy(profileId = into)) }
        dao.deleteFor(from)
    }

    /** A setting from another device: the newer choice wins. Returns whether it was taken. */
    internal suspend fun mergeSynced(setting: ProfileSetting): Boolean {
        val local = dao.find(setting.profileId, setting.name)
        if (local != null && local.updatedAtMillis >= setting.updatedAtMillis) return false
        dao.upsert(setting)
        return true
    }

    /** Every Profile's chosen settings, as they travel to the other side. One never chosen has no row. */
    suspend fun export(): List<SyncProfileSetting> {
        val names = profiles.directory()
        return dao.all().mapNotNull { setting ->
            names.name(setting.profileId)?.let {
                SyncProfileSetting(it, setting.name, setting.value, setting.updatedAtMillis)
            }
        }
    }

    /** Merges another device's settings, newest wins, labels going by [history]. Returns how many were taken. */
    suspend fun import(items: List<SyncProfileSetting>, history: ProfileHistory = ProfileHistory.NONE): Int {
        val names = profiles.directory()
        val placed = items.mapNotNull { item ->
            names.id(history.currentName(item.profile, item.updatedAtMillis))?.let {
                ProfileSetting(it, item.name, item.value, item.updatedAtMillis)
            }
        }
        return db.mergeForProfiles(placed, { it.profileId }) { mergeSynced(it) }
    }

    private companion object {
        const val SUBTITLES_ON = "subtitles.on"
        const val SUBTITLE_LANGUAGE = "subtitles.language"
        const val VERSION = "version."
    }
}
