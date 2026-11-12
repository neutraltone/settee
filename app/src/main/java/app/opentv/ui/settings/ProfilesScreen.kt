/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Profile
import app.opentv.data.repo.Profiles
import app.opentv.ui.ProfilesViewModel
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.PanelTitle
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.SidePanel
import app.opentv.ui.design.Text

/**
 * Profiles, opened from the Rail's Profile: who watches on this TV. OK on a Profile renames it;
 * switching to it and removing it are in its options. The active Profile decides whose Continue
 * Watching, watch state and Favourites are in play across the app.
 */
@Composable
fun ProfilesScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: ProfilesViewModel = viewModel()) {
    val profiles by viewModel.profiles.collectAsState()
    val activeId by viewModel.activeProfileId.collectAsState()
    var naming by remember { mutableStateOf<Long?>(null) }
    var options by remember { mutableStateOf<Profile?>(null) }
    var removing by remember { mutableStateOf<Profile?>(null) }
    val rows = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    LaunchedEffect(profiles.isNotEmpty()) {
        if (profiles.isEmpty()) return@LaunchedEffect
        // A frame first: until the input mode has gone to keyboard, nothing can take focus.
        withFrameNanos {}
        runCatching { rows.requestFocus() }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(SetteeColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SetteeSpace.ScreenSide, vertical = SetteeSpace.ScreenTopBottom)
            // Removing the Profile with the Highlight takes its row away, and nothing else would
            // take the Highlight: back into the list it goes.
            .focusProperties { onEnter = { runCatching { rows.requestFocus() } } }
            .focusGroup(),
    ) {
        Text(stringResource(R.string.profiles_title), SetteeType.ScreenTitle)
        Text(stringResource(R.string.profiles_hint), SetteeType.Body, color = SetteeColors.QuietText)
        Spacer(Modifier.height(SetteeSpace.RowGap))
        Column(
            Modifier.widthIn(max = LIST_WIDTH).focusRequester(rows).focusGroup(),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            profiles.forEach { profile ->
                key(profile.id) {
                    ListRow(
                        profile.name,
                        onClick = { naming = profile.id },
                        note = if (profile.id == activeId) stringResource(R.string.profiles_watching_now) else null,
                        onOptions = { options = profile },
                    )
                }
            }
            ListRow(stringResource(R.string.profiles_add_row), onClick = { naming = NEW }, opens = true)
        }
    }

    options?.let { profile ->
        SidePanel(onBack = { options = null }) {
            PanelTitle(profile.name)
            PanelRow(stringResource(R.string.profiles_watch_as, profile.name), onClick = {
                options = null
                viewModel.select(profile.id)
            })
            if (profile.id != Profiles.BASE_PROFILE_ID) {
                PanelRow(stringResource(R.string.common_remove), onClick = {
                    options = null
                    removing = profile
                })
            }
        }
    }
    removing?.let { profile ->
        RemoveQuestion(profile.name, onKeep = { removing = null }, onRemove = {
            removing = null
            viewModel.remove(profile.id)
        })
    }
    naming?.let { target ->
        val resources = LocalResources.current
        EntryWindow(
            title = stringResource(if (target == NEW) R.string.profiles_new_heading else R.string.profiles_rename_heading),
            initial = if (target == NEW) "" else profiles.firstOrNull { it.id == target }?.name.orEmpty(),
            maxLength = MAX_NAME,
            refusal = { name ->
                if (Profiles.isNameTaken(name, profiles, except = target)) {
                    resources.getString(R.string.profiles_name_taken, name.trim())
                } else {
                    null
                }
            },
            onSave = { name ->
                naming = null
                if (target == NEW) viewModel.addProfile(name) else viewModel.rename(target, name)
            },
            onCancel = { naming = null },
        )
    }
}

private const val MAX_NAME = 24
private const val NEW = -1L
private val LIST_WIDTH = 760.dp
private val ROW_GAP = 8.dp
