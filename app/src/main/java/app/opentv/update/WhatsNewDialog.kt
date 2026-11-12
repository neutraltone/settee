/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.update

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.BuildConfig
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.ui.design.NotesPanel
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows [WhatsNewDialog] on the first launch after an upgrade, once [canShow] allows it, and stores
 * the installed version when it is dismissed. A launch with nothing to show stores it straight away.
 */
@Composable
fun WhatsNewGate(canShow: Boolean) {
    val context = LocalContext.current
    val settings = remember { ServiceLocator.get(context).settings }
    var pending by remember { mutableStateOf<List<VersionNotes>>(emptyList()) }
    LaunchedEffect(Unit) {
        val notes = withContext(Dispatchers.IO) {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            WhatsNew.notesToShow(
                installed = BuildConfig.VERSION_NAME,
                lastSeen = settings.lastSeenVersion,
                firstInstallTime = pkg.firstInstallTime,
                lastUpdateTime = pkg.lastUpdateTime,
                changelog = context.bundledChangelog(),
            )
        }
        if (notes.isEmpty()) settings.lastSeenVersion = BuildConfig.VERSION_NAME else pending = notes
    }
    if (canShow && pending.isNotEmpty()) {
        WhatsNewDialog(BuildConfig.VERSION_NAME, pending) {
            settings.lastSeenVersion = BuildConfig.VERSION_NAME
            pending = emptyList()
        }
    }
}

/** The `CHANGELOG.md` the build copies into assets; blank if it is somehow missing, so nothing shows. */
fun Context.bundledChangelog(): String =
    runCatching { assets.open("CHANGELOG.md").bufferedReader().use { it.readText() } }.getOrDefault("")

/** "What's new in x.y.z": the notes for every version since the last one this device saw. */
@Composable
fun WhatsNewDialog(installedVersion: String, notes: List<VersionNotes>, onDismiss: () -> Unit) {
    NotesPanel(
        title = stringResource(R.string.whats_new_title, installedVersion),
        main = stringResource(R.string.whats_new_dismiss),
        onMain = onDismiss,
        onBack = onDismiss,
    ) {
        if (notes.isEmpty()) {
            Text(stringResource(R.string.whats_new_nothing), SetteeType.Body)
        } else {
            ReleaseNotesBody(notes)
        }
    }
}

/**
 * Takes focus when it first appears, where Compose would otherwise highlight the first thing in focus
 * order. It waits a frame first, as Home does: on a full screen the item isn't placed yet on the frame it
 * first composes, and asking then is refused.
 */
@Composable
fun Modifier.startsFocused(): Modifier {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) {
        withFrameNanos {}
        runCatching { focus.requestFocus() }
    }
    return focusRequester(focus)
}

/** Release notes as a viewer reads them; shared by the What's new and Update available panels. */
@Composable
fun ReleaseNotesBody(notes: List<VersionNotes>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        notes.forEach { version ->
            // One version needs no label: the panel's title already names it.
            if (notes.size > 1) {
                Text(version.version, SetteeType.Section, modifier = Modifier.padding(top = 8.dp))
            }
            version.sections.forEach { section ->
                Text(
                    stringResource(section.heading.label),
                    SetteeType.CardTitle,
                    color = SetteeColors.Accent,
                    modifier = Modifier.padding(top = 4.dp),
                )
                section.entries.forEach { Text("• $it", SetteeType.Body) }
            }
        }
    }
}

private val NotesHeading.label: Int
    get() = when (this) {
        NotesHeading.HeadsUp -> R.string.whats_new_heads_up
        NotesHeading.NewFeatures -> R.string.whats_new_new_features
        NotesHeading.BugFixes -> R.string.whats_new_bug_fixes
        NotesHeading.PerformanceImprovements -> R.string.whats_new_performance
    }
