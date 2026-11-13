/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.hardware
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VersionTraits
import app.opentv.data.repo.VodQualities
import app.opentv.isRunningOnTelevision
import app.opentv.ui.design.ConfirmPanel
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.PanelTitle
import app.opentv.ui.design.SidePanel
import java.util.Locale

/**
 * What a film's or show's page shows of its Versions (#181): the Quality pill's [choices], the
 * [otherLanguage] Play asks about before playing, and [cantPlay], the Quality nothing of it plays
 * below, when nothing does.
 */
@Immutable
data class PageVersions(
    val choices: List<VodQualities.Choice> = emptyList(),
    val otherLanguage: OtherLanguage? = null,
    val cantPlay: String? = null,
) {
    /** Two or more entries: anything less leaves nothing to choose. */
    val showsPill: Boolean get() = choices.size > 1

    /** The Version in another language Play would ask to play, in [language] when its name says. */
    data class OtherLanguage(val key: MediaKey, val language: String?)

    companion object {
        /** What [plan] and [choices] mean for a page. */
        fun of(plan: VodQualities.Plan, choices: List<VodQualities.Choice>, playsNothing: Boolean) = PageVersions(
            choices = choices,
            otherLanguage = plan.otherLanguages.firstOrNull()?.let { OtherLanguage(it.key, plan.otherLanguage) },
            cantPlay = plan.start.label.takeIf { playsNothing && it.isNotEmpty() },
        )
    }
}

/**
 * What a page opens over its Versions: the Quality pill's panel while [choosingQuality], and while
 * [askingLanguage], the question before playing another language, whose yes chooses that Version and
 * plays it. Either way the prompt then closes with [onClose].
 */
@Composable
internal fun VersionPrompts(
    versions: PageVersions,
    choosingQuality: Boolean,
    askingLanguage: Boolean,
    onChooseVersion: (MediaKey) -> Unit,
    onPlay: () -> Unit,
    onClose: () -> Unit,
) {
    if (choosingQuality) {
        QualityPanel(
            versions.choices,
            onChoose = {
                onClose()
                onChooseVersion(it)
            },
            onDismiss = onClose,
        )
    }
    versions.otherLanguage?.takeIf { askingLanguage }?.let { other ->
        LanguageQuestion(
            other.language,
            // Closing last: what's playing may be held only until the prompt closes.
            onPlay = {
                onChooseVersion(other.key)
                onPlay()
                onClose()
            },
            onKeep = onClose,
        )
    }
}

/**
 * The Quality pill's panel: one row per way the Versions differ, the one playing ticked, and those this
 * device can't play greyed out and passed over.
 */
@Composable
internal fun QualityPanel(choices: List<VodQualities.Choice>, onChoose: (MediaKey) -> Unit, onDismiss: () -> Unit) {
    SidePanel(onBack = onDismiss) {
        PanelTitle(stringResource(R.string.vod_quality))
        choices.forEach { choice ->
            PanelRow(
                label = versionLabel(choice.traits),
                onClick = { onChoose(choice.key) },
                ticked = choice.playing,
                enabled = choice.playable,
            )
        }
    }
}

/** Asks before playing a Version in another language, which Settee never switches to on its own. */
@Composable
private fun LanguageQuestion(language: String?, onPlay: () -> Unit, onKeep: () -> Unit) {
    val context = LocalContext.current
    val onTelevision = isRunningOnTelevision(context)
    val name = language?.let(::languageName)
    ConfirmPanel(
        title = name?.let { stringResource(R.string.vod_language_title, it) }
            ?: stringResource(R.string.vod_language_title_unnamed),
        body = stringResource(hardware(onTelevision, R.string.vod_language_body_tv, R.string.vod_language_body_device)),
        confirm = name?.let { stringResource(R.string.vod_language_play, it) }
            ?: stringResource(R.string.vod_language_play_unnamed),
        keep = stringResource(R.string.vod_language_keep),
        onConfirm = onPlay,
        onKeep = onKeep,
    )
}

/** Why Play is off, under it: "This TV can't play 4K". */
@Composable
internal fun cantPlayText(quality: String): String {
    val onTelevision = isRunningOnTelevision(LocalContext.current)
    return stringResource(hardware(onTelevision, R.string.vod_cant_play_tv, R.string.vod_cant_play_device), quality)
}

/**
 * A Version as the pill names it: "4K", "HD · German", "HD · Italian subtitles", "CAM", or "Other" when
 * nothing tells it apart. Never "Version", a word that isn't on screen.
 */
@Composable
internal fun versionLabel(traits: VersionTraits): String {
    val parts = listOfNotNull(
        traits.qualityLabel.takeIf { it.isNotEmpty() },
        if (traits.cam) stringResource(R.string.vod_quality_cam) else null,
        traits.language?.let(::languageName),
        if (traits.dubbed && traits.language == null) stringResource(R.string.vod_quality_dubbed) else null,
        traits.subtitles?.let {
            if (it == VersionTraits.MULTI) {
                stringResource(R.string.vod_quality_subtitles_multi)
            } else {
                stringResource(R.string.vod_quality_subtitles, languageName(it))
            }
        },
        traits.sound,
    )
    return parts.joinToString(" · ").ifEmpty { stringResource(R.string.vod_quality_other) }
}

/** A language's name in the viewer's own language: `de` is "German" to an English viewer. */
private fun languageName(code: String): String {
    val shown = Locale.getDefault()
    return Locale.forLanguageTag(code).getDisplayLanguage(shown).replaceFirstChar { it.titlecase(shown) }
}
