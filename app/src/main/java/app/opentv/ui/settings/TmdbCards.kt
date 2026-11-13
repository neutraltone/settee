/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ErrorWording
import app.opentv.pairing.PairingServer
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.onboarding.describePairingFailure

/**
 * The QR code as a card of its own, beside the rows rather than above them, for a screen with no room
 * to stack them, such as Onboarding's step. The card's size is fixed, so nothing moves when the code
 * appears after its server starts, or the server can't start and the card says why instead.
 */
@Composable
internal fun PhoneCodeCard(phone: PairingServer.State, onRetry: () -> Unit, wording: ErrorWording) {
    Column(
        Modifier
            .size(CARD_WIDTH, CARD_HEIGHT)
            .background(SetteeColors.Surface, SetteeShapes.Card)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        when (phone) {
            is PairingServer.State.Listening -> {
                PhoneQrCode(phone.session.url, stringResource(R.string.settings_tmdb_qr_desc))
                Text(
                    stringResource(R.string.settings_tmdb_scan),
                    SetteeType.Body,
                    color = SetteeColors.QuietText,
                    textAlign = TextAlign.Center,
                )
                Text(phone.session.shortUrl, SetteeType.CardTitle, textAlign = TextAlign.Center)
            }
            is PairingServer.State.Failed -> {
                val reason = remember(phone) { describePairingFailure(wording, phone) }
                ListRow(
                    stringResource(R.string.common_try_again),
                    onClick = onRetry,
                    note = reason.sentence,
                    detail = reason.detail,
                )
            }
            else -> Unit
        }
    }
}

/** A saved key, by its last four characters, in [PhoneCodeCard]'s place and size, which it replaces. */
@Composable
internal fun KeySavedCard(ending: String) {
    Column(
        Modifier
            .size(CARD_WIDTH, CARD_HEIGHT)
            .background(SetteeColors.Surface, SetteeShapes.Card)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.settings_row_tmdb_set), SetteeType.Section, textAlign = TextAlign.Center)
        Text(stringResource(R.string.settings_tmdb_ending, ending), SetteeType.Body, textAlign = TextAlign.Center)
    }
}

/** TMDB's logo and the sentence its terms ask for, in Settings › TMDB and About. */
@Composable
internal fun TmdbAttribution(modifier: Modifier = Modifier) {
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

/** [PhoneCodeCard]: wide enough for the address, tall enough for the code, the line above it and the address. */
private val CARD_WIDTH = 340.dp
private val CARD_HEIGHT = 340.dp
private val LOGO_HEIGHT = 16.dp
