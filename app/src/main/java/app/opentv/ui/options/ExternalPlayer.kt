/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.options

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import app.opentv.R
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.data.repo.PlaybackSources
import app.opentv.player.PlaybackErrors

/** Hands [key]'s stream to another installed player, saying so if it can't be played or there's none. */
internal suspend fun openInExternalPlayer(context: Context, sources: PlaybackSources, key: MediaKey) {
    val playable = sources.forWatching(key)
    if (playable is Playable.Failed) {
        // A toast can't hold the shared ErrorMessage, so the detail line goes on its own line under the sentence.
        val reason = PlaybackErrors.describe(context, playable.failure)
        Toast.makeText(context, "${reason.sentence}\n${reason.detail}".trim(), Toast.LENGTH_LONG).show()
        return
    }
    playable as Playable.Ready
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(playable.url.toUri(), "video/*")
        putExtra("title", playable.title)
        // MX Player / VLC read the User-Agent from this header extra.
        putExtra("headers", arrayOf("User-Agent", playable.userAgent))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.guide_play_channel_with))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.guide_no_external_player), Toast.LENGTH_SHORT).show()
    }
}
