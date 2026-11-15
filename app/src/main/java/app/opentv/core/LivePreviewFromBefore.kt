/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import app.opentv.data.repo.ProfileSettings

/**
 * 1.0 had one "Live preview on Home" switch for the whole device; it's now each Profile's (#212). A
 * device where it was off keeps it off for the Profiles it has, once: the old switch is forgotten
 * only after they have it, so a launch cut short tries again next time.
 */
suspend fun carryLivePreviewFromBefore(settings: AppSettings, profileSettings: ProfileSettings) {
    if (settings.livePreviewWasOffBefore()) profileSettings.turnLivePreviewOffEverywhere()
    settings.forgetLivePreviewFromBefore()
}
