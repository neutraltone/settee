/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */

private val versionName = Regex("""(\d+)\.(\d+)\.(\d+)""")

/**
 * Android's versionCode for a `major.minor.patch` version name: major×10000 + minor×100 + patch,
 * so 0.1.0 → 100 and 1.2.3 → 10203. A release changes the name only; deriving the code means the
 * two can never drift apart. Lives here rather than in the app build script so it can be tested.
 */
fun versionCodeOf(name: String): Int {
    val parts = requireNotNull(versionName.matchEntire(name)) {
        "Version name must be major.minor.patch, e.g. 0.1.0: \"$name\""
    }
    val (major, minor, patch) = parts.destructured.toList().map { it.toInt() }
    require(minor < 100 && patch < 100) { "Minor and patch must stay below 100: $name" }
    return major * 10000 + minor * 100 + patch
}
