/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.core.findActivity
import app.opentv.data.model.Source
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.ListRowInset
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.PanelTitle
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.SidePanel
import app.opentv.ui.design.Text
import app.opentv.ui.providerFailures

/**
 * Providers: a row per provider, which OK edits, and a row to add one. A provider's other actions
 * are in its options.
 */
@Composable
internal fun ProvidersPane(
    onAddProvider: () -> Unit,
    onEditProvider: (sourceId: Long) -> Unit,
    // The activity's, the one the rest of the app uses: one per visit to Settings would collect the
    // refresh's progress again, and announce it in the main screen's corner beside the app's own.
    viewModel: SourcesViewModel = viewModel(viewModelStoreOwner = activityOwner()),
) {
    val ui by viewModel.ui.collectAsState()
    var options by remember { mutableStateOf<Source?>(null) }
    var removing by remember { mutableStateOf<Source?>(null) }
    val failures = ui.sources.providerFailures().associateBy { it.sourceId }
    ui.sources.forEach { source ->
        // Keyed, so the Highlight stays on its provider when the list changes around it.
        key(source.id) {
            val row = screenRow(providerRow(source.id)) { onEditProvider(source.id) }
            val failure = failures[source.id]?.error
            var againHighlighted by remember { mutableStateOf(false) }
            // Read as the failure goes, before Refresh again leaves: with the Highlight on it, Compose
            // would hand it to the top of the list, so it goes back to this provider's row instead.
            val giveBack = failure == null && againHighlighted
            LaunchedEffect(failure) {
                if (!giveBack) return@LaunchedEffect
                againHighlighted = false
                withFrameNanos {}
                runCatching { row.requester.requestFocus() }
            }
            ListRow(
                source.name,
                onClick = row.open,
                modifier = row.modifier,
                note = hostOnly(source.url),
                onOptions = { options = source },
            )
            failure?.let { failure ->
                // Lined up with the row's label, and kept under it, so it reads as that provider's.
                ErrorMessage(
                    failure.sentence,
                    failure.detail,
                    Modifier.padding(horizontal = ListRowInset, vertical = 6.dp),
                    textAlign = TextAlign.Start,
                )
                // Every provider, as the refresh row does: refreshing one alone isn't worth its own path.
                ListRow(
                    stringResource(R.string.providers_refresh_again),
                    onClick = viewModel::refreshAll,
                    modifier = Modifier.onFocusChanged { againHighlighted = it.isFocused },
                )
            }
        }
    }
    val add = screenRow("add-provider", onAddProvider)
    ListRow(
        stringResource(R.string.settings_row_add_provider),
        onClick = add.open,
        modifier = add.modifier,
        opens = true,
    )
    // Only once the list has loaded, so it doesn't flash up before the providers do.
    if (ui.loaded && ui.sources.isEmpty()) {
        Text(
            stringResource(R.string.providers_empty),
            SetteeType.Body,
            color = SetteeColors.QuietText,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
    // Background work, so leaving Settings doesn't cut it short. The row itself says it's running and
    // then how it went, and each provider's failure appears under its own row as it's saved.
    ListRow(
        stringResource(R.string.settings_row_refresh_content),
        onClick = viewModel::refreshAll,
        note = if (ui.refreshing) stringResource(R.string.refresh_refreshing) else ui.refreshResult,
    )

    options?.let { source ->
        SidePanel(onBack = { options = null }) {
            PanelTitle(source.name)
            PanelRow(stringResource(R.string.common_remove), onClick = {
                options = null
                removing = source
            })
        }
    }
    removing?.let { source ->
        RemoveQuestion(source.name, onKeep = { removing = null }, onRemove = {
            removing = null
            viewModel.delete(source)
        })
    }
}

/** A provider's row, as [SettingsMemory.openedRow] names it. */
internal fun providerRow(sourceId: Long) = "provider:$sourceId"

/** The activity, which owns the view models the app shares across its screens. */
@Composable
private fun activityOwner(): ViewModelStoreOwner =
    LocalContext.current.findActivity() as? ViewModelStoreOwner ?: checkNotNull(LocalViewModelStoreOwner.current)
