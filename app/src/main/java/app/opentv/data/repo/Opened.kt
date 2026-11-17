/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

/**
 * What a film's or show's page gets when it opens: the title [Found], or [Gone] when it isn't stored
 * any more (#495). Not a nullable, which a page would read as still loading: a refresh can remove a
 * title that a Home row, the back stack or a restored app still points at.
 */
sealed interface Opened<out T> {
    data class Found<T>(val title: T) : Opened<T>

    data object Gone : Opened<Nothing>
}
