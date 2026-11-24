/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

// Made-up keys in each format: none of them has ever worked anywhere.
private const val OPENSUBTITLES = "Ab3dEf6hIj9kLm2nOp5qRs8tUv1wXy4z"
private const val TMDB_V3 = "0123456789abcdef0123456789abcdef"
private const val SENTRY_DSN = "https://0123456789abcdef0123456789abcdef@o123456.ingest.example.com/4567"
private const val TMDB_V4 = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ4In0.c2lnbmF0dXJlLV9zaWc"

class BuiltInKeysTest {

    @Test
    fun `an OpenSubtitles key of 32 letters and digits is built in as it is`() {
        assertThat(checkedBuiltInKey(BuiltInKey.OPENSUBTITLES, OPENSUBTITLES)).isEqualTo(OPENSUBTITLES)
    }

    @Test
    fun `a GitHub token given as the OpenSubtitles key stops the build without showing it`() {
        val token = "ghs_" + "x".repeat(36)
        val e = assertThrows(IllegalArgumentException::class.java) {
            checkedBuiltInKey(BuiltInKey.OPENSUBTITLES, token)
        }
        assertThat(e).hasMessageThat().contains("OPENSUBTITLES_API_KEY")
        assertThat(e).hasMessageThat().contains("32 letters and digits")
        assertThat(e).hasMessageThat().doesNotContain(token)
    }

    @Test
    fun `a blank key builds, leaving that feature out`() {
        for (key in BuiltInKey.entries) {
            assertThat(checkedBuiltInKey(key, "")).isEmpty()
        }
    }

    @Test
    fun `an OpenSubtitles key of the wrong length or with other characters is refused`() {
        for (bad in listOf(OPENSUBTITLES.drop(1), OPENSUBTITLES + "a", OPENSUBTITLES.dropLast(1) + "-")) {
            assertThrows(IllegalArgumentException::class.java) { checkedBuiltInKey(BuiltInKey.OPENSUBTITLES, bad) }
        }
    }

    @Test
    fun `TMDB takes either a v3 API Key or a v4 Read Access Token`() {
        assertThat(checkedBuiltInKey(BuiltInKey.TMDB, TMDB_V3)).isEqualTo(TMDB_V3)
        assertThat(checkedBuiltInKey(BuiltInKey.TMDB, TMDB_V4)).isEqualTo(TMDB_V4)
    }

    @Test
    fun `a TMDB key that is neither form is refused without showing it`() {
        val bad = listOf(
            // Not hex.
            OPENSUBTITLES,
            TMDB_V3.drop(1),
            // Two parts, or not starting as a JWT header does.
            TMDB_V4.substringBeforeLast('.'),
            "abc" + TMDB_V4.drop(3),
            "$TMDB_V4.extra",
        )
        for (value in bad) {
            val e = assertThrows(IllegalArgumentException::class.java) { checkedBuiltInKey(BuiltInKey.TMDB, value) }
            assertThat(e).hasMessageThat().contains("TMDB_API_KEY")
            assertThat(e).hasMessageThat().doesNotContain(value)
        }
    }

    @Test
    fun `a Sentry DSN of https, key, host and project id is built in as it is`() {
        assertThat(checkedBuiltInKey(BuiltInKey.SENTRY_DSN, SENTRY_DSN)).isEqualTo(SENTRY_DSN)
    }

    @Test
    fun `a Sentry DSN that is not https key at host slash project id is refused without showing it`() {
        val bad = listOf(
            SENTRY_DSN.replace("https://", "http://"),
            // No key, no project id, a project id that isn't a number, a path that would break BuildConfig.
            "https://o123456.ingest.example.com/4567",
            SENTRY_DSN.substringBeforeLast('/'),
            SENTRY_DSN.substringBeforeLast('/') + "/settee",
            "$SENTRY_DSN\"",
            // The auth token pasted in its place.
            "sntrys_" + "x".repeat(40),
        )
        for (value in bad) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                checkedBuiltInKey(BuiltInKey.SENTRY_DSN, value)
            }
            assertThat(e).hasMessageThat().contains("SENTRY_DSN")
            assertThat(e).hasMessageThat().doesNotContain(value)
        }
    }
}
