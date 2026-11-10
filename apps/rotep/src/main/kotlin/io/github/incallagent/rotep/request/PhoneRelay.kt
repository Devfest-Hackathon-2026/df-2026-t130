// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import io.github.incallagent.rotep.app.RotepLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val SHORTEST_ROUND_MS = 5_000L

/**
 * How long to wait after a round that went well. A round normally waits at the server: one that came
 * back at once (a command, or a server or proxy that does not wait) must not turn into a tight loop.
 */
internal fun relayRoundPause(roundMs: Long): Long = (SHORTEST_ROUND_MS - roundMs).coerceAtLeast(0)

/** How the connection to the server is going, for the settings screen. */
sealed interface RelayState {
    /** No server is set up: nothing happens at all. */
    data object Off : RelayState

    data object Ready : RelayState

    data object Connected : RelayState

    data class Failed(val reason: String) : RelayState
}

/**
 * Keeps the phone's mailbox round going: while the app is in front it waits at the server all the
 * time, and in the background [RelayPollJob] takes a look every 15 minutes. With no server set up
 * nothing here does anything. Nothing here looks at what the requests hold; that is
 * [PhoneRequestHandler]'s work. It only holds the application context.
 */
@SuppressLint("StaticFieldLeak")
object PhoneRelay {
    private const val WAIT_SECONDS = 25
    private const val FAILURE_PAUSE_MS = 5_000L
    private const val LONGEST_PAUSE_MS = 60_000L

    private enum class Round { OK, FAILED, REFUSED }

    private val instance = UUID.randomUUID().toString().replace("-", "")
    private val pushes = Executors.newSingleThreadExecutor { task -> Thread(task, "relay-push").apply { isDaemon = true } }

    private val log: (String) -> Unit = { RotepLog.i("relay", it) }
    private lateinit var app: Context
    private lateinit var config: RelayConfig
    private lateinit var handler: PhoneRequestHandler

    @Volatile
    private var client: RelayClient? = null

    @Volatile
    private var inFront = false

    private var loop: Thread? = null

    private val _state = MutableStateFlow<RelayState>(RelayState.Off)
    val state: StateFlow<RelayState> = _state

    /** Once, when the app's state is set up (RotepApp.init). Sends nothing and starts nothing if no server is set up. */
    @Synchronized
    fun start(context: Context, requests: PhoneRequestStore, requestHandler: PhoneRequestHandler) {
        if (::config.isInitialized) return
        app = context.applicationContext
        handler = requestHandler
        config = RelayConfig(app, log)
        requests.listener = ::pushLater
        rebuild()
        // What ended while nothing could tell the server: a process that died, a restart that took a call away
        requests.leftoverForServer()?.let(::pushLater)
    }

    /** The app came to the front, or left it. */
    fun foreground(visible: Boolean) {
        inFront = visible
        if (visible) startLoop()
    }

    /** From the 15-minute job, on its own thread: one look. */
    fun pollOnce() {
        val current = client ?: return
        runRound(current, 0)
    }

    private fun pushLater(id: String) {
        val current = client ?: return
        pushes.execute {
            try {
                current.pushState(id)
            } catch (e: RuntimeException) {
                log("state not sent: ${e.javaClass.simpleName}")
            }
        }
    }

    @Synchronized
    private fun startLoop() {
        if (client == null || loop?.isAlive == true) return
        loop = thread(name = "relay-loop", isDaemon = true) {
            var pause = FAILURE_PAUSE_MS
            while (inFront) {
                val current = client ?: break
                val started = SystemClock.elapsedRealtime()
                when (runRound(current, WAIT_SECONDS)) {
                    Round.OK -> {
                        pause = FAILURE_PAUSE_MS
                        try {
                            Thread.sleep(relayRoundPause(SystemClock.elapsedRealtime() - started))
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    // The token is wrong: asking again will not help. The next time the app comes to the front, or a new token, starts it again
                    Round.REFUSED -> break
                    Round.FAILED -> {
                        try {
                            Thread.sleep(pause)
                        } catch (_: InterruptedException) {
                            break
                        }
                        pause = (pause * 2).coerceAtMost(LONGEST_PAUSE_MS)
                    }
                }
            }
        }
    }

    private fun runRound(current: RelayClient, waitSeconds: Int): Round = try {
        current.pollOnce(waitSeconds)
        _state.value = RelayState.Connected
        Round.OK
    } catch (e: RelayUnauthorized) {
        log("the server refused the token")
        _state.value = RelayState.Failed("サーバーがトークンを受け付けません。端末のトークンを確かめてください")
        Round.REFUSED
    } catch (e: IOException) {
        log("server not reached: ${e.javaClass.simpleName}")
        _state.value = RelayState.Failed("サーバーにつながりません")
        Round.FAILED
    } catch (e: RuntimeException) {
        // Nothing here may end the app
        log("round failed: ${e.javaClass.simpleName}")
        _state.value = RelayState.Failed("サーバーとのやり取りに失敗しました")
        Round.FAILED
    }

    @Synchronized
    private fun rebuild() {
        val url = config.url
        val hasToken = config.hasToken
        // The token only for the address this client was made for: a new token is written after the new address
        client = if (url != null && hasToken) RelayClient(RelayHttp(url, { config.token().takeIf { config.url == url } }), handler, instance, log) else null
        _state.value = if (client != null) RelayState.Ready else RelayState.Off
        RelayPollJob.sync(app, client != null)
        if (inFront) startLoop()
    }

    // --- the settings ---

    val url: String? get() = if (::config.isInitialized) config.url else null

    val hasToken: Boolean get() = ::config.isInitialized && config.hasToken

    /** [done] gets null, or why it was not saved. */
    fun save(url: String, token: String, done: (String?) -> Unit) {
        if (!::config.isInitialized) return done("アプリの準備ができていません")
        // Goes through the keystore: not on the thread of the tap
        thread(name = "relay-save", isDaemon = true) {
            val problem = config.save(url, token)
            if (problem == null) rebuild()
            done(problem)
        }
    }

    fun clear(done: () -> Unit) {
        if (!::config.isInitialized) return done()
        thread(name = "relay-save", isDaemon = true) {
            config.clear()
            rebuild()
            done()
        }
    }
}
