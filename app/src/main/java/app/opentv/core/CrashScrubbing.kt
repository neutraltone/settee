/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.App
import io.sentry.protocol.Device
import io.sentry.protocol.Mechanism
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.SentryThread
import io.sentry.protocol.User

/**
 * What a Crash Report may carry (ADR 0009, docs/PRIVACY.md): an allow-list, applied to everything
 * Sentry would send. Each field is copied across only if it's listed here, so a field a later SDK
 * version adds stays on the device until someone lists it, rather than leaking until someone thinks
 * to remove it. Part of [CrashReports]; nothing else uses it.
 */
internal object CrashScrubbing {

    /** The tag naming the screen that was open. */
    const val SCREEN_TAG = "screen"

    /** Breadcrumbs: none are on the list. Even a navigation one can carry a route's arguments. */
    @Suppress("FunctionOnlyReturningConstant", "UnusedParameter") // The before-breadcrumb hook's shape.
    fun breadcrumb(breadcrumb: Breadcrumb): Breadcrumb? = null

    /** [event] with only the listed fields, every string in them [scrub]bed, [installId] as its only id. */
    fun event(event: SentryEvent, installId: String?, screen: String?): SentryEvent = event.apply {
        exceptions = exceptions?.map(::exception)
        threads = threads?.map(::thread)
        message = message?.let { from ->
            io.sentry.protocol.Message().apply {
                formatted = (from.formatted ?: from.message)?.let(::scrub)
            }
        }
        user = installId?.let { id -> User().apply { this.id = id } }
        tags = screen?.let { mapOf(SCREEN_TAG to it) }
        extras = null
        breadcrumbs = null
        request = null
        serverName = null
        setModules(null)
        transaction = null
        logger = null
        fingerprints = null
        keepContexts(this)
    }

    /** Device model, free memory, the Android or Fire OS version, and Settee's version: nothing else. */
    private fun keepContexts(event: SentryEvent) {
        val contexts = event.contexts
        val device = contexts.device?.let { from ->
            Device().apply {
                model = from.model
                freeMemory = from.freeMemory
            }
        }
        val os = contexts.operatingSystem?.let { from ->
            OperatingSystem().apply {
                name = from.name
                version = from.version
                build = from.build
            }
        }
        val app = contexts.app?.let { from ->
            App().apply {
                appVersion = from.appVersion
                appBuild = from.appBuild
            }
        }
        contexts.keys().toList().forEach(contexts::remove)
        device?.let(contexts::setDevice)
        os?.let(contexts::setOperatingSystem)
        app?.let(contexts::setApp)
    }

    private fun exception(from: SentryException) = SentryException().apply {
        type = from.type
        module = from.module
        value = from.value?.let(::scrub)
        threadId = from.threadId
        stacktrace = from.stacktrace?.let(::stackTrace)
        mechanism = from.mechanism?.let { was ->
            Mechanism().apply {
                type = was.type
                isHandled = was.isHandled
                exceptionId = was.exceptionId
                parentId = was.parentId
                isExceptionGroup = was.isExceptionGroup
            }
        }
    }

    // Names only for the main thread: any other can be named after what it's doing, as OkHttp's are
    // after the address they're fetching.
    private fun thread(from: SentryThread) = SentryThread().apply {
        id = from.id
        name = from.name?.takeIf { it == "main" }
        isCrashed = from.isCrashed
        isCurrent = from.isCurrent
        isMain = from.isMain
        isDaemon = from.isDaemon
        state = from.state
        stacktrace = from.stacktrace?.let(::stackTrace)
    }

    private fun stackTrace(from: SentryStackTrace) = SentryStackTrace().apply {
        frames = from.frames?.map(::frame)
    }

    // Where in the code, never what it held: no locals, source lines or absolute paths.
    private fun frame(from: SentryStackFrame) = SentryStackFrame().apply {
        module = from.module?.let(::scrub)
        function = from.function?.let(::scrub)
        filename = from.filename?.let(::scrub)
        lineno = from.lineno
        isInApp = from.isInApp
        isNative = from.isNative
        platform = from.platform
    }

    /**
     * [text] with every web address cut down to its scheme and path (no login, host, port, query or
     * fragment, and an Xtream path's username and password starred out), anything shaped like a MAC
     * starred out, and the host or address an exception names on its own taken out.
     */
    fun scrub(text: String): String = text
        .replace(URL) { match -> "${match.groupValues[1]}://${path(match.groupValues[2])}" }
        .replace(CREDENTIAL_PARAMETER) { "${it.groupValues[1]}=*" }
        .replace(NAMED_HOST) { "${it.groupValues[1]}*" }
        .replace(MAC) { "**:**:**:**:**:**" }
        .replace(ENCODED_MAC) { "**%3A**%3A**%3A**%3A**%3A**" }
        .replace(IPV4) { "*.*.*.*" }

    // An Xtream stream's path is /live/<username>/<password>/<id>, and its other kinds the same.
    private fun path(path: String): String {
        val segments = path.split('/').toMutableList()
        val kind = segments.indexOfFirst { it.lowercase() in XTREAM_KINDS }
        // The kind, the username and password, and something after them: a path that stops short is
        // a page of the panel's, not a stream.
        if (kind >= 0 && segments.size > kind + XTREAM_CREDENTIALS + 1) {
            for (credential in 1..XTREAM_CREDENTIALS) segments[kind + credential] = "*"
        }
        return segments.joinToString("/") { if (TOKEN.matches(it)) "*" else it }
    }

    private val XTREAM_KINDS = setOf("live", "movie", "series", "timeshift")
    private const val XTREAM_CREDENTIALS = 2

    // scheme :// [authority] [path], stopping at the query, fragment, whitespace or a quote.
    private val URL = Regex("""\b([A-Za-z][A-Za-z0-9+.-]*)://[^/\s?#"'<>]*(/[^\s?#"'<>]*)?(?:[?#][^\s"'<>]*)?""")

    // A long run of letters and digits in a path is a token or a key, not a name worth keeping.
    private val TOKEN = Regex("""(?=.*\d)[A-Za-z0-9_-]{24,}""")

    private val CREDENTIAL_PARAMETER =
        Regex("""(?i)\b(username|password|user|pass|pwd|token|mac|sn|device_id|signature)=[^&\s"'<>]*""")

    // How the JDK and OkHttp name a host outside a URL: Unable to resolve host "x", failed to connect to x/1.2.3.4.
    private val NAMED_HOST = Regex("""(?i)(resolve host "|connect to )[^\s"/:]+""")

    private val MAC = Regex("""(?i)\b[0-9a-f]{2}([:-])[0-9a-f]{2}(?:\1[0-9a-f]{2}){4}\b""")
    private val ENCODED_MAC = Regex("""(?i)\b[0-9a-f]{2}(?:%3A[0-9a-f]{2}){5}\b""")
    private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
}
