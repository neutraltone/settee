/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.content.Context
import androidx.annotation.StringRes
import app.opentv.R
import app.opentv.core.LocaleUtils
import app.opentv.data.repo.CatalogueRefresh.Outcome

/** What a Catalogue Refresh tells the viewer, in their language. */
class RefreshMessages(private val context: Context) {
    // Looked up each time: this outlives the activity, and changing language only recreates that,
    // so the Application's own resources stay in the language the process started in.
    private val resources get() = LocaleUtils.wrap(context).resources

    /** A refresh's steps, as the viewer is told about them while they run. */
    enum class Step(@param:StringRes val text: Int) {
        SavingSource(R.string.refresh_saving_source),
        SaveFailed(R.string.refresh_save_failed),
        LoadingChannels(R.string.refresh_loading_channels),
        Refreshing(R.string.refresh_refreshing),
        BuildingGuide(R.string.refresh_building_guide),
        LoadingVod(R.string.refresh_loading_vod),
        DownloadingGuides(R.string.refresh_downloading_guides),
    }

    fun of(step: Step): String = resources.getString(step.text)

    /** The sentence for [outcome], or null where a refresh has nothing to say. */
    fun of(outcome: Outcome): String? = when (outcome) {
        is Outcome.Refreshed -> refreshed(outcome)
        is Outcome.ChannelsLoaded ->
            resources.getQuantityString(R.plurals.refresh_channels_loaded, outcome.channels, outcome.channels)
        is Outcome.AddFailed -> outcome.reason
        is Outcome.GuideLoaded -> guideLoaded(outcome.guide)
        is Outcome.GuideRefreshed -> guideRefreshed(outcome.guide)
        is Outcome.VodChecked -> null
    }

    private fun refreshed(outcome: Outcome.Refreshed): String = if (outcome.problems == 0) {
        resources.getQuantityString(
            R.plurals.refresh_done,
            outcome.channels,
            outcome.channels,
            outcome.guide.channelsMatched,
            outcome.guide.channelsTotal,
        )
    } else {
        resources.getQuantityString(
            R.plurals.refresh_done_with_problems,
            outcome.channels,
            outcome.channels,
            resources.getQuantityString(R.plurals.refresh_problems, outcome.problems, outcome.problems),
        )
    }

    private fun guideLoaded(guide: EpgRepository.SyncSummary): String = if (guide.channelsMatched > 0) {
        resources.getQuantityString(
            R.plurals.refresh_guide_ready,
            guide.channelsTotal,
            guide.channelsMatched,
            guide.channelsTotal,
        )
    } else {
        resources.getString(R.string.refresh_guide_unmatched)
    }

    private fun guideRefreshed(guide: EpgRepository.SyncSummary): String {
        val matched = resources.getQuantityString(
            R.plurals.refresh_guide_matched,
            guide.channelsTotal,
            guide.channelsMatched,
            guide.channelsTotal,
        )
        if (guide.feedsFailed == 0) return matched
        val failed = resources.getQuantityString(R.plurals.refresh_feeds_failed, guide.feedsFailed, guide.feedsFailed)
        return resources.getString(R.string.refresh_guide_and_failures, matched, failed)
    }
}
