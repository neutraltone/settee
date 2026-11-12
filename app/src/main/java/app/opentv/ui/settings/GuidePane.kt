/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.core.ViewerError
import app.opentv.data.model.EpgFeed
import app.opentv.ui.EpgViewModel
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.PanelTitle
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.SidePanel
import app.opentv.ui.design.Text

/**
 * TV guide: updating it now, a row per guide (the provider's, the free ones and the viewer's own),
 * and adding one's own. OK on a guide opens its details, where it's turned on or off and, if it's
 * the viewer's own, removed.
 */
@Composable
internal fun GuidePane(viewModel: EpgViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsState()
    var details by remember { mutableStateOf<Long?>(null) }
    var removing by remember { mutableStateOf<EpgFeed?>(null) }
    var adding by remember { mutableStateOf(false) }
    val on = stringResource(R.string.settings_row_on)
    val off = stringResource(R.string.settings_row_off)

    ListRow(
        stringResource(if (ui.syncing) R.string.settings_guide_updating else R.string.settings_guide_update),
        onClick = viewModel::refresh,
        note = ui.statusLine,
    )
    ui.feeds.forEach { feed ->
        // Keyed, so the Highlight stays on its guide when the free ones arrive and the list
        // reorders, rather than staying in place over whichever guide moves there.
        key(feed.id) {
            ListRow(
                feed.name,
                onClick = { details = feed.id },
                value = if (feed.enabled) on else off,
                note = ViewerError.fromStored(feed.lastResult)?.sentence ?: feed.lastResult.ifBlank { null },
                detail = ViewerError.fromStored(feed.lastResult)?.detail,
                onOptions = { details = feed.id },
            )
        }
    }
    ListRow(stringResource(R.string.settings_guide_add_own), onClick = { adding = true }, opens = true)

    details?.let { id ->
        SidePanel(onBack = { details = null }) {
            // By id, so the panel follows the guide as it's turned on or off.
            val feed = ui.feeds.firstOrNull { it.id == id } ?: return@SidePanel
            PanelTitle(feed.name)
            PanelRow(
                stringResource(R.string.settings_guide_use),
                onClick = { viewModel.setEnabled(feed, !feed.enabled) },
                value = if (feed.enabled) on else off,
            )
            if (feed.isOwn) {
                PanelRow(stringResource(R.string.common_remove), onClick = {
                    details = null
                    removing = feed
                })
            }
            LastResult(feed)
        }
    }
    removing?.let { feed ->
        RemoveQuestion(feed.name, onKeep = { removing = null }, onRemove = {
            removing = null
            viewModel.remove(feed)
        })
    }
    if (adding) {
        EntryWindow(
            title = stringResource(R.string.settings_guide_own_address),
            initial = "",
            keyboardType = KeyboardType.Uri,
            onSave = { url ->
                adding = false
                // Named by its host: the full address is the row's label otherwise, and a
                // provider's guide link can carry the login.
                viewModel.addCustom(hostOnly(url.trim()), url)
            },
            onCancel = { adding = false },
        )
    }
}

/** How the guide's last download went: what it found, or why it failed with its detail line. */
@Composable
private fun LastResult(feed: EpgFeed) {
    val failure = ViewerError.fromStored(feed.lastResult)
    val padding = Modifier.padding(top = 12.dp)
    when {
        failure != null ->
            ErrorMessage(failure.sentence, failure.detail, modifier = padding, textAlign = TextAlign.Start)
        feed.lastResult.isNotBlank() ->
            Text(feed.lastResult, SetteeType.Body, color = SetteeColors.QuietText, modifier = padding)
    }
}

/** The viewer's own guides can be removed; the provider's and the free ones only turned off. */
private val EpgFeed.isOwn: Boolean get() = !builtIn && providerSourceId == null
