// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import android.content.Context
import io.github.incallagent.rotep.app.ApiKeyStore
import io.github.incallagent.rotep.app.KeystoreCipher
import java.io.File

/** The address of the server as saved: https only, without the slash at the end. null: it cannot be used. */
internal fun normalizedRelayUrl(text: String): String? =
    text.trim().trimEnd('/').takeIf { it.startsWith("https://") && it.length > "https://".length }

internal const val RELAY_TOKEN_NEEDED = "サーバーの URL を変えたときは、端末のトークンも入れ直してください"

/** Why these settings cannot be saved, or null. A new address needs the token typed again: the old one must not go to another server. */
internal fun relaySaveProblem(savedUrl: String?, hasToken: Boolean, url: String, token: String): String? {
    val address = normalizedRelayUrl(url) ?: return "サーバーの URL は https:// で始めてください"
    return if (token.isBlank() && (!hasToken || address != savedUrl)) RELAY_TOKEN_NEEDED else null
}

/**
 * Saves the address and the token so that the old token can never go to a new address, wherever this
 * stops: the old token goes first when the address changes, then the address, then the new token.
 */
internal fun saveRelaySettings(
    savedUrl: String?,
    address: String,
    token: String,
    clearToken: () -> Unit,
    writeUrl: (String) -> Boolean,
    writeToken: (String) -> Boolean,
): String? {
    if (address != savedUrl) clearToken()
    if (!writeUrl(address)) return "サーバーの URL を保存できませんでした"
    if (token.isNotBlank() && !writeToken(token)) return "トークンを保存できませんでした"
    return null
}

/**
 * Where the server is and this phone's token. The token is kept like the API keys: encrypted with a
 * key of the Android Keystore, in noBackupFilesDir, never logged. The address is not a secret.
 */
class RelayConfig(context: Context, log: (String) -> Unit) {
    private val prefs = context.getSharedPreferences("phone_relay", Context.MODE_PRIVATE)
    private val tokenFile = File(context.noBackupFilesDir, TOKEN_FILE)
    private val tokens = ApiKeyStore(
        context.noBackupFilesDir,
        KeystoreCipher("rotep_relay_token"),
        log,
        fileName = TOKEN_FILE,
        plainFileName = "relay_token.plain",
        who = "サーバーの",
    )

    val url: String? get() = prefs.getString(KEY_URL, null)

    /** Without opening the keystore: the file is there or not. A token the keystore lost goes with its file. */
    val hasToken: Boolean get() = tokenFile.exists()

    /** The token, or null if it cannot be read now. Goes through the keystore: not on the main thread. */
    fun token(): String? = (tokens.read() as? ApiKeyStore.Read.Key)?.value

    /** null, or why it was not saved. */
    fun save(url: String, token: String): String? {
        relaySaveProblem(this.url, hasToken, url, token)?.let { return it }
        val address = normalizedRelayUrl(url) ?: return RELAY_TOKEN_NEEDED
        return saveRelaySettings(this.url, address, token, tokens::clear, { prefs.edit().putString(KEY_URL, it).commit() }, tokens::save)
    }

    fun clear() {
        tokens.clear()
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_URL = "url"
        const val TOKEN_FILE = "relay_token.enc"
    }
}
