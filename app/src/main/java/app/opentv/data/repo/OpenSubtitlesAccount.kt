/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.opentv.core.AppSettings
import app.opentv.data.remote.OpenSubtitlesClient
import app.opentv.data.remote.OpenSubtitlesFailure
import app.opentv.data.remote.OpenSubtitlesSession
import app.opentv.data.remote.SubtitleFile
import app.opentv.data.remote.SubtitleResult
import app.opentv.data.remote.SubtitleSearch
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Seals the OpenSubtitles password at rest. [open] returns null for anything it can't unseal. */
interface Sealer {
    fun seal(plain: String): String

    fun open(sealed: String): String?
}

/**
 * The box's OpenSubtitles Account (it belongs to the box, not a Profile, and is never synced), and
 * every call to OpenSubtitles made on its behalf. Signed in, downloads count against the viewer's
 * own allowance; signed out, against this address's anonymous five a day.
 *
 * The token lasts 24 hours. Rather than ask the viewer again, the password is kept sealed by
 * [sealer] and used to sign in afresh: when the token is a day old, or when OpenSubtitles turns it
 * down sooner. A renewal OpenSubtitles turns down is reported as [OpenSubtitlesFailure.TokenRejected]
 * and the account is left as it is, for the viewer to sign in again from Settings.
 */
class OpenSubtitlesAccount(
    private val settings: AppSettings,
    private val client: OpenSubtitlesClient,
    private val sealer: Sealer,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The signed-in username, or null when signed out. */
    val signedInAs: StateFlow<String?> = settings.openSubtitlesUsername

    val autoDownload: StateFlow<Boolean> = settings.openSubtitlesAuto

    /** One renewal at a time, so two downloads finding a stale token don't both sign in. */
    private val renewing = Mutex()

    fun setAutoDownload(on: Boolean) = settings.setOpenSubtitlesAuto(on)

    /** Signs in, keeping the account only if OpenSubtitles accepts it. */
    suspend fun signIn(username: String, password: String): Result<OpenSubtitlesSession> = io {
        client.signIn(username.trim(), password).onSuccess {
            settings.setOpenSubtitlesAccount(username.trim(), sealer.seal(password), it.token, clock())
        }
    }

    /** Forgets the username, sealed password and token. */
    fun signOut() = settings.removeOpenSubtitlesAccount()

    suspend fun search(query: SubtitleSearch): Result<List<SubtitleResult>> = io { client.search(query) }

    /** Downloads [fileId] on the account's allowance when signed in, else the anonymous one. */
    suspend fun download(fileId: Long): Result<SubtitleFile> = io {
        withToken { token -> client.download(fileId, token) }
    }

    /** The account's allowance today; fails when signed out. */
    suspend fun allowance(): Result<OpenSubtitlesSession> = io {
        if (signedInAs.value == null) return@io Result.failure(OpenSubtitlesFailure.TokenRejected())
        withToken { token -> client.account(token ?: throw OpenSubtitlesFailure.TokenRejected()) }
    }

    /** Runs [call] with a fresh token (null when signed out), renewing once if OpenSubtitles refuses it. */
    private suspend fun <T> withToken(call: (String?) -> Result<T>): Result<T> {
        if (signedInAs.value == null) return call(null)
        val stale = clock() - settings.openSubtitlesTokenAt >= TOKEN_LIFETIME
        val saved = settings.openSubtitlesToken.takeUnless { stale }
        val first = if (saved !=
            null
        ) {
            call(saved)
        } else {
            renew().fold(onSuccess = call, onFailure = { Result.failure(it) })
        }
        // A token refused before its day was out: renewed once, and the call tried again.
        val refused = saved != null && first.exceptionOrNull() is OpenSubtitlesFailure.TokenRejected
        return if (refused) renew().fold(onSuccess = call, onFailure = { Result.failure(it) }) else first
    }

    private suspend fun renew(): Result<String> = renewing.withLock {
        val username = signedInAs.value
        val password = settings.openSubtitlesSealedPassword?.let(sealer::open)
        if (username == null || password == null) return Result.failure(OpenSubtitlesFailure.TokenRejected())
        client.signIn(username, password).fold(
            onSuccess = {
                settings.setOpenSubtitlesToken(it.token, clock())
                Result.success(it.token)
            },
            onFailure = {
                Result.failure(
                    if (it is OpenSubtitlesFailure.SignInRejected) OpenSubtitlesFailure.TokenRejected() else it,
                )
            },
        )
    }

    private suspend fun <T> io(block: suspend () -> Result<T>): Result<T> = withContext(Dispatchers.IO) { block() }

    private companion object {
        /** OpenSubtitles' tokens last 24 hours; renewing a minute early avoids a refused request. */
        const val TOKEN_LIFETIME = 24 * 60 * 60 * 1000L - 60_000L
    }
}

/**
 * Seals with an AES key held in the Android Keystore, so the password can't be read back from a
 * copy of the settings file. EncryptedSharedPreferences would do the same but is deprecated, and
 * one value doesn't need a library.
 */
class KeystoreSealer : Sealer {
    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    override fun open(sealed: String): String? = runCatching {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "opensubtitles-password"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
