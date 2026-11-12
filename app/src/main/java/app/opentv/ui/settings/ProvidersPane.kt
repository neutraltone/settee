/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Source
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.PanelTitle
import app.opentv.ui.design.SidePanel

/**
 * Providers: a row per provider, which OK edits, and a row to add one. A provider's other actions
 * are in its options.
 */
@Composable
internal fun ProvidersPane(
    onAddProvider: () -> Unit,
    onEditProvider: (sourceId: Long) -> Unit,
    onRowGone: () -> Unit,
    viewModel: SourcesViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsState()
    var options by remember { mutableStateOf<Source?>(null) }
    var removing by remember { mutableStateOf<Source?>(null) }
    var gone by remember { mutableStateOf<Long?>(null) }
    ui.sources.forEach { source ->
        // Keyed, so the Highlight stays on its provider when the list changes around it.
        key(source.id) {
            val row = screenRow("provider:${source.id}") { onEditProvider(source.id) }
            ListRow(
                source.name,
                onClick = row.open,
                modifier = row.modifier,
                note = hostOnly(source.url),
                onOptions = { options = source },
            )
        }
    }
    val add = screenRow("add-provider", onAddProvider)
    ListRow(stringResource(R.string.settings_row_add_provider), onClick = add.open, modifier = add.modifier, opens = true)

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
            gone = source.id
            viewModel.delete(source)
        })
    }
    // Only once the list no longer has it: moving the Highlight sooner could land on the very row
    // that's about to go.
    LaunchedEffect(ui.sources, gone) {
        val id = gone ?: return@LaunchedEffect
        if (ui.sources.none { it.id == id }) {
            gone = null
            withFrameNanos {}
            onRowGone()
        }
    }
}

/** Host only: the full URL carries the login and has no place on a settings list. */
internal fun hostOnly(url: String): String = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
