/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.opentv.BuildConfig
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.update.UpdateChecker
import app.opentv.update.UpdateHub
import app.opentv.update.UpdateUiState
import app.opentv.update.VersionNotes
import app.opentv.update.WhatsNew
import app.opentv.update.WhatsNewDialog
import app.opentv.update.bundledChangelog
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How About checks for an update: GitHub's latest release, unless a test gives its own answer. */
typealias UpdateCheck = suspend (Context) -> UpdateChecker.Outcome

internal val CheckGitHub: UpdateCheck = { context ->
    val graph = ServiceLocator.get(context)
    UpdateChecker(graph.httpClient, BuildConfig.VERSION_NAME, dispatchers = graph.dispatchers).check()
}

/**
 * About: what Settee is, its version and updates, and its licences. The update check here is the
 * manual counterpart to the automatic one: someone who just heard a fix landed can pull it now
 * instead of waiting for the periodic check.
 */
@Composable
internal fun AboutPane(onOpenFontLicences: () -> Unit, checkForUpdate: UpdateCheck) {
    val context = LocalContext.current
    val io = remember { ServiceLocator.get(context).dispatchers.io }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var checkNote by remember { mutableStateOf<String?>(null) }
    var checkDetail by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var whatsNew by remember { mutableStateOf<List<VersionNotes>?>(null) }

    Text(stringResource(R.string.about_what_is_body), SetteeType.Body, color = SetteeColors.QuietText)
    Text(
        stringResource(R.string.about_built_open_body),
        SetteeType.Body,
        color = SetteeColors.QuietText,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    ListRow(
        stringResource(R.string.about_check_updates),
        onClick = {
            if (checking) return@ListRow
            checking = true
            checkNote = null
            checkDetail = null
            scope.launch {
                val outcome = checkForUpdate(context)
                checkNote = when (outcome) {
                    is UpdateChecker.Outcome.Available ->
                        resources.getString(R.string.about_update_available, outcome.update.versionName)
                    UpdateChecker.Outcome.UpToDate -> resources.getString(R.string.about_up_to_date)
                    is UpdateChecker.Outcome.CouldNotCheck -> resources.getString(R.string.about_check_failed)
                }
                checkDetail = (outcome as? UpdateChecker.Outcome.CouldNotCheck)?.detail
                // UpdateGate draws over every screen, so the install prompt appears over Settings.
                if (outcome is UpdateChecker.Outcome.Available) {
                    UpdateHub.state.value = UpdateUiState.Available(outcome.update, asked = true)
                }
                checking = false
            }
        },
        value = "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
        note = when {
            checking -> stringResource(R.string.about_checking)
            else -> checkNote ?: stringResource(R.string.about_based_on)
        },
        detail = checkDetail.takeUnless { checking },
    )
    ListRow(
        stringResource(R.string.about_whats_new),
        onClick = {
            scope.launch {
                whatsNew = withContext(io) {
                    WhatsNew.installedNotes(BuildConfig.VERSION_NAME, context.bundledChangelog())
                }
            }
        },
        opens = true,
    )
    ListRow(
        stringResource(R.string.about_licence_label),
        onClick = { context.openLink("https://www.gnu.org/licenses/gpl-3.0.html") },
        value = stringResource(R.string.settings_about_licence_value),
    )
    ListRow(
        stringResource(R.string.about_source_code),
        onClick = { context.openLink("https://github.com/neutraltone/settee") },
        value = "github.com/neutraltone/settee",
    )
    val fonts = screenRow("fonts", onOpenFontLicences)
    ListRow(
        stringResource(R.string.about_fonts_label),
        onClick = fonts.open,
        modifier = fonts.modifier,
        note = stringResource(R.string.about_fonts_value),
        opens = true,
    )

    TmdbAttribution(Modifier.padding(top = 12.dp))
    // IntroDB's data is ODbL, which asks for this; TheIntroDB may ask for credit, and gets it anyway.
    Text(
        stringResource(R.string.timings_attribution),
        SetteeType.Body,
        color = SetteeColors.QuietText,
        modifier = Modifier.padding(top = 12.dp),
    )

    whatsNew?.let { WhatsNewDialog(BuildConfig.VERSION_NAME, it) { whatsNew = null } }
}

/** Opens [url] in a browser, or shows it where there isn't one, as on some TV boxes. */
private fun Context.openLink(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(this, url, Toast.LENGTH_LONG).show() }
}

/** TMDB's logo and the sentence its terms ask for. */
@Composable
private fun TmdbAttribution(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // Smaller than Settee's own mark anywhere, as TMDB's terms ask.
        Image(
            painterResource(R.drawable.tmdb_logo),
            contentDescription = stringResource(R.string.tmdb_logo_desc),
            modifier = Modifier.height(LOGO_HEIGHT),
        )
        Column(Modifier.padding(start = 16.dp)) {
            Text(stringResource(R.string.tmdb_attribution), SetteeType.Body, color = SetteeColors.QuietText)
        }
    }
}

private val LOGO_HEIGHT = 16.dp
