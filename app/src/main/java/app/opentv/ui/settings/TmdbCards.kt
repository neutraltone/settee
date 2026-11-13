/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ErrorWording
import app.opentv.pairing.PairingServer
import app.opentv.ui.design.ListRow
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.onboarding.describePairingFailure

/**
 * The QR code for Onboarding's TMDB step, laid out as the phone pairing screen before it lays its own:
 * the code, white and 300 dp square, with the address to type under it for a camera that won't scan.
 * Its space is fixed, so nothing moves when the code appears after its server starts, or the server
 * can't start and Try again says why in its place.
 */
@Composable
internal fun PhoneQrPanel(phone: PairingServer.State, onRetry: () -> Unit, wording: ErrorWording) {
    Column(Modifier.size(QR_PANEL_WIDTH, QR_PANEL_HEIGHT)) {
        when (phone) {
            is PairingServer.State.Listening -> {
                PhoneQrCode(phone.session.url, stringResource(R.string.settings_tmdb_qr_desc), QR_PANEL_WIDTH)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.pairing_camera_fail), SetteeType.Body, color = SetteeColors.QuietText)
                Spacer(Modifier.height(4.dp))
                Text(phone.session.shortUrl, SetteeType.Section)
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

/** [PhoneQrPanel]: the pairing screen's 300 dp code, and under it how to type the address, and the address. */
internal val QR_PANEL_WIDTH = 300.dp
internal val QR_PANEL_HEIGHT = 390.dp
private val LOGO_HEIGHT = 16.dp
