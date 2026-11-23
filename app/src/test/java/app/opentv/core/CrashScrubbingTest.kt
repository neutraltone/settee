/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import com.google.common.truth.Truth.assertThat
import io.sentry.SentryEvent
import io.sentry.protocol.DebugImage
import io.sentry.protocol.DebugMeta
import org.junit.Test

/**
 * The parts of the allow-list [CrashReportsTest] can't reach through a real crash: the build id a
 * release's APK carries (#577) exists only in a minified build, which unit tests never run.
 */
class CrashScrubbingTest {

    @Test
    fun `a report keeps the id of its release's R8 mapping, and no other debug image`() {
        val event = SentryEvent().apply {
            debugMeta = DebugMeta().apply {
                images = listOf(
                    DebugImage().apply {
                        type = DebugImage.PROGUARD
                        uuid = MAPPING_ID
                        codeFile = "/data/app/uk.neutraltone.settee/base.apk"
                    },
                    DebugImage().apply {
                        type = DebugImage.JVM
                        debugId = "source-bundle"
                    },
                )
            }
        }

        val images = CrashScrubbing.event(event, installId = null, screen = null).debugMeta?.images

        assertThat(images).hasSize(1)
        assertThat(images!!.single().type).isEqualTo(DebugImage.PROGUARD)
        assertThat(images.single().uuid).isEqualTo(MAPPING_ID)
        assertThat(images.single().codeFile).isNull()
    }

    @Test
    fun `a report from a build without a mapping has no debug images at all`() {
        val event = SentryEvent().apply {
            debugMeta = DebugMeta().apply { images = listOf(DebugImage().apply { type = DebugImage.JVM }) }
        }

        assertThat(CrashScrubbing.event(event, installId = null, screen = null).debugMeta).isNull()
    }

    private companion object {
        const val MAPPING_ID = "0b5b5f2a-8c1e-4f5e-9d3a-2a7f1c9e4b6d"
    }
}
