/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import app.opentv.player.SmbDataSource
import com.google.common.truth.Truth.assertThat
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import java.net.ConnectException
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A recording that isn't on the NAS any more reaches the player as a missing file, the same as one gone
 * from this device, so it's worded and retried as one (#475).
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SmbDataSourceTest {

    @Test
    fun `a file the NAS says isn't there is a missing file to the player`() {
        listOf(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND).forEach { status ->
            val source = SmbDataSource { throw refusal(status) }

            val thrown = assertThrows(DataSourceException::class.java) { source.open(SPEC) }

            assertThat(thrown.reason).isEqualTo(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        }
    }

    @Test
    fun `anything else the NAS throws reaches the player as it was`() {
        val failures = listOf(ConnectException("Connection refused"), refusal(NtStatus.STATUS_LOGON_FAILURE))
        failures.forEach { failure ->
            val source = SmbDataSource { throw failure }

            val thrown = assertThrows(Exception::class.java) { source.open(SPEC) }

            assertThat(thrown).isSameInstanceAs(failure)
        }
    }

    private fun refusal(status: NtStatus) = SMBApiException(status.value, SMB2MessageCommandCode.SMB2_CREATE, null)

    private companion object {
        val SPEC = DataSpec(Uri.parse("smb://nas.example.com/recordings/film.ts"))
    }
}
