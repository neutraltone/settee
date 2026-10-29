/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.options

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * [clickable] that also opens the item's options panel ([onOptions]) on the remote's Menu button,
 * a held Select, or a long press by touch. With no [onOptions] it is just [clickable].
 *
 * Unlike `clickable`, which fires on Select's key-up however long it was held, `combinedClickable`
 * times a held Select itself and then drops the key-up, so only Menu needs handling here.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.optionsClickable(onClick: () -> Unit, onOptions: (() -> Unit)?): Modifier {
    if (onOptions == null) return clickable(onClick = onClick)
    return onPreviewKeyEvent { event ->
        if (event.key != Key.Menu) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) onOptions()
        true
    }.combinedClickable(onClick = onClick, onLongClick = onOptions)
}

/** Opens [subject]'s options panel; null where no [ItemOptionsHost] is above to show it. */
@Composable
fun openOptionsOn(subject: OptionsSubject): (() -> Unit)? {
    val open = LocalOpenOptions.current ?: return null
    return { open(subject) }
}
