/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import app.opentv.data.db.ProfileSettingDao
import app.opentv.data.model.ProfileSetting
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
    private val dao: ProfileSettingDao,
    private val activeProfileId: StateFlow<Long>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
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

    private suspend fun store(name: String, value: String) =
        dao.upsert(ProfileSetting(activeProfileId.value, name, value, clock()))

    /** The Profile is being deleted: its settings go with it. */
    suspend fun forgetProfile(profileId: Long) = dao.deleteFor(profileId)

    /** Every Profile's stored settings, for sync. Settings a Profile never chose have no row. */
    suspend fun everyProfiles(): List<ProfileSetting> = dao.all()

    /** A setting from another device: the newer choice wins. Returns whether it was taken. */
    suspend fun mergeSynced(setting: ProfileSetting): Boolean {
        val local = dao.find(setting.profileId, setting.name)
        if (local != null && local.updatedAtMillis >= setting.updatedAtMillis) return false
        dao.upsert(setting)
        return true
    }

    private companion object {
        const val SUBTITLES_ON = "subtitles.on"
        const val SUBTITLE_LANGUAGE = "subtitles.language"
    }
}
