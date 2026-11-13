/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.model

/**
 * What kind of subtitle a viewer picked from OpenSubtitles: ordinary dialogue, only the foreign
 * parts ([FORCED]), or with sound described ([HEARING_IMPAIRED]). Stored by name in the Subtitle
 * Preference, so don't rename the entries.
 */
enum class SubtitleKind { STANDARD, FORCED, HEARING_IMPAIRED }
