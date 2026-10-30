/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.repo.Profiles
import app.opentv.ui.ProfilesViewModel
import app.opentv.ui.channels.DeviceTextField

/**
 * Who's watching. Add, rename, remove and switch between local profiles; the active one decides
 * whose continue-watching and watched state is in play across the app.
 */
@Composable
fun ProfilesScreen(onBack: () -> Unit, viewModel: ProfilesViewModel = viewModel()) {
    val profiles by viewModel.profiles.collectAsState()
    val activeId by viewModel.activeProfileId.collectAsState()

    // null = list; NEW = adding; a positive id = renaming that profile.
    var editing by remember { mutableStateOf<Long?>(null) }

    editing?.let { target ->
        ProfileNameEntry(
            initial = if (target == NEW) "" else profiles.firstOrNull { it.id == target }?.name.orEmpty(),
            heading = if (target ==
                NEW
            ) {
                stringResource(R.string.profiles_new_heading)
            } else {
                stringResource(R.string.profiles_rename_heading)
            },
            isTaken = { Profiles.isNameTaken(it, profiles, except = target) },
            onCancel = { editing = null },
            onSave = { name ->
                if (target == NEW) viewModel.addProfile(name) else viewModel.rename(target, name)
                editing = null
            },
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.profiles_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = { editing = NEW }) { Text(stringResource(R.string.profiles_add)) }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        Spacer(Modifier.height(20.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.widthIn(max = 760.dp),
        ) {
            items(profiles, key = { it.id }) { profile ->
                val active = profile.id == activeId
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                profile.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (active) {
                                Text(
                                    stringResource(R.string.profiles_active),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (!active) {
                            TextButton(onClick = {
                                viewModel.select(profile.id)
                            }) { Text(stringResource(R.string.profiles_use)) }
                        }
                        TextButton(onClick = {
                            editing = profile.id
                        }) { Text(stringResource(R.string.profiles_rename)) }
                        if (profile.id != Profiles.BASE_PROFILE_ID) {
                            TextButton(onClick = {
                                viewModel.remove(profile.id)
                            }) { Text(stringResource(R.string.common_remove)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Naming a new profile or renaming one: a box for the name with Save and Cancel above it. A name
 * [isTaken] by another profile can't be saved, and the line under the box says why.
 */
@Composable
internal fun ProfileNameEntry(
    initial: String,
    heading: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
    isTaken: (String) -> Boolean = { false },
) {
    val name = rememberTextFieldState(initial)
    val nameBox = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { nameBox.requestFocus() } }
    val taken = isTaken(name.text.toString())
    val canSave = name.text.isNotBlank() && !taken
    val save = { if (canSave) onSave(name.text.toString()) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(heading, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            Button(enabled = canSave, onClick = save) { Text(stringResource(R.string.common_save)) }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        }
        Spacer(Modifier.height(24.dp))
        DeviceTextField(
            state = name,
            placeholder = stringResource(R.string.profiles_name_placeholder),
            maxLength = MAX_NAME,
            onSubmit = save,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().focusRequester(nameBox),
        )
        if (taken) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.profiles_name_taken, name.text.toString().trim()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private const val MAX_NAME = 24
private const val NEW = -1L
