/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Episode

/** An episode of series "s" with a predictable row id: S1E2 is 12, S2E1 is 21. */
fun testEpisode(season: Int, number: Int) = Episode(
    id = season * 10L + number,
    sourceId = 1,
    seriesId = "s",
    episodeId = "$season-$number",
    season = season,
    episodeNumber = number,
    title = "Episode $number",
    plot = null,
    durationSeconds = 2_400,
    stillUrl = null,
    streamUrl = "https://example.com/$season-$number.mkv",
)
