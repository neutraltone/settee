/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.content.res.Resources
import app.opentv.R
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.RefreshStatus

/** What to tell the viewer about a Catalogue Refresh; null when there's nothing to say. */
fun refreshMessage(resources: Resources, status: RefreshStatus): String? = when (status) {
    RefreshStatus.Idle -> null
    RefreshStatus.Refreshing -> resources.getString(R.string.refresh_running)
    RefreshStatus.LoadingChannels -> resources.getString(R.string.refresh_loading_channels)
    is RefreshStatus.Refreshed -> {
        val channels = resources.getQuantityString(R.plurals.refresh_channels, status.channels, status.channels)
        if (status.problems == 0) {
            resources.getString(R.string.refresh_done, channels, status.guideMatched, status.guideTotal)
        } else {
            val problems = resources.getQuantityString(R.plurals.refresh_problems, status.problems, status.problems)
            resources.getString(R.string.refresh_done_with_problems, channels, problems)
        }
    }
    is RefreshStatus.BuildingGuide ->
        resources.getQuantityString(R.plurals.refresh_provider_loaded, status.channels, status.channels)
    is RefreshStatus.ProviderReady ->
        if (status.channelsMatched > 0) {
            resources.getString(R.string.refresh_guide_ready, status.channelsMatched, status.channelsTotal)
        } else {
            resources.getString(R.string.refresh_guide_unmatched)
        }
    // The reason comes from the provider client, as the rest of the playback and login errors do.
    is RefreshStatus.ProviderFailed -> status.reason
}

/** The line under Guide settings' update button once a guide download has finished. */
fun guideStatusLine(resources: Resources, summary: EpgRepository.SyncSummary): String {
    val matched = resources.getString(R.string.epg_status_matched, summary.channelsMatched, summary.channelsTotal)
    if (summary.feedsFailed == 0) return matched
    val failed = resources.getQuantityString(
        R.plurals.epg_status_feeds_failed,
        summary.feedsFailed,
        summary.feedsFailed,
    )
    return resources.getString(R.string.epg_status_with_failures, matched, failed)
}
