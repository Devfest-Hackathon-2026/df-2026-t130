// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The real TCP and TLS part of talking to the phone's own adbd. Only ever 127.0.0.1. */
internal object AdbConnect {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Connects to adbd on [port], runs [block] and closes the connection. */
    fun <T> withShell(port: Int, keys: AdbKeys, readTimeoutMs: Int = READ_TIMEOUT_MS, block: (AdbClient) -> T): T {
        val plain = Socket()
        var layered: javax.net.ssl.SSLSocket? = null
        try {
            plain.connect(InetSocketAddress(AdbTls.HOST, port), CONNECT_TIMEOUT_MS)
            plain.tcpNoDelay = true
            plain.soTimeout = readTimeoutMs
            val client = AdbClient.connect(AdbStreams(plain.getInputStream(), plain.getOutputStream()), startTls = {
                val socket = AdbTls.layer(AdbTls.context(keys), plain, port)
                layered = socket
                socket.startHandshake()
                AdbStreams(socket.inputStream, socket.outputStream)
            })
            return block(client)
        } finally {
            runCatching { layered?.close() }
            runCatching { plain.close() }
        }
    }

    /** Pairs with adbd's pairing port. Throws [AdbPairingException] (or an I/O error) when it does not work. */
    fun pair(port: Int, code: String, keys: AdbKeys, deviceName: String) {
        val plain = Socket()
        var layered: javax.net.ssl.SSLSocket? = null
        try {
            plain.connect(InetSocketAddress(AdbTls.HOST, port), CONNECT_TIMEOUT_MS)
            plain.tcpNoDelay = true
            plain.soTimeout = READ_TIMEOUT_MS
            val socket = AdbTls.layer(AdbTls.context(keys), plain, port)
            layered = socket
            socket.startHandshake()
            val exported = AdbTls.exportKeyingMaterial(socket)
            AdbPairingExchange(
                code.toByteArray(Charsets.US_ASCII),
                exported,
                AdbKeyFormat.peerInfo(keys.publicKey, deviceName),
            ).run(socket.inputStream, socket.outputStream)
        } finally {
            runCatching { layered?.close() }
            runCatching { plain.close() }
        }
    }
}

/** Finds the ports of the phone's own adbd with mDNS. Only services hosted on this phone count. */
internal class AdbServiceFinder(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    /** Discovery that keeps going until [stop]. */
    inner class Search(type: String, private val onPort: (Int) -> Unit) {
        private val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo != null) resolve(serviceInfo)
            }
        }

        init {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        }

        fun stop() {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }

        @Suppress("DEPRECATION") // resolveService works on every Android this app runs on; the newer call needs API 34.
        private fun resolve(info: NsdServiceInfo) {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit

                override fun onServiceResolved(resolved: NsdServiceInfo?) {
                    val service = resolved ?: return
                    if (AdbPorts.isOnThisDevice(hostAddresses(service), localAddresses())) onPort(service.port)
                }
            })
        }

        private fun hostAddresses(info: NsdServiceInfo): List<String> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return info.hostAddresses.mapNotNull { it.hostAddress }
            }
            @Suppress("DEPRECATION")
            return listOfNotNull(info.host?.hostAddress)
        }
    }

    /**
     * The first port found for [type] within [timeoutMs], leaving out [exclude], or null. [giveUp] is asked
     * every moment: no use waiting any longer. Blocking: never on the main thread.
     */
    fun findOnce(type: String, timeoutMs: Long, exclude: Set<Int> = emptySet(), giveUp: () -> Boolean = { false }): Int? {
        val found = CountDownLatch(1)
        val port = AtomicInteger(0)
        val search = Search(type) { value ->
            if (value !in exclude && port.compareAndSet(0, value)) found.countDown()
        }
        return try {
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            var result: Int? = null
            while (result == null && System.nanoTime() < end) {
                if (found.await(GIVE_UP_CHECK_MS, TimeUnit.MILLISECONDS)) result = port.get() else if (giveUp()) break
            }
            result
        } finally {
            search.stop()
        }
    }

    private companion object {
        const val GIVE_UP_CHECK_MS = 200L
    }

    private fun localAddresses(): Set<String> = NetworkInterface.getNetworkInterfaces().asSequence()
        .flatMap { it.inetAddresses.asSequence() }
        .mapNotNull { it.hostAddress }
        .toSet()
}

/** The small decisions of service discovery, apart from Android so they can be tested. */
internal object AdbPorts {
    const val PAIRING_TYPE = "_adb-tls-pairing._tcp"
    const val CONNECT_TYPE = "_adb-tls-connect._tcp"

    /** Enough for the records of a few earlier sessions: each try costs a search. */
    const val MAX_PORT_TRIES = 3

    /**
     * [attempt] on a port that [find] offers, and what it returned; null when [find] offers none.
     *
     * After Wireless debugging was turned off and on, mDNS can go on offering the record of the earlier
     * session, on a port nothing listens on any more, next to the new record (whose name then ends in
     * " (2)") and before it. A refused connection has sent nothing, so [find] is asked again, with the
     * ports that refused. So is one that does not know the app's key: anything on the phone can offer a
     * service under this name, and the first one to answer need not be adbd. If no other port turns up
     * the last answer is thrown, a refused key before a refused connection; any other failure has nothing
     * to do with the port and ends it at once.
     */
    inline fun <T : Any> firstListening(find: (refused: Set<Int>) -> Int?, onRefused: (Int) -> Unit = {}, attempt: (Int) -> T): T? {
        val refused = LinkedHashSet<Int>()
        var last: IOException? = null
        while (refused.size < MAX_PORT_TRIES) {
            val port = find(refused) ?: break
            try {
                return attempt(port)
            } catch (e: ConnectException) {
                refused += port
                if (last !is AdbNotAuthorizedException) last = e
                onRefused(port)
            } catch (e: AdbNotAuthorizedException) {
                refused += port
                last = e
                onRefused(port)
            }
        }
        last?.let { throw it }
        return null
    }

    /** True when one of the service's addresses is an address of this phone. */
    fun isOnThisDevice(serviceAddresses: List<String>, localAddresses: Set<String>): Boolean {
        val local = localAddresses.mapTo(HashSet()) { stripScope(it) }
        return serviceAddresses.any { stripScope(it) in local }
    }

    /** An IPv6 address can carry its interface after a '%', on one side and not on the other. */
    private fun stripScope(address: String): String = address.substringBefore('%')

    /** A port typed by hand, or null when it is not a valid one. */
    fun parsePort(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** The 6-digit code as digits, or null when it is not exactly that. */
    fun parseCode(text: String): String? = text.filter { !it.isWhitespace() }.takeIf { it.length == 6 && it.all { c -> c in '0'..'9' } }
}
