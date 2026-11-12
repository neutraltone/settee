/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.core.SleepTimer
import app.opentv.core.findActivity
import app.opentv.data.work.SyncWorker
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.SidePanel
import kotlinx.coroutines.launch

/** Display and playback: what shows, and how things play. */
@Composable
internal fun DisplayPane() {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val graph = remember { ServiceLocator.get(context) }
    val subtitles by graph.profileSettings.subtitlePreference.collectAsState(initial = null)
    val profileName = activeProfileName()
    val deadline by SleepTimer.deadline.collectAsState()
    val playNext by settings.playNextAutomatically.collectAsState()
    val previewOnHome by settings.livePreviewOnHome.collectAsState()
    val previewVideo by settings.guidePreviewVideo.collectAsState()
    val previewSound by settings.guidePreviewSound.collectAsState()
    val language by settings.languageTag.collectAsState()
    val liveEnabled by settings.liveEnabled.collectAsState()
    val moviesEnabled by settings.moviesEnabled.collectAsState()
    val seriesEnabled by settings.seriesEnabled.collectAsState()
    var choosing by remember { mutableStateOf<Choice?>(null) }

    val on = stringResource(R.string.settings_row_on)
    val off = stringResource(R.string.settings_row_off)
    val subtitlesOn = subtitles?.enabled ?: false
    ListRow(
        stringResource(R.string.settings_row_subtitles),
        onClick = { choosing = Choice.Subtitles },
        value = if (subtitlesOn) on else off,
        note = profileName?.let { stringResource(R.string.settings_row_for_profile, it) },
    )
    val minutesLeft = deadline?.let { ((it - System.currentTimeMillis()).coerceAtLeast(0) + MINUTE - 1) / MINUTE }
    ListRow(
        stringResource(R.string.settings_row_sleep_timer),
        onClick = { choosing = Choice.Sleep },
        value = minutesLeft?.let { stringResource(R.string.settings_row_sleep_left, it.toInt()) } ?: off,
    )
    ListRow(
        stringResource(R.string.settings_row_play_next),
        onClick = { settings.setPlayNextAutomatically(!playNext) },
        switch = playNext,
    )
    ListRow(
        stringResource(R.string.settings_row_live_preview_home),
        onClick = { settings.setLivePreviewOnHome(!previewOnHome) },
        switch = previewOnHome,
    )
    ListRow(
        stringResource(R.string.settings_row_guide_preview),
        onClick = { settings.setGuidePreviewVideo(!previewVideo) },
        switch = previewVideo,
    )
    ListRow(
        stringResource(R.string.settings_row_guide_preview_sound),
        onClick = { settings.setGuidePreviewSound(!previewSound) },
        switch = previewSound,
    )
    ListRow(
        stringResource(R.string.settings_row_language),
        onClick = { choosing = Choice.Language },
        value = OpenTvLanguages.firstOrNull { it.first == language }?.second
            ?: stringResource(R.string.settings_language_system),
    )
    ListRow(stringResource(R.string.nav_live_tv), onClick = {
        settings.setLiveEnabled(!liveEnabled)
    }, switch = liveEnabled)
    ListRow(stringResource(R.string.nav_movies), onClick = {
        settings.setMoviesEnabled(!moviesEnabled)
    }, switch = moviesEnabled)
    ListRow(stringResource(R.string.nav_shows), onClick = {
        settings.setSeriesEnabled(!seriesEnabled)
    }, switch = seriesEnabled)
    val tmdbKey by settings.tmdbApiKey.collectAsState()
    ListRow(
        stringResource(R.string.settings_row_tmdb),
        onClick = { choosing = Choice.TmdbKey },
        value = stringResource(
            if (tmdbKey.isBlank()) R.string.settings_row_tmdb_none else R.string.settings_row_tmdb_set,
        ),
        note = stringResource(R.string.settings_tmdb_note),
    )
    val refreshing = stringResource(R.string.settings_content_refreshing)
    ListRow(
        stringResource(R.string.settings_row_refresh_content),
        onClick = {
            // Background work, so it isn't cut short by leaving Settings; the Toast says it started.
            SyncWorker.refreshNow(context)
            Toast.makeText(context, refreshing, Toast.LENGTH_SHORT).show()
        },
    )

    when (val choice = choosing) {
        // Never shown once saved: the row only says there is one.
        Choice.TmdbKey -> EntryWindow(
            title = stringResource(R.string.settings_tmdb_key_label),
            initial = "",
            hint = stringResource(R.string.settings_tmdb_note),
            // Saving it empty is how TMDB is switched off again.
            allowBlank = true,
            onSave = {
                choosing = null
                settings.setTmdbApiKey(it)
            },
            onCancel = { choosing = null },
        )
        null -> Unit
        else -> ChoicePanel(choice, subtitlesOn = subtitlesOn, sleeping = deadline != null, language = language) {
            choosing = null
        }
    }
}

/** The panel a value row opens, with the current choice ticked. Choosing closes it, as Back does. */
@Composable
private fun ChoicePanel(choice: Choice, subtitlesOn: Boolean, sleeping: Boolean, language: String, close: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val graph = remember { ServiceLocator.get(context) }
    val scope = rememberCoroutineScope()
    val on = stringResource(R.string.settings_row_on)
    val off = stringResource(R.string.settings_row_off)
    SidePanel(onBack = close) {
        when (choice) {
            Choice.Subtitles -> listOf(false to off, true to on).forEach { (value, label) ->
                PanelRow(label, ticked = value == subtitlesOn, onClick = {
                    scope.launch { graph.profileSettings.setSubtitlesOn(value) }
                    close()
                })
            }
            Choice.Sleep -> {
                PanelRow(off, ticked = !sleeping, onClick = {
                    SleepTimer.clear()
                    close()
                })
                SleepTimer.presets.forEach { minutes ->
                    PanelRow(stringResource(R.string.settings_sleep_minutes, minutes), onClick = {
                        SleepTimer.armMinutes(minutes)
                        close()
                    })
                }
            }
            Choice.TmdbKey -> Unit
            Choice.Language -> {
                PanelRow(stringResource(R.string.settings_language_system), ticked = language.isBlank(), onClick = {
                    close()
                    changeLanguage(context, settings, "")
                })
                // Each language is listed in its own name (endonym), the convention users expect.
                OpenTvLanguages.forEach { (tag, name) ->
                    PanelRow(name, ticked = language == tag, onClick = {
                        close()
                        changeLanguage(context, settings, tag)
                    })
                }
            }
        }
    }
}

private enum class Choice { Subtitles, Sleep, Language, TmdbKey }

/** The active Profile's name, for the line under a setting that belongs to it (ADR 0002). */
@Composable
internal fun activeProfileName(): String? {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val activeId by graph.settings.activeProfileId.collectAsState()
    val profiles by remember { graph.profiles.observeAll() }.collectAsState(initial = emptyList())
    return profiles.firstOrNull { it.id == activeId }?.name
}

/**
 * The UI languages Settee ships translations for, each labelled with its own endonym. The tag is
 * both the stored language tag and the resource qualifier (values-<tag>), so adding a language is
 * just a new `values-xx/strings.xml` plus a line here.
 */
private val OpenTvLanguages: List<Pair<String, String>> = listOf(
    "en" to "English",
    "es" to "Español",
    "fr" to "Français",
    "de" to "Deutsch",
    "it" to "Italiano",
    "pt" to "Português",
    "nl" to "Nederlands",
    "pl" to "Polski",
    "ru" to "Русский",
    "tr" to "Türkçe",
    "ar" to "العربية",
    "zh" to "中文",
    "ja" to "日本語",
    "ko" to "한국어",
    "hi" to "हिन्दी",
    "sv" to "Svenska",
    "da" to "Dansk",
    "fi" to "Suomi",
    "nb" to "Norsk",
    "cs" to "Čeština",
    "el" to "Ελληνικά",
    "ro" to "Română",
    "hu" to "Magyar",
    "uk" to "Українська",
    "id" to "Bahasa Indonesia",
    "th" to "ไทย",
    "vi" to "Tiếng Việt",
    "bg" to "Български",
    "sk" to "Slovenčina",
    "hr" to "Hrvatski",
    "fa" to "فارسی",
)

/** Persist the chosen language and recreate the activity so the whole UI reloads translated. */
private fun changeLanguage(context: Context, settings: AppSettings, tag: String) {
    if (settings.languageTag.value == tag) return
    settings.setLanguageTag(tag)
    context.findActivity()?.recreate()
}

private const val MINUTE = 60_000L
