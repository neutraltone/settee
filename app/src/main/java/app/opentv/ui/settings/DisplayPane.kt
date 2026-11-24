/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.SidePanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Display and playback: what shows, and how things play. */
@Composable
internal fun DisplayPane(onOpenHomeRows: () -> Unit = {}) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val graph = remember { ServiceLocator.get(context) }
    val subtitles by graph.profileSettings.subtitlePreference.collectAsState(initial = null)
    val profileName = activeProfileName()
    val playNext by settings.playNextAutomatically.collectAsState()
    val skipIntros by settings.skipIntrosAndCredits.collectAsState()
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
    val minutesLeft = rememberSleepMinutesLeft()
    ListRow(
        stringResource(R.string.settings_row_sleep_timer),
        onClick = { choosing = Choice.Sleep },
        value = minutesLeft?.let { stringResource(R.string.settings_row_sleep_left, it) } ?: off,
    )
    ListRow(
        stringResource(R.string.settings_row_play_next),
        onClick = { settings.setPlayNextAutomatically(!playNext) },
        switch = playNext,
    )
    ListRow(
        stringResource(R.string.settings_row_skip_intros),
        onClick = { settings.setSkipIntrosAndCredits(!skipIntros) },
        switch = skipIntros,
        note = stringResource(R.string.settings_row_skip_intros_note),
    )
    // A Profile's, like the Subtitle Preference: its own screen, as moving rows needs room.
    val homeRows = screenRow("home-rows", onOpenHomeRows)
    ListRow(
        stringResource(R.string.home_rows_title),
        onClick = homeRows.open,
        opens = true,
        note = profileName?.let { stringResource(R.string.settings_row_for_profile, it) },
        modifier = homeRows.modifier,
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
    // Not a screen of its own, but a language change makes the activity again: the row takes the
    // Highlight back the same way, once changeLanguage has named it. Only its modifier is used, as its
    // open would name it for a panel closed with nothing chosen too.
    val languageRow = screenRow(LANGUAGE_ROW) {}
    ListRow(
        stringResource(R.string.settings_row_language),
        onClick = { choosing = Choice.Language },
        value = OpenTvLanguages.firstOrNull { it.first == language }?.second
            ?: stringResource(R.string.settings_language_system),
        modifier = languageRow.modifier,
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

    when (val choice = choosing) {
        null -> Unit
        else -> ChoicePanel(choice, subtitlesOn = subtitlesOn, sleeping = minutesLeft != null, language = language) {
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
    val memory = LocalSettingsMemory.current
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
            Choice.Language -> {
                PanelRow(stringResource(R.string.settings_language_system), ticked = language.isBlank(), onClick = {
                    close()
                    changeLanguage(context, settings, "", memory)
                })
                // Each language is listed in its own name (endonym), the convention users expect.
                OpenTvLanguages.forEach { (tag, name) ->
                    PanelRow(name, ticked = language == tag, onClick = {
                        close()
                        changeLanguage(context, settings, tag, memory)
                    })
                }
            }
        }
    }
}

private enum class Choice { Subtitles, Sleep, Language }

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

/**
 * The sleep timer's minutes left, or null when it's off, redrawn as each minute passes. Nothing else
 * would redraw the row: with no player open nobody clears a timer that runs out, so without this the
 * row would keep its last count instead of turning to Off.
 */
@Composable
private fun rememberSleepMinutesLeft(): Int? {
    val deadline by SleepTimer.deadline.collectAsState()
    var minutePassed by remember { mutableIntStateOf(0) }
    LaunchedEffect(deadline) {
        while (true) {
            val left = SleepTimer.millisRemaining() ?: break
            // Until the rounded-up count drops by one, or the timer runs out.
            delay((left - 1) % MINUTE_MILLIS + 1)
            minutePassed++
        }
    }
    return remember(deadline, minutePassed) { SleepTimer.minutesRemaining() }
}

/**
 * Persist the chosen language and recreate the activity so the whole UI reloads translated. Settings
 * comes back on Language ([memory] is saved state), so the viewer can check or undo it (#443).
 */
private fun changeLanguage(context: Context, settings: AppSettings, tag: String, memory: SettingsMemory?) {
    if (settings.languageTag.value == tag) return
    settings.setLanguageTag(tag)
    memory?.openedRow = LANGUAGE_ROW
    context.findActivity()?.recreate()
}

private const val LANGUAGE_ROW = "language"

private const val MINUTE_MILLIS = 60_000L
