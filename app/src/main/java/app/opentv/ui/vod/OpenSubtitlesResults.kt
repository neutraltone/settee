/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.SubtitleKind
import app.opentv.data.remote.OpenSubtitlesFailure
import app.opentv.data.remote.SubtitleResult
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.player.PlayerChoice
import app.opentv.ui.player.PlayerChoicePanel
import app.opentv.ui.player.languageName
import kotlinx.coroutines.flow.MutableStateFlow

/** OpenSubtitles' results for the title, Back returning to the subtitle list. */
@Composable
internal fun OpenSubtitlesResultsPanel(
    subtitles: VodSubtitles,
    firstFocus: FocusRequester,
    onClose: () -> Unit,
    onPicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val results by subtitles.results.collectAsState()
    val context = LocalContext.current
    val wording = remember { ServiceLocator.get(context).errorWording }
    val failure = remember { MutableStateFlow<Throwable?>(null) }
    val downloadFailure by failure.collectAsState()
    var fetching by remember { mutableStateOf(false) }
    val kindLabels = kindLabels()
    val found = (results as? VodSubtitles.Results.Found)?.results.orEmpty()
    // The results come after the panel opens, with nothing yet to take the Highlight: the first one
    // takes it as they arrive, a frame later, once its row is there to hold it.
    LaunchedEffect(found.isNotEmpty()) {
        if (found.isNotEmpty()) {
            withFrameNanos {}
            runCatching { firstFocus.requestFocus() }
        }
    }
    val choices = found.map { result ->
        PlayerChoice(
            languageName(result.language) ?: result.language,
            selected = false,
            onClick = {
                if (!fetching) {
                    fetching = true
                    failure.value = null
                    subtitles.choose(result) { error ->
                        fetching = false
                        if (error == null) onPicked() else failure.value = error
                    }
                }
            },
            detail = resultDetail(result, kindLabels),
        )
    }
    val problem = downloadFailure ?: problemIn(results)
    val waiting = waitingFor(results, fetching)?.let { stringResource(it) }
    PlayerChoicePanel(
        title = stringResource(R.string.opensubtitles_name),
        choices = choices,
        startFocus = firstFocus,
        onClose = onClose,
        modifier = modifier.testTag(OPENSUBTITLES_RESULTS_TAG),
        empty = waiting ?: "",
        footer = {
            problem?.let {
                val error = wording.ofOpenSubtitles(it)
                ErrorMessage(
                    error.sentence,
                    error.detail,
                    Modifier.padding(bottom = 12.dp),
                    textAlign = TextAlign.Start,
                )
            }
            if (waiting != null && choices.isNotEmpty()) Text(waiting, SetteeType.Body, color = SetteeColors.QuietText)
            Text(stringResource(R.string.opensubtitles_attribution), SetteeType.Body, color = SetteeColors.QuietText)
        },
    )
}

/** Why the results list has nothing to offer, if it hasn't: a failed search, or one that found nothing. */
private fun problemIn(results: VodSubtitles.Results): Throwable? = when (results) {
    is VodSubtitles.Results.Failed -> results.error
    is VodSubtitles.Results.Found -> OpenSubtitlesFailure.NoResults().takeIf { results.results.isEmpty() }
    else -> null
}

/** What the list is waiting for, said where the rows would be: the download picked, or the search. */
private fun waitingFor(results: VodSubtitles.Results, fetching: Boolean): Int? = when {
    fetching -> R.string.opensubtitles_downloading
    results is VodSubtitles.Results.Searching || results is VodSubtitles.Results.Idle ->
        R.string.opensubtitles_searching
    else -> null
}

@Composable
internal fun kindLabels(): Map<SubtitleKind, String> = mapOf(
    SubtitleKind.HEARING_IMPAIRED to stringResource(R.string.opensubtitles_hearing_impaired),
    SubtitleKind.FORCED to stringResource(R.string.opensubtitles_forced),
)

private fun resultDetail(result: SubtitleResult, kindLabels: Map<SubtitleKind, String>): String =
    listOfNotNull(result.release.takeIf { it.isNotBlank() }, kindLabels[result.kind]).joinToString(" · ")
