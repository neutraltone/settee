/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.DownloadedSubtitle
import app.opentv.data.repo.ProfileSettings
import app.opentv.player.PlayerController
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.player.PlayerChoice
import app.opentv.ui.player.SubtitlePicks
import app.opentv.ui.player.languageName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * As `rememberProfileSubtitles`, for films and episodes: the title's Downloaded Subtitles are among
 * the choices, and once the stream is known a missing subtitle may be fetched by itself. Picking a
 * stream's own track, or Off, stops drawing a Downloaded Subtitle.
 */
@Composable
fun rememberVodProfileSubtitles(
    controller: PlayerController,
    profileSettings: ProfileSettings,
    scope: CoroutineScope,
    subtitles: VodSubtitles?,
): SubtitlePicks {
    val preference by profileSettings.subtitlePreference.collectAsState(initial = null)
    val tracks by controller.tracks.collectAsState()
    val none = remember { MutableStateFlow<List<DownloadedSubtitle>?>(emptyList()) }
    val downloaded by (subtitles?.downloaded ?: none).collectAsState()
    LaunchedEffect(tracks, preference, downloaded) {
        // Until the next title's stream is in, the tracks are still the last one's: deciding on them
        // would show, or not fetch, by the wrong title's languages.
        if (controller.isSwitching) return@LaunchedEffect
        val list = downloaded ?: return@LaunchedEffect
        controller.applySubtitlePreference(preference, list.map { it.language })?.let { pick ->
            subtitles?.show(list[pick.index], picked = false)
        }
        if (tracks.groups.isNotEmpty()) subtitles?.autoFetch(preference, controller.streamSubtitleLanguages)
    }
    return remember(profileSettings, scope, subtitles) {
        SubtitlePicks(
            onOff = {
                subtitles?.hide()
                scope.launch { profileSettings.setSubtitlesOn(false) }
            },
            onPicked = { language ->
                subtitles?.hide()
                scope.launch { profileSettings.pickSubtitleTrack(language) }
            },
        )
    }
}

/** Draws the showing Downloaded Subtitle over the picture, styled as the stream's own would be. */
@OptIn(UnstableApi::class)
@Composable
fun DownloadedSubtitleOverlay(subtitles: VodSubtitles, positionMillis: () -> Long, modifier: Modifier = Modifier) {
    val showing by subtitles.showing.collectAsState()
    if (showing == null) return
    val holder = remember { arrayOfNulls<SubtitleView>(1) }
    AndroidView(
        modifier = modifier.fillMaxSize().testTag(DOWNLOADED_SUBTITLE_TAG),
        factory = { context ->
            SubtitleView(context).apply {
                setUserDefaultStyle()
                setUserDefaultTextSize()
                holder[0] = this
            }
        },
    )
    // Looked up often enough that a line appears on its frame to the eye, and as Timing moves it.
    LaunchedEffect(showing) {
        var drawn: List<Cue>? = null
        while (isActive) {
            val cues = subtitles.cuesAt(positionMillis())
            // Only when the lines change: setting them redraws the view, ten times a second for a whole film.
            if (cues != drawn) {
                holder[0]?.setCues(cues)
                drawn = cues
            }
            delay(CUE_POLL_MILLIS)
        }
    }
}

/** The brief word about a subtitle fetched by itself: that it came, or why it didn't. Never in the way. */
@Composable
fun SubtitleNote(subtitles: VodSubtitles, modifier: Modifier = Modifier) {
    val note by subtitles.note.collectAsState()
    val context = LocalContext.current
    val wording = remember { ServiceLocator.get(context).errorWording }
    // Each note shows for a few seconds, then goes; a new one shows afresh.
    var gone by remember(note) { mutableStateOf(false) }
    LaunchedEffect(note) {
        delay(NOTE_MILLIS)
        gone = true
    }
    val current = note?.takeUnless { gone } ?: return
    val box = modifier
        .padding(24.dp)
        .background(SetteeColors.Surface, SetteeShapes.Card)
        .padding(horizontal = 20.dp, vertical = 12.dp)
        .testTag(SUBTITLE_NOTE_TAG)
    when (current) {
        VodSubtitles.Note.Fetched -> Text(
            stringResource(R.string.opensubtitles_fetched),
            SetteeType.Body,
            modifier = box,
        )
        is VodSubtitles.Note.Failed -> {
            val error = wording.ofOpenSubtitles(current.error)
            ErrorMessage(error.sentence, error.detail, box, textAlign = TextAlign.Start)
        }
    }
}

/**
 * The rows OpenSubtitles adds to the top of the subtitle list: its entry, then, while a Downloaded
 * Subtitle shows, its Timing, then the title's Downloaded Subtitles. Kept together at the top, so the
 * subtitle the viewer picked and the control that adjusts it are where they picked it from.
 */
@Composable
internal fun openSubtitlesChoices(subtitles: VodSubtitles, onFind: () -> Unit, onDone: () -> Unit): List<PlayerChoice> {
    val subject by subtitles.subject.collectAsState()
    val downloaded by subtitles.downloaded.collectAsState()
    val showing by subtitles.showing.collectAsState()
    if (subject == null) return emptyList()
    val downloadedLabel = stringResource(R.string.opensubtitles_downloaded)
    val kindLabels = kindLabels()
    val entry = PlayerChoice(
        stringResource(R.string.opensubtitles_name),
        selected = false,
        onClick = onFind,
        detail = stringResource(R.string.opensubtitles_find),
        tag = OPENSUBTITLES_ENTRY_TAG,
    )
    val timing = showing?.let { current ->
        PlayerChoice(
            stringResource(R.string.opensubtitles_timing),
            selected = false,
            onClick = {},
            detail = timingLabel(current.subtitle.offsetMillis) + " · " +
                stringResource(R.string.opensubtitles_timing_hint),
            onAdjust = subtitles::nudge,
            tag = TIMING_TAG,
        )
    }
    val saved = downloaded.orEmpty().sortedBy { it.language }.map { subtitle ->
        PlayerChoice(
            languageName(subtitle.language) ?: subtitle.language,
            selected = showing?.subtitle?.fileId == subtitle.fileId,
            onClick = {
                subtitles.show(subtitle, picked = true)
                onDone()
            },
            detail = listOfNotNull(downloadedLabel, kindLabels[subtitle.kind]).joinToString(" · "),
        )
    }
    return listOfNotNull(entry, timing) + saved
}

/** "+0.3 s", "−1.2 s", "0.0 s". */
internal fun timingLabel(offsetMillis: Long): String {
    val tenths = offsetMillis / TENTH
    val sign = when {
        tenths > 0 -> "+"
        tenths < 0 -> "−"
        else -> ""
    }
    val abs = kotlin.math.abs(tenths)
    return "$sign${abs / TENTHS_PER_SECOND}.${abs % TENTHS_PER_SECOND} s"
}

/** The film player's OpenSubtitles side, from [VodPlayerScreen]; null without Settee's key. */
val LocalVodSubtitles = staticCompositionLocalOf<VodSubtitles?> { null }

private const val CUE_POLL_MILLIS = 100L
private const val NOTE_MILLIS = 4_000L
private const val TENTH = 100L
private const val TENTHS_PER_SECOND = 10L

const val OPENSUBTITLES_ENTRY_TAG = "opensubtitles_entry"
const val OPENSUBTITLES_RESULTS_TAG = "opensubtitles_results"
const val TIMING_TAG = "opensubtitles_timing"
const val DOWNLOADED_SUBTITLE_TAG = "downloaded_subtitle"
const val SUBTITLE_NOTE_TAG = "subtitle_note"
