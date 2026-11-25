/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/**
 * Applies the user's chosen UI language by wrapping a base [Context] with an overridden locale.
 *
 * Done at attachBaseContext time (rather than the newer per-app-language API) so it works the same
 * on every supported version — minSdk 24 through the latest — and on Fire OS, without pulling in
 * AppCompat. A blank tag means "follow the device".
 */
object LocaleUtils {

    fun wrap(base: Context): Context = wrap(base, AppSettings.savedLanguageTag(base))

    fun wrap(base: Context, tag: String): Context {
        // "Follow the device" can't just leave [base] alone: choosing it recreates the activity but
        // not the process, so the default locale and the application's own context (wrapped when the
        // process started) still carry the language chosen before. The device's languages come from
        // the system's resources, which no override touches, rather than a remembered
        // Locale.getDefault(), which would miss the device's language changing while the app runs.
        val locales = if (tag.isBlank()) {
            Resources.getSystem().configuration.locales
        } else {
            LocaleList(Locale.forLanguageTag(tag))
        }
        Locale.setDefault(locales[0])
        if (base.resources.configuration.locales == locales) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(locales)
        return base.createConfigurationContext(config)
    }
}
