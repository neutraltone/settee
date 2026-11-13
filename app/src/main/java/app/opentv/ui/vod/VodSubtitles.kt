/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.media3.common.text.Cue
import app.opentv.data.model.SubtitleKind
import app.opentv.data.remote.OpenSubtitlesFailure
import app.opentv.data.remote.SubtitleResult
import app.opentv.data.repo.DownloadedSubtitle
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.OpenSubtitles
import app.opentv.data.repo.ProfileSettings
import app.opentv.data.repo.SubtitlePreference
import app.opentv.data.repo.SubtitleSubject
import app.opentv.player.AutoSubtitles
import app.opentv.player.DownloadedSubtitleCues
import app.opentv.player.orderSubtitleResults
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The film and episode players' OpenSubtitles side: the title's Downloaded Subtitles, the one
 * showing and its Timing, searching and picking a result, and fetching one automatically. Holds no
 * player: the screen hands it what the stream offers and draws what [cuesAt] returns, and
 * [streamTextOff] turns the stream's own subtitles off whenever a Downloaded Subtitle shows, however
 * it came to, so the two never draw over each other.
 */
class VodSubtitles(
    private val openSubtitles: OpenSubtitles,
    private val profileSettings: ProfileSettings,
    private val scope: CoroutineScope,
    private val streamTextOff: () -> Unit,
    private val device: () -> String = { Locale.getDefault().language },
) {
    sealed interface Results {
        data object Idle : Results

        data object Searching : Results

        data class Found(val results: List<SubtitleResult>) : Results

        data class Failed(val error: Throwable) : Results
    }

    /** A brief word over the picture about a subtitle fetched by itself: that it came, or why it didn't. */
    sealed interface Note {
        data object Fetched : Note

        data class Failed(val error: Throwable) : Note
    }

    /** What's showing, and its lines. */
    class Showing(val subtitle: DownloadedSubtitle, internal val cues: DownloadedSubtitleCues)

    private val _subject = MutableStateFlow<SubtitleSubject?>(null)

    /** The title OpenSubtitles is asked about; null for anything that isn't a film or an episode. */
    val subject: StateFlow<SubtitleSubject?> = _subject.asStateFlow()

    private val _downloaded = MutableStateFlow<List<DownloadedSubtitle>?>(null)

    /** The title's Downloaded Subtitles; null until they're known. */
    val downloaded: StateFlow<List<DownloadedSubtitle>?> = _downloaded.asStateFlow()

    private val _showing = MutableStateFlow<Showing?>(null)
    val showing: StateFlow<Showing?> = _showing.asStateFlow()

    private val _results = MutableStateFlow<Results>(Results.Idle)
    val results: StateFlow<Results> = _results.asStateFlow()

    private val _note = MutableStateFlow<Note?>(null)
    val note: StateFlow<Note?> = _note.asStateFlow()

    /** Whether the title playing has had its one chance to fetch a subtitle by itself. */
    private var autoDecided = false
    private var loading: Job? = null

    /** A new title is playing: forget the last one's subtitles and look up this one's. */
    fun load(key: MediaKey?) {
        loading?.cancel()
        _subject.value = null
        _downloaded.value = null
        _showing.value = null
        _results.value = Results.Idle
        _note.value = null
        autoDecided = false
        if (key == null) return
        loading = scope.launch {
            val subject = openSubtitles.subjectFor(key)
            _subject.value = subject
            _downloaded.value = subject?.let { openSubtitles.store.forTitle(it.title) }.orEmpty()
        }
    }

    /** Shows [subtitle]. [picked] when the viewer chose it, which updates the Subtitle Preference. */
    fun show(subtitle: DownloadedSubtitle, picked: Boolean) {
        scope.launch {
            val cues = withContext(Dispatchers.IO) { DownloadedSubtitleCues.parse(subtitle.file.readText()) }
            streamTextOff()
            _showing.value = Showing(subtitle, cues)
            openSubtitles.store.played(subtitle)
            if (picked) profileSettings.pickSubtitleTrack(subtitle.language, subtitle.kind)
        }
    }

    /** Stops drawing the Downloaded Subtitle (Off, or a stream's own track was picked). */
    fun hide() {
        _showing.value = null
    }

    /** Looks for subtitles for the title, ordered for the active Profile. */
    fun search() {
        val subject = _subject.value ?: return
        _results.value = Results.Searching
        scope.launch {
            val preferred = profileSettings.subtitlePreference.first().language
            _results.value = openSubtitles.search(subject).fold(
                onSuccess = { Results.Found(orderSubtitleResults(it, preferred, device())) },
                onFailure = { Results.Failed(it) },
            )
        }
    }

    /** Downloads [result], keeps it, and shows it. [onDone] hears whether it worked. */
    fun choose(result: SubtitleResult, onDone: (Throwable?) -> Unit) {
        val subject = _subject.value ?: return
        scope.launch {
            openSubtitles.fetch(subject, result).fold(
                onSuccess = { saved ->
                    keep(saved)
                    show(saved, picked = true)
                    onDone(null)
                },
                onFailure = onDone,
            )
        }
    }

    /** Moves the showing subtitle [steps] tenths of a second, later when positive, and keeps it. */
    fun nudge(steps: Int) {
        val current = _showing.value ?: return
        val moved = current.subtitle.copy(offsetMillis = current.subtitle.offsetMillis + steps * STEP_MILLIS)
        _showing.value = Showing(moved, current.cues)
        keep(moved)
        scope.launch { openSubtitles.store.setOffset(moved, moved.offsetMillis) }
    }

    /** The lines to draw at [positionMillis] of the video. */
    fun cuesAt(positionMillis: Long): List<Cue> =
        _showing.value?.let { it.cues.at(positionMillis, it.subtitle.offsetMillis) }.orEmpty()

    /**
     * Once the stream's tracks and the title's Downloaded Subtitles are known: fetches the top result
     * in the Profile's language and kind when [AutoSubtitles] says to. Quiet but for [note].
     */
    fun autoFetch(preference: SubtitlePreference?, streamLanguages: List<String?>) {
        val subject = _subject.value ?: return
        val language = autoLanguage(preference, streamLanguages) ?: return
        val kind = preference?.kind ?: SubtitleKind.STANDARD
        scope.launch {
            val outcome = openSubtitles.search(subject).mapCatching { results ->
                val best = AutoSubtitles.pick(results, language, kind) ?: throw OpenSubtitlesFailure.NoResults()
                openSubtitles.fetch(subject, best).getOrThrow()
            }
            outcome.fold(
                onSuccess = { saved ->
                    keep(saved)
                    show(saved, picked = false)
                    _note.value = Note.Fetched
                },
                onFailure = { _note.value = Note.Failed(it) },
            )
        }
    }

    /** The language to fetch by itself, decided once a title and only once everything it rests on is known. */
    private fun autoLanguage(preference: SubtitlePreference?, streamLanguages: List<String?>): String? {
        val downloaded = _downloaded.value
        if (downloaded == null || preference == null || autoDecided) return null
        autoDecided = true
        return AutoSubtitles(device()).languageToFetch(
            signedIn = openSubtitles.account.signedInAs.value != null,
            autoOn = openSubtitles.account.autoDownload.value,
            preference = preference,
            streamLanguages = streamLanguages,
            hasDownloaded = downloaded.isNotEmpty(),
        )
    }

    /** Keeps the title's list in step with a subtitle just saved or adjusted. */
    private fun keep(subtitle: DownloadedSubtitle) {
        val others = _downloaded.value.orEmpty().filterNot { it.fileId == subtitle.fileId }
        _downloaded.value = others + subtitle
    }

    private companion object {
        const val STEP_MILLIS = 100L
    }
}
