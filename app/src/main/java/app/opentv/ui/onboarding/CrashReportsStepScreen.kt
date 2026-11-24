/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.CrashReports
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text

/**
 * Setup's last step, after OpenSubtitles': whether this device sends Crash Reports (ADR 0009), asked
 * before anything could be sent. Not now has the Highlight, so pressing OK through Setup without
 * reading leaves them off. What a report holds is the Privacy pane's own sentence, so the two can't
 * promise different things. Either choice goes on, through [onDone]; [onBack] is Back, choosing nothing.
 */
@Composable
fun CrashReportsStepScreen(crashReports: CrashReports, onDone: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    StepLayout(
        title = stringResource(R.string.crash_reports_step_title),
        help = stringResource(R.string.settings_privacy_crash_reports_note),
        attribution = {},
        code = {},
        rows = {
            Text(
                stringResource(R.string.crash_reports_step_later),
                SetteeType.Body,
                color = SetteeColors.QuietText,
                modifier = Modifier.padding(bottom = SetteeSpace.RowGap),
            )
        },
        actions = {
            PillButton(
                stringResource(R.string.common_not_now),
                onClick = onDone,
                modifier = Modifier.takesHighlight(),
            )
            PillButton(
                stringResource(R.string.settings_privacy_crash_reports),
                onClick = {
                    crashReports.setOn(true)
                    onDone()
                },
            )
        },
    )
}
