// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.history

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.rotep.app.KeystoreCipher
import io.github.incallagent.rotep.app.RotepLog
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.history.BookingHistoryStore.Read
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.security.KeyStore
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * 「電話の記録」 for the rest of the app (ADR-045, ADR-050). The key store and the file are slow, so everything
 * but [findByNumber] and [latestForShop] runs on a thread of its own and reports in [state].
 *
 * What is kept here is for the user to read. Nothing of it goes into the AI's instructions on a later
 * call (ADR-024): what a shop said must not become an instruction to the next call's AI.
 */
object BookingHistory {
    const val NOT_NOW = "記録を今は読めませんでした。少し待ってから、もう一度開いてください"
    const val LOST = "記録を読めなかったので、消しました"
    const val PARTLY_LOST = "読めない記録がありました。読めたものだけを出しています"
    const val NOT_DELETED = "消せませんでした。少し待ってから、もう一度試してください"
    private const val CIPHER_KEY = "rotep_booking_history"
    private const val NUMBER_KEY = "rotep_number_hmac"
    private const val RECORD_WAIT_MS = 3_000L

    data class State(val entries: List<Entry> = emptyList(), val problem: String? = null)

    private val _state = MutableStateFlow(State())

    /** Empty until a screen that shows it has asked ([refresh]). */
    val state: StateFlow<State> = _state

    /** null until [init], and in the previews. */
    @Volatile
    private var store: BookingHistoryStore? = null

    /** One thread: the steps stay in order. */
    private val worker by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "booking-history").apply { isDaemon = true } }
    }

    /** Once per process: the app's start and a call being screened (ADR-047) may both ask, and there is one store. */
    @Synchronized
    fun init(context: Context) {
        if (store != null) return
        val s = BookingHistoryStore(
            context.noBackupFilesDir,
            RetryingCipher(KeystoreCipher(CIPHER_KEY)),
            KeystoreNumberHmac(NUMBER_KEY),
            Instant::now,
            { RotepLog.i("history", it) },
        )
        store = s
        // What is over its time goes, also when the screen is never opened
        if (s.exists) onWorker { it.read() }
    }

    /**
     * A call that ended. Waits until it is written, for a moment at most: the phone may end the app as
     * soon as the call's service stops. Its failure stays here.
     */
    fun record(policy: DelegationPolicy, result: CallSession.Result, lines: List<CallSession.Line>) {
        val written = onWorker { s -> show(s.add(BookingEntries.of(UUID.randomUUID().toString(), policy, result, lines))) } ?: return
        waitFor(written, RECORD_WAIT_MS) { RotepLog.w("history", it) }
    }

    fun refresh() {
        onWorker { show(it.read()) }
    }

    fun delete(id: String) {
        onWorker { s ->
            val deleted = s.delete(id)
            show(s.read())
            if (!deleted) _state.update { it.copy(problem = NOT_DELETED) }
        }
    }

    fun deleteAll() {
        onWorker { s ->
            s.deleteAll()
            show(s.read())
        }
    }

    /**
     * The newest kept call to [number], however it is written, or null: for a shop calling back. Also
     * while the switch is off, as long as the user has not deleted it.
     * Reads and decrypts a file: not on the main thread.
     */
    fun findByNumber(number: String): Entry? = store?.findByNumber(number)

    /**
     * The newest kept call to the shop called [shopName], or null: the result screen knows the shop
     * but not its number. Reads and decrypts a file: not on the main thread.
     */
    fun latestForShop(shopName: String): Entry? = store?.latestForShop(shopName)

    internal fun stateAfter(read: Read, before: State): State = when (read) {
        is Read.Found -> State(read.entries, problem = LOST.takeIf { read.lost } ?: PARTLY_LOST.takeIf { read.partly })
        Read.NotNow -> before.copy(problem = NOT_NOW)
    }

    private fun show(read: Read) = _state.update { stateAfter(read, it) }

    private fun onWorker(step: (BookingHistoryStore) -> Unit): Future<*>? {
        val s = store ?: return null
        return worker.submit {
            try {
                step(s)
            } catch (t: Throwable) {
                RotepLog.w("history", "step failed: ${t.javaClass.simpleName}")
            }
        }
    }
}

/** Waits for [work], for [ms] at most. It goes on by itself when this is over. */
internal fun waitFor(work: Future<*>, ms: Long, log: (String) -> Unit) {
    try {
        work.get(ms, TimeUnit.MILLISECONDS)
    } catch (t: Exception) {
        log("not finished in time: ${t.javaClass.simpleName}")
    }
}

/** HMAC-SHA256 with a key made in AndroidKeyStore: the key cannot be taken out of the phone. */
internal class KeystoreNumberHmac(private val alias: String) : NumberHmac {
    override fun of(normalizedNumber: String): String {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(existingKey() ?: newKey())
        return mac.doFinal(normalizedNumber.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    override fun forget() {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun newKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
        return gen.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
