// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import io.github.incallagent.rotep.app.ApiKeyStore
import io.github.incallagent.rotep.app.SecretCipher
import java.io.File

/**
 * The approved call that waits for its time: encrypted the same way as the API keys, under [dir]
 * (noBackupFilesDir in the app: app-private, never part of a backup). Its own keystore entry, which
 * [clear] deletes with the file, so nothing of a call is left after it was placed or taken back.
 */
class ScheduledCallStore(
    dir: File,
    cipher: SecretCipher,
    log: (String) -> Unit = {},
) {
    sealed interface Read {
        class Found(val call: ScheduledCall) : Read {
            override fun toString() = "Found(<hidden>)"
        }

        data object None : Read

        /** It could not be read and was deleted: there is no call any more. */
        data object Unreadable : Read

        /** It could not be read just now and was kept: the next [read] tries again. */
        data object NotNow : Read
    }

    private val secrets = ApiKeyStore(dir, cipher, log, fileName = FILE, plainFileName = NO_PLAIN_FILE)
    private val file = File(dir, FILE)

    /** Without reading it: cheap enough for every step of the wait before the call. */
    val exists: Boolean get() = file.exists()

    /** false when it could not be saved: nothing is kept then. */
    fun save(call: ScheduledCall): Boolean = secrets.save(ScheduledCallJson.encode(call))

    fun read(): Read = when (val r = secrets.read()) {
        is ApiKeyStore.Read.Key -> ScheduledCallJson.decode(r.value)?.let { Read.Found(it) } ?: when {
            ScheduledCallJson.fromOtherVersion(r.value) -> Read.NotNow
            else -> {
                clear()
                Read.Unreadable
            }
        }
        ApiKeyStore.Read.None -> Read.None
        is ApiKeyStore.Read.Unreadable -> Read.Unreadable
        is ApiKeyStore.Read.NotNow -> Read.NotNow
    }

    fun clear() = secrets.clear()

    companion object {
        const val FILE = "scheduled_call.enc"

        /** Never written: without its own name, ApiKeyStore would look at the plaintext file of the Gemini key. */
        private const val NO_PLAIN_FILE = "scheduled_call.plain"
    }
}
