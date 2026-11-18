/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import app.opentv.PANEL_SOURCE
import app.opentv.TestCatalog
import app.opentv.core.AppSettings
import app.opentv.core.ErrorWording
import app.opentv.data.model.Channel
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.RecordingRepository
import app.opentv.data.repo.SourceRepository

/** A [RecordingEngine] over a test database, its clock fixed at [now], and a channel to record. */
class RecordingFixture(context: Context, now: Long = NOW) : AutoCloseable {
    private val test = TestCatalog(context)
    private val db = test.db
    val recordings = RecordingRepository(db.recordings(), db.seriesRules())
    val engine = RecordingEngine(
        context,
        recordings,
        SourceRepository(db.sources(), XtreamApi(test.panel.http), StalkerApi(test.panel.http)),
        AppSettings.get(context),
        EpgRepository(
            programmeDao = db.programmes(),
            refreshDao = db.guideRefresh(),
            feedDao = db.epgFeeds(),
            aliasDao = db.epgAliases(),
            channelDao = db.channels(),
            sourceDao = db.sources(),
            api = XtreamApi(test.panel.http),
            http = test.panel.http,
            wording = ErrorWording(context, onTelevision = false),
            selfEnabledGuide = EpgRepository.SelfEnabledGuide.InMemory(),
        ),
        test.lineup,
        ErrorWording(context, onTelevision = false),
        clock = { now },
    )

    val bbcOne = Channel(
        id = 7,
        sourceId = PANEL_SOURCE.id,
        streamId = "7",
        name = "UK| BBC One HD",
        displayName = "BBC One",
        groupKey = "uk:bbcone",
        qualityRank = 200,
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "http://example.com/live/u/p/7.ts",
    )

    override fun close() = test.close()

    companion object {
        const val NOW = 1_763_217_000_000L
    }
}
