/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.directions

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/**
 * PROTOTYPE for #89, for trying the directions with a remote. Throwaway, never merged.
 *
 * adb shell am start -n uk.neutraltone.settee.debug/app.opentv.ui.directions.DirectionsActivity
 *
 * Rewind and fast-forward change direction, play/pause shows or hides the Live Preview on Home, OK on
 * a rail tab changes screen, OK on a Shows title opens its details, and Back returns to Shows.
 */
class DirectionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var direction by remember { mutableStateOf(Direction.COMBINED) }
            var screen by remember { mutableStateOf(Screen.HOME) }
            val directions = Direction.entries
            BackHandler(screen == Screen.DETAILS) { screen = Screen.SHOWS }
            Box(
                Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        val step = when (event.key) {
                            Key.MediaFastForward -> 1
                            Key.MediaRewind -> -1
                            Key.MediaPlayPause -> 0
                            else -> return@onPreviewKeyEvent false
                        }
                        if (event.type == KeyEventType.KeyDown) {
                            if (step == 0) {
                                screen = if (screen == Screen.HOME) Screen.HOME_PREVIEW else Screen.HOME
                            } else {
                                direction = directions[(direction.ordinal + step).mod(directions.size)]
                            }
                        }
                        true
                    },
            ) {
                CompositionLocalProvider(LocalProtoNav provides { screen = it }) {
                    key(direction, screen) { DirectionScreen(direction, screen) }
                }
                Text(
                    "PROTOTYPE  ◀◀ ${direction.label} ▶▶",
                    color = Color.Black,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .background(Color(0xFFFFD54F), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
    }
}
