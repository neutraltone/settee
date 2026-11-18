/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import app.opentv.core.HttpStatus

/**
 * A guide of the viewer's own that didn't answer with a guide: [status] is the HTTP status it gave
 * instead. Not a [ProviderException]: no provider is behind it, so "your provider" would be wrong
 * about it, and it mustn't raise Home's banner.
 */
class GuideDownloadException(override val status: Int) :
    Exception("Guide download failed (HTTP $status)."),
    HttpStatus
