/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

/**
 * How long a UI test waits for a screen to reach the state it checks. A wait ends as soon as it does,
 * so this only decides how long a test takes to fail. CI's runners share their cores across the whole
 * suite, and 5 s was too short there for a screen that was correct, just slow behind a stall: four
 * tests failed that way in a week (NextEpisodeOverlayTest, SettingsRoutesTest, AddSourceScreenTest,
 * VodPlayerRowTest), none of them on a retry.
 */
const val UI_WAIT_MILLIS = 15_000L
