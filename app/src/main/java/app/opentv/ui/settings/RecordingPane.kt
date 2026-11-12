/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.StatFs
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.opentv.R
import app.opentv.core.AppSettings.RecordingTarget
import app.opentv.core.ServiceLocator
import app.opentv.core.isIgnoringBatteryOptimizations
import app.opentv.core.requestIgnoreBatteryOptimizations
import app.opentv.recording.RecordingStorage
import app.opentv.recording.SmbClient
import app.opentv.recording.SmbConfig
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.SidePanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recording: where recordings are saved, with the network drive's details or the USB folder under
 * it, how early and late to record, and how recording behaves.
 */
@Composable
internal fun RecordingPane() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val settings = remember { ServiceLocator.get(context).settings }
    val target by settings.recordingTarget.collectAsState()
    val host by settings.smbHost.collectAsState()
    val share by settings.smbShare.collectAsState()
    val folder by settings.smbFolder.collectAsState()
    val user by settings.smbUser.collectAsState()
    val password by settings.smbPassword.collectAsState()
    val usbTree by settings.usbTreeUri.collectAsState()
    val usbLabel by settings.usbFolderLabel.collectAsState()
    val padStart by settings.recordPadStartMinutes.collectAsState()
    val padEnd by settings.recordPadEndMinutes.collectAsState()
    val autoSwitch by settings.recordAutoSwitch.collectAsState()
    val livePause by settings.livePauseEnabled.collectAsState()
    var choosing by remember { mutableStateOf<RecordingChoice?>(null) }
    var typing by remember { mutableStateOf<NasField?>(null) }
    var testNote by remember { mutableStateOf<String?>(null) }
    var testDetail by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The system's folder picker. On a granted folder, a persistable permission keeps the grant (and
    // playback of what's recorded there) across restarts.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            val label = DocumentFile.fromTreeUri(context, uri)?.name
                ?: uri.lastPathSegment ?: resources.getString(R.string.settings_rec_usb)
            settings.setUsbTree(uri.toString(), label)
            settings.setRecordingTarget(RecordingTarget.USB)
        }
    }
    val pickFolder = {
        runCatching { folderPicker.launch(null) }.onFailure {
            Toast.makeText(context, resources.getString(R.string.recset_usb_no_picker), Toast.LENGTH_LONG).show()
        }
    }

    ListRow(
        stringResource(R.string.recset_save_to),
        onClick = { choosing = RecordingChoice.Target },
        value = stringResource(target.label),
        note = if (target == RecordingTarget.INTERNAL) storageNote() else null,
    )
    when (target) {
        RecordingTarget.SMB -> {
            val fromPhone = stringResource(R.string.settings_row_from_phone)
            val notSet = stringResource(R.string.settings_row_not_set)
            NasField.entries.forEach { field ->
                val current = field.of(host, share, folder, user, password)
                ListRow(
                    stringResource(field.label),
                    onClick = { typing = field },
                    value = when {
                        current.isBlank() -> notSet
                        field == NasField.Password -> stringResource(R.string.settings_row_saved)
                        else -> current
                    },
                    note = fromPhone,
                )
            }
            ListRow(
                stringResource(if (testing) R.string.recset_status_testing else R.string.recset_test_connection),
                onClick = {
                    if (testing) return@ListRow
                    testDetail = null
                    if (host.isBlank() || share.isBlank()) {
                        testNote = resources.getString(R.string.settings_rec_test_needs_details)
                        return@ListRow
                    }
                    testing = true
                    testNote = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { SmbClient.test(SmbConfig(host.trim(), share.trim(), folder.trim(), user, password)) }
                        }
                        testing = false
                        val failure = result.exceptionOrNull()?.let {
                            ServiceLocator.get(context).errorWording.of(it, R.string.recset_status_failed)
                        }
                        testNote = failure?.sentence ?: resources.getString(R.string.recset_status_connected)
                        testDetail = failure?.detail
                    }
                },
                note = testNote,
                detail = testDetail,
            )
        }
        RecordingTarget.USB -> ListRow(
            stringResource(R.string.settings_rec_usb_folder),
            onClick = { pickFolder() },
            value = usbLabel?.takeIf { usbTree != null } ?: stringResource(R.string.settings_row_not_set),
        )
        RecordingTarget.INTERNAL -> Unit
    }
    val minutes = { value: Int -> resources.getString(R.string.settings_minutes, value) }
    ListRow(
        stringResource(R.string.recset_pad_start),
        onClick = { choosing = RecordingChoice.Start },
        value = minutes(padStart),
        note = stringResource(R.string.recset_pad_start_desc),
    )
    ListRow(
        stringResource(R.string.recset_pad_end),
        onClick = { choosing = RecordingChoice.End },
        value = minutes(padEnd),
        note = stringResource(R.string.recset_pad_end_desc),
    )
    ListRow(
        stringResource(R.string.settings_rec_autoswitch),
        onClick = { settings.setRecordAutoSwitch(!autoSwitch) },
        switch = autoSwitch,
        note = stringResource(R.string.settings_rec_autoswitch_note),
    )
    ListRow(
        stringResource(R.string.recset_livepause),
        onClick = { settings.setLivePauseEnabled(!livePause) },
        switch = livePause,
        note = stringResource(R.string.settings_rec_livepause_note),
    )
    BackgroundRow()

    choosing?.let { choice ->
        SidePanel(onBack = { choosing = null }) {
            when (choice) {
                RecordingChoice.Target -> RecordingTarget.entries.forEach { option ->
                    PanelRow(stringResource(option.label), ticked = option == target, onClick = {
                        choosing = null
                        // USB only counts once a folder is granted, so recordings are never left
                        // with nowhere to go.
                        if (option == RecordingTarget.USB && usbTree == null) pickFolder() else settings.setRecordingTarget(option)
                    })
                }
                RecordingChoice.Start -> MinuteChoices(START_MINUTES, padStart, minutes) {
                    choosing = null
                    settings.setRecordPadding(it, padEnd)
                }
                RecordingChoice.End -> MinuteChoices(END_MINUTES, padEnd, minutes) {
                    choosing = null
                    settings.setRecordPadding(padStart, it)
                }
            }
        }
    }
    typing?.let { field ->
        EntryWindow(
            title = stringResource(field.label),
            initial = field.of(host, share, folder, user, password),
            keyboardType = if (field == NasField.Password) KeyboardType.Password else KeyboardType.Text,
            hint = stringResource(R.string.settings_row_from_phone),
            // A guest share has no login, and an empty folder means the share itself.
            allowBlank = field.optional,
            onSave = { typed ->
                typing = null
                val value = if (field == NasField.Password) typed else typed.trim()
                settings.setSmbConfig(
                    host = if (field == NasField.Server) value else host,
                    share = if (field == NasField.Share) value else share,
                    folder = if (field == NasField.Folder) value else folder,
                    user = if (field == NasField.Username) value else user,
                    password = if (field == NasField.Password) value else password,
                )
            },
            onCancel = { typing = null },
        )
    }
}

@Composable
private fun MinuteChoices(options: List<Int>, current: Int, label: (Int) -> String, onPick: (Int) -> Unit) {
    // The current value is listed even if it isn't one of the steps, so it can be seen ticked.
    (options + current).distinct().sorted().forEach { value ->
        PanelRow(label(value), ticked = value == current, onClick = { onPick(value) })
    }
}

/**
 * Whether Android lets recordings carry on in the background. OK asks for it when it isn't
 * allowed; once allowed there's nothing to do. Checked again on coming back from Android's own
 * screen, with a word that it worked.
 */
@Composable
private fun BackgroundRow() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = context.isIgnoringBatteryOptimizations()
                if (now && !allowed) {
                    Toast.makeText(context, resources.getString(R.string.recset_background_toast), Toast.LENGTH_LONG).show()
                }
                allowed = now
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    ListRow(
        stringResource(R.string.recset_background_title),
        onClick = { if (!allowed) context.requestIgnoreBatteryOptimizations() },
        value = stringResource(if (allowed) R.string.recset_background_allowed else R.string.recset_background_not_allowed),
        note = stringResource(if (allowed) R.string.recset_background_on_desc else R.string.settings_rec_background_note),
        opens = !allowed,
    )
}

/** How much the recordings on this TV take up, and what's left. */
@Composable
private fun storageNote(): String? {
    val context = LocalContext.current
    val resources = LocalResources.current
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        note = withContext(Dispatchers.IO) {
            val dir = RecordingStorage.internalDir(context)
            val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
            resources.getString(
                R.string.settings_rec_storage,
                formatBytes(resources, files.sumOf { it.length() }),
                files.size,
                formatBytes(resources, StatFs(dir.path).availableBytes),
            )
        }
    }
    return note
}

private fun formatBytes(resources: Resources, bytes: Long): String {
    val mb = bytes.coerceAtLeast(0) / (BYTES_PER_UNIT * BYTES_PER_UNIT)
    return if (mb >= BYTES_PER_UNIT) {
        resources.getString(R.string.settings_size_gb, mb / BYTES_PER_UNIT)
    } else {
        resources.getString(R.string.settings_size_mb, mb)
    }
}

private val RecordingTarget.label: Int
    get() = when (this) {
        RecordingTarget.INTERNAL -> R.string.settings_rec_internal
        RecordingTarget.SMB -> R.string.settings_rec_nas
        RecordingTarget.USB -> R.string.settings_rec_usb
    }

private enum class RecordingChoice { Target, Start, End }

/** The network drive's details, each typed on an entry screen. */
private enum class NasField(val label: Int, val optional: Boolean = false) {
    Server(R.string.settings_rec_server),
    Share(R.string.settings_rec_share),
    Folder(R.string.settings_rec_folder, optional = true),
    Username(R.string.recset_field_username, optional = true),
    Password(R.string.recset_field_password, optional = true),
    ;

    fun of(host: String, share: String, folder: String, user: String, password: String) = when (this) {
        Server -> host
        Share -> share
        Folder -> folder
        Username -> user
        Password -> password
    }
}

private const val BYTES_PER_UNIT = 1024.0
private val START_MINUTES = listOf(0, 1, 2, 5, 10, 15, 30)
private val END_MINUTES = listOf(0, 1, 2, 5, 10, 15, 30, 60)
