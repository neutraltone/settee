/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
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
import app.opentv.ui.player.RowEnd
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
    // The result being downloaded, whose row waits with the small aerial TV; one at a time.
    var fetching by remember { mutableStateOf<Long?>(null) }
    val kindLabels = kindLabels()
    val found = (results as? VodSubtitles.Results.Found)?.results.orEmpty()
    val onBox by subtitles.downloaded.collectAsState()
    val showing by subtitles.showing.collectAsState()
    val saved = onBox.orEmpty().map { it.fileId }.toSet()
    val downloadedLabel = stringResource(R.string.opensubtitles_downloaded)
    val downloading = stringResource(R.string.opensubtitles_downloading)
    // The results come after the panel opens, with nothing yet to take the Highlight: the first one
    // takes it as they arrive, a frame later, once its row is there to hold it.
    LaunchedEffect(found.isNotEmpty()) {
        if (found.isNotEmpty()) {
            withFrameNanos {}
            runCatching { firstFocus.requestFocus() }
        }
    }
    val choices = found.map { result ->
        val onThisBox = result.fileId in saved
        PlayerChoice(
            languageName(result.language) ?: result.language,
            // The one showing now is ticked, as any list's chosen row is; others already here say so.
            selected = showing?.subtitle?.fileId == result.fileId,
            onClick = {
                if (fetching == null) {
                    fetching = result.fileId
                    failure.value = null
                    subtitles.choose(result) { error ->
                        fetching = null
                        if (error == null) onPicked() else failure.value = error
                    }
                }
            },
            detail = listOfNotNull(resultDetail(result, kindLabels), downloadedLabel.takeIf { onThisBox })
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            end = when {
                fetching == result.fileId -> RowEnd.Waiting(downloading)
                onThisBox -> RowEnd.Mark(Icons.Filled.DownloadDone)
                else -> null
            },
        )
    }
    val problem = downloadFailure ?: problemIn(results)
    val waiting = waitingFor(results)?.let { stringResource(it) }
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

/** The search being waited for, said where the rows will be. A download waits on its own row. */
private fun waitingFor(results: VodSubtitles.Results): Int? = R.string.opensubtitles_searching.takeIf {
    results is VodSubtitles.Results.Searching || results is VodSubtitles.Results.Idle
}

@Composable
internal fun kindLabels(): Map<SubtitleKind, String> = mapOf(
    SubtitleKind.HEARING_IMPAIRED to stringResource(R.string.opensubtitles_hearing_impaired),
    SubtitleKind.FORCED to stringResource(R.string.opensubtitles_forced),
)

private fun resultDetail(result: SubtitleResult, kindLabels: Map<SubtitleKind, String>): String =
    listOfNotNull(result.release.takeIf { it.isNotBlank() }, kindLabels[result.kind]).joinToString(" · ")
