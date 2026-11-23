/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.CrashReports
import app.opentv.ui.design.ListRow

/**
 * Privacy: Crash Reports, off until the viewer turns them on here (ADR 0009), with what a report
 * holds beside the switch so they can see what they've agreed to. For this device only.
 */
@Composable
internal fun PrivacyPane(crashReports: CrashReports) {
    val on by crashReports.on.collectAsState()
    ListRow(
        stringResource(R.string.settings_privacy_crash_reports),
        onClick = { crashReports.setOn(!on) },
        note = stringResource(R.string.settings_privacy_crash_reports_note),
        switch = on,
    )
}
