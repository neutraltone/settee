/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.onboarding

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.isRunningOnTelevision
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.design.TextField
import app.opentv.update.startsFocused

/**
 * Adding a provider, from first run, Settings or Live TV alike, and editing one.
 *
 * Two things this screen does that most IPTV players do not:
 *
 * 1. **Test before saving.** The user finds out the password is wrong here, with a sentence
 *    explaining what to change, rather than staring at an empty channel list later.
 * 2. **Says where the credentials go.** People are handing over their provider login. They
 *    are entitled to know it stays on the device, and to be told so without having to go
 *    looking for a privacy policy.
 */
@Composable
fun AddSourceScreen(
    viewModel: SourcesViewModel,
    onFinished: () -> Unit,
    editingSourceId: Long? = null,
    onSetup: () -> Unit = {},
) {
    val context = LocalContext.current
    // The QR flow serves a setup page FROM this device for a phone to fill in, so only a TV offers
    // it. There it comes first, because typing a server address and password with a D-pad is the
    // worst moment in every app of this kind.
    val isTelevision = remember(context) { isRunningOnTelevision(context) }
    val options = remember(isTelevision) { AddOption.entries.filter { isTelevision || it != AddOption.PHONE } }
    // What's open over the page, and which option the Highlight goes back to when it closes.
    var opened by rememberSaveable { mutableStateOf<AddOption?>(null) }
    var cameFrom by rememberSaveable { mutableStateOf(options.first()) }

    if (editingSourceId != null) {
        ProviderForm(viewModel, onFinished, onSetup, editingSourceId, kind = null)
        return
    }
    when (val open = opened) {
        null -> ProviderPage(options, highlight = cameFrom) { option ->
            cameFrom = option
            opened = option
        }
        AddOption.PHONE -> PhonePairingScreen(
            // The TV stays on "Got it" while the channels load, and the phone waits with it: a
            // failed login is put right on the phone, which shows why, and the TV listens again.
            // Once the channels are in, a first provider carries on in Setup, already past its
            // first step; a later one goes to Home as before.
            onReceived = { received ->
                var setup = false
                viewModel.saveAndSync(
                    received.draft,
                    onSetup = { setup = true },
                    onChannels = { failure ->
                        received.answer(failure)
                        if (failure == null) if (setup) onSetup() else onFinished()
                    },
                )
            },
            onCancel = { opened = null },
        )
        else -> ProviderForm(viewModel, onFinished, onSetup, editingSourceId = null, kind = open.kind)
    }
    if (opened != null) BackHandler { opened = null }
}

/** The ways onto the page, in the order it lists them. */
private enum class AddOption(
    val kind: SourceKind?,
    val title: Int,
    val description: Int,
    /** What a provider added this way is called when the viewer leaves its name blank. */
    val defaultName: Int = R.string.onboarding_default_provider_name,
) {
    PHONE(null, R.string.pairing_setup_title, R.string.onboarding_phone_desc),
    XTREAM(SourceKind.XTREAM, R.string.onboarding_xtream_login, R.string.onboarding_xtream_desc),
    M3U(
        SourceKind.M3U,
        R.string.onboarding_m3u_url,
        R.string.onboarding_m3u_desc,
        defaultName = R.string.onboarding_default_playlist_name,
    ),
    STALKER(SourceKind.STALKER, R.string.onboarding_stalker_portal, R.string.onboarding_stalker_desc),
}

private val SourceKind.option: AddOption
    get() = AddOption.entries.first { it.kind == this }

/** The welcome and the choice of provider together (docs/LOOK.md, Onboarding). */
@Composable
private fun ProviderPage(options: List<AddOption>, highlight: AddOption, onOpen: (AddOption) -> Unit) {
    Centred {
        Text(stringResource(R.string.onboarding_add_provider_title), SetteeType.ScreenTitle)
        Text(stringResource(R.string.onboarding_add_provider_desc), SetteeType.Synopsis, color = SetteeColors.QuietText)
        Spacer(Modifier.height(8.dp))
        options.forEach { option ->
            OptionRow(option, if (option == highlight) Modifier.startsFocused() else Modifier) { onOpen(option) }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.onboarding_privacy_note), SetteeType.Body, color = SetteeColors.QuietText)
    }
}

@Composable
private fun OptionRow(option: AddOption, modifier: Modifier, onClick: () -> Unit) {
    HighlightSurface(onClick = onClick, style = HighlightStyle.Row, modifier = modifier.fillMaxWidth()) { highlighted ->
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stringResource(option.title),
                SetteeType.RowTitle,
                color = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text,
            )
            Text(
                stringResource(option.description),
                SetteeType.Body,
                color = if (highlighted) SetteeColors.OnAccent else SetteeColors.QuietText,
            )
        }
    }
}

/** A scrolling column down the middle of the screen, as the page and the forms are laid out. */
@Composable
private fun Centred(content: @Composable () -> Unit) = ScreenFrame {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/**
 * One kind of provider's details, typed on the device. [kind] is null when editing [editingSourceId],
 * whose own kind is used.
 */
@Composable
private fun ProviderForm(
    viewModel: SourcesViewModel,
    onFinished: () -> Unit,
    onSetup: () -> Unit,
    editingSourceId: Long?,
    kind: SourceKind?,
) {
    val ui by viewModel.ui.collectAsState()
    // The view model outlives the form, so without this one form would open on another's test result.
    // Only when it opens, not when it comes back from Setup: that's where a failed login says why.
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!opened) viewModel.clearFormMessages()
        opened = true
    }

    // When editing, find the source being edited and fill every field from it. Keyed on its id so
    // the fields fill once the list has loaded; a fresh add leaves everything blank.
    val existing = remember(editingSourceId, ui.sources) {
        editingSourceId?.let { id -> ui.sources.firstOrNull { it.id == id } }
    }
    // A provider removed before its form opened has nothing to fill the form with, so go back.
    LaunchedEffect(ui.loaded, existing) {
        if (editingSourceId != null && ui.loaded && existing == null) onFinished()
    }
    // Editing before the list has loaded: there's no kind yet to choose the fields by.
    val formKind = existing?.kind ?: kind ?: return
    // Saveable, so a phone turning round keeps what was typed (the form stays open, as `opened` is).
    val details = rememberSaveable(existing?.id, saver = ProviderDetails.saver(existing, formKind)) {
        ProviderDetails(existing, formKind)
    }
    val defaultName = stringResource(formKind.option.defaultName)

    Centred {
        Text(
            stringResource(if (existing != null) R.string.settings_edit_provider_title else formKind.option.title),
            SetteeType.ScreenTitle,
        )
        KindFields(details, Modifier.startsFocused())

        ui.testResult?.let { Text(it, SetteeType.Body) }
        ui.testError?.let { Text(it, SetteeType.Body, color = SetteeColors.Accent) }
        ui.syncMessage?.let { Text(it, SetteeType.Body) }

        FormButtons(viewModel, details, defaultName, onFinished, onSetup)

        AdvancedFields(details)

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.onboarding_privacy_note), SetteeType.Body, color = SetteeColors.QuietText)
    }
}

/** Test connection and Save and load, which work on [details] once it has what its kind needs. */
@Composable
private fun FormButtons(
    viewModel: SourcesViewModel,
    details: ProviderDetails,
    defaultName: String,
    onFinished: () -> Unit,
    onSetup: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    // Working ignores presses rather than disabling the buttons: one that isn't enabled can't
    // hold the Highlight, so the button just pressed would drop it and leave nothing highlighted.
    val working = ui.testing || ui.adding
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton(
            label = stringResource(
                if (ui.testing) R.string.onboarding_working else R.string.recset_test_connection,
            ),
            onClick = { if (!working) viewModel.test(details.draft(defaultName)) },
            enabled = details.canSubmit,
        )
        PillButton(
            label = stringResource(if (ui.adding) R.string.onboarding_working else R.string.onboarding_save_load),
            onClick = {
                if (!working) {
                    viewModel.saveAndSync(details.draft(defaultName), onSetup) { ok -> if (ok) onFinished() }
                }
            },
            enabled = details.canSubmit,
        )
    }
}

/**
 * What's typed into a form. Editing starts from [existing] and copies onto it, so its id, enabled
 * state, live format and last-sync stamp are kept; a fresh add starts blank (id 0, so save() inserts).
 */
@Stable
private class ProviderDetails(private val existing: Source?, val kind: SourceKind) {
    var name by mutableStateOf(existing?.name.orEmpty())
    var url by mutableStateOf(existing?.url.orEmpty())
    var username by mutableStateOf(existing?.username.orEmpty())
    var password by mutableStateOf(existing?.password.orEmpty())
    var mac by mutableStateOf(existing?.macAddress.orEmpty())
    var epgUrl by mutableStateOf(existing?.epgUrl.orEmpty())
    var userAgent by mutableStateOf(existing?.userAgent ?: Source.DEFAULT_USER_AGENT)

    val canSubmit: Boolean
        get() = url.isNotBlank() &&
            when (kind) {
                SourceKind.XTREAM -> username.isNotBlank() && password.isNotBlank()
                SourceKind.M3U -> true
                SourceKind.STALKER -> mac.isNotBlank()
            }

    fun draft(defaultName: String): Source = (existing ?: Source(name = "", kind = kind, url = "")).copy(
        name = name.ifBlank { defaultName },
        kind = kind,
        url = url,
        username = username.takeIf { it.isNotBlank() },
        password = password.takeIf { it.isNotBlank() },
        macAddress = mac.takeIf { it.isNotBlank() },
        epgUrl = epgUrl.takeIf { it.isNotBlank() },
        userAgent = userAgent.ifBlank { Source.DEFAULT_USER_AGENT },
    )

    companion object {
        /** Keeps what's typed across the screen being rebuilt; [existing] and [kind] come from the caller again. */
        fun saver(existing: Source?, kind: SourceKind) = mapSaver(
            save = {
                mapOf(
                    "name" to it.name,
                    "url" to it.url,
                    "username" to it.username,
                    "password" to it.password,
                    "mac" to it.mac,
                    "epgUrl" to it.epgUrl,
                    "userAgent" to it.userAgent,
                )
            },
            restore = { saved ->
                ProviderDetails(existing, kind).apply {
                    name = saved["name"] as String
                    url = saved["url"] as String
                    username = saved["username"] as String
                    password = saved["password"] as String
                    mac = saved["mac"] as String
                    epgUrl = saved["epgUrl"] as String
                    userAgent = saved["userAgent"] as String
                }
            },
        )
    }
}

/** The fields [details]' kind needs, and only those. [modifier] goes on the first, the name. */
@Composable
private fun KindFields(details: ProviderDetails, modifier: Modifier = Modifier) {
    val (urlLabel, urlHelp) = when (details.kind) {
        SourceKind.XTREAM -> R.string.onboarding_server_address to R.string.onboarding_server_help
        SourceKind.M3U -> R.string.onboarding_playlist_url to R.string.onboarding_playlist_help
        SourceKind.STALKER -> R.string.onboarding_portal_url to R.string.onboarding_portal_help
    }
    TextField(
        value = details.name,
        onValueChange = { details.name = it },
        label = stringResource(R.string.epg_name_optional),
        modifier = modifier.fillMaxWidth(),
    )
    TextField(
        value = details.url,
        onValueChange = { details.url = it },
        label = stringResource(urlLabel),
        supportingText = stringResource(urlHelp),
        trailingIcon = { PasteButton { details.url += it } },
        keyboardType = KeyboardType.Uri,
        modifier = Modifier.fillMaxWidth(),
    )
    if (details.kind == SourceKind.XTREAM) {
        TextField(
            value = details.username,
            onValueChange = { details.username = it },
            label = stringResource(R.string.recset_field_username),
            trailingIcon = { PasteButton { details.username += it } },
            modifier = Modifier.fillMaxWidth(),
        )
        TextField(
            value = details.password,
            onValueChange = { details.password = it },
            label = stringResource(R.string.recset_field_password),
            trailingIcon = { PasteButton { details.password += it } },
            keyboardType = KeyboardType.Password,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (details.kind == SourceKind.STALKER) {
        TextField(
            value = details.mac,
            onValueChange = { details.mac = it },
            label = stringResource(R.string.onboarding_mac_address),
            supportingText = stringResource(R.string.onboarding_mac_help),
            trailingIcon = { PasteButton { details.mac += it } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The guide URL and User-Agent, collapsed at the bottom: most providers never need either. */
@Composable
private fun AdvancedFields(details: ProviderDetails) {
    var showing by rememberSaveable { mutableStateOf(false) }
    PillButton(
        label = stringResource(if (showing) R.string.onboarding_hide_advanced else R.string.onboarding_advanced),
        onClick = { showing = !showing },
    )
    if (!showing) return
    TextField(
        value = details.epgUrl,
        onValueChange = { details.epgUrl = it },
        label = stringResource(R.string.onboarding_guide_url_optional),
        supportingText = stringResource(R.string.onboarding_guide_blank_help),
        keyboardType = KeyboardType.Uri,
        modifier = Modifier.fillMaxWidth(),
    )
    TextField(
        value = details.userAgent,
        onValueChange = { details.userAgent = it },
        label = "User-Agent",
        supportingText = stringResource(R.string.onboarding_user_agent_help),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A trailing "paste" affordance for a provider field: reads the system clipboard and appends it
 * via [onPaste]. A long Xtream URL or username is miserable to type on a D-pad. An empty clipboard
 * shows a brief message rather than doing nothing silently.
 */
@Composable
private fun PasteButton(onPaste: (String) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    HighlightSurface(
        onClick = {
            val pasted = readClipboardText(context)
            if (pasted != null) {
                onPaste(pasted)
            } else {
                Toast.makeText(context, resources.getString(R.string.kbd_clipboard_empty), Toast.LENGTH_SHORT).show()
            }
        },
        style = HighlightStyle.Button,
    ) { highlighted ->
        Icon(
            Icons.Filled.ContentPaste,
            contentDescription = stringResource(R.string.kbd_paste),
            tint = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text,
            modifier = Modifier.padding(10.dp).size(22.dp),
        )
    }
}

/** Plain text currently on the system clipboard, trimmed, or null when there's nothing to paste. */
private fun readClipboardText(context: Context): String? =
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
        ?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
