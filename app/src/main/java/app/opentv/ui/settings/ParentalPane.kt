/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.ui.design.ListRow

/**
 * Parental lock (GLOSSARY: Parental Lock). With a PIN set, the pane shows only "Enter your PIN"
 * until the PIN is entered, and then its settings until the app closes. With none, it shows the
 * settings and a row to set one.
 *
 * The PIN is a family lock, not a vault: four digits stored as a salted hash, enough to stop casual
 * access, not a serious attacker.
 */
@Composable
internal fun ParentalPane(onOpenLockedCategories: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { ServiceLocator.get(context).settings }
    val pinIsSet by settings.pinIsSet.collectAsState()
    val pinEntered by settings.pinEntered.collectAsState()
    var step by remember { mutableStateOf<PinStep?>(null) }
    val wrongPin = stringResource(R.string.settings_parental_wrong_pin)
    val mismatch = stringResource(R.string.settings_parental_pin_mismatch)

    if (pinIsSet && !pinEntered) {
        ListRow(stringResource(R.string.settings_parental_enter_pin), onClick = { step = PinStep.Enter }, opens = true)
    } else {
        ParentalSettings(pinIsSet, onSetPin = { step = PinStep.Choose }, onOpenLockedCategories)
    }

    when (val current = step) {
        PinStep.Enter -> PinEntry(
            title = stringResource(R.string.settings_parental_enter_pin),
            refusal = { if (it.length == PIN_LENGTH && !settings.verifyPin(it)) wrongPin else null },
            onPin = { if (settings.enterPin(it)) step = null },
            onCancel = { step = null },
        )
        PinStep.Choose -> PinEntry(
            title = stringResource(R.string.settings_parental_choose_pin),
            onPin = { step = PinStep.Confirm(it) },
            onCancel = { step = null },
        )
        is PinStep.Confirm -> PinEntry(
            title = stringResource(R.string.settings_parental_confirm_pin),
            refusal = { if (it.length == PIN_LENGTH && it != current.pin) mismatch else null },
            onPin = {
                if (it == current.pin) {
                    settings.setPin(it)
                    // Whoever just chose the PIN knows it, so the settings stay open.
                    settings.enterPin(it)
                    step = null
                }
            },
            onCancel = { step = null },
        )
        null -> Unit
    }
}

@Composable
private fun ParentalSettings(pinIsSet: Boolean, onSetPin: () -> Unit, onOpenLockedCategories: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { ServiceLocator.get(context) }
    val settings = graph.settings
    // Counted against today's categories, as Locked categories draws its switches: the stored keys
    // also hold categories a provider has dropped, or another device's provider lists (they're kept
    // in case they come back), and counting those said more were locked than the switches showed.
    val locked = graph.channelLineup.lockedCategoryCount.collectAsState(initial = null as Int?).value
    val unlocked by settings.hiddenUnlocked.collectAsState()
    val lockedRow = screenRow("locked-categories", onOpenLockedCategories)

    ListRow(
        stringResource(R.string.settings_parental_show_hidden),
        onClick = { settings.setHiddenUnlocked(!unlocked) },
        switch = unlocked,
        note = stringResource(R.string.settings_parental_show_hidden_note),
    )
    ListRow(
        stringResource(if (pinIsSet) R.string.settings_parental_change_pin else R.string.settings_parental_set_pin),
        onClick = onSetPin,
        opens = true,
    )
    if (pinIsSet) {
        ListRow(stringResource(R.string.parental_remove_pin), onClick = settings::clearPin)
    }
    // A row that opens the categories on a screen of their own, not a switch per category here: a
    // big provider has hundreds, and highlighting Parental lock on the way down composed them all.
    ListRow(
        stringResource(R.string.parental_locked_categories),
        onClick = lockedRow.open,
        modifier = lockedRow.modifier,
        value = when (locked) {
            // Nothing until the categories have been read, rather than None and then a count.
            null -> null
            0 -> stringResource(R.string.parental_locked_none)
            else -> pluralStringResource(R.plurals.parental_locked_count, locked, locked)
        },
        opens = true,
    )
}

/** Four digits on the number keyboard, hidden as they're typed. Fewer than four saves nothing. */
@Composable
private fun PinEntry(
    title: String,
    onPin: (String) -> Unit,
    onCancel: () -> Unit,
    refusal: (String) -> String? = { null },
) {
    EntryWindow(
        title = title,
        initial = "",
        keyboardType = KeyboardType.NumberPassword,
        maxLength = PIN_LENGTH,
        refusal = refusal,
        onSave = { if (it.length == PIN_LENGTH && it.all(Char::isDigit)) onPin(it) },
        onCancel = onCancel,
    )
}

private sealed interface PinStep {
    data object Enter : PinStep
    data object Choose : PinStep
    data class Confirm(val pin: String) : PinStep
}

private const val PIN_LENGTH = 4
