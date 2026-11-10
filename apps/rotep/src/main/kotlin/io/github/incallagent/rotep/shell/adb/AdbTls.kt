// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.shell.adb

import android.annotation.SuppressLint
import android.net.ssl.SSLSockets
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * The TLS side of the connection to adbd, on the phone's own TLS 1.3 (Conscrypt): the protocol needs
 * TLS 1.3, our certificate as the client certificate, and for pairing the exported keying material
 * (a public API since Android 12, this app's minimum).
 *
 * Only ever used for 127.0.0.1. adb itself does not verify the other side's certificate either
 * (pairing proves the code; a connection proves the key): see adb's tls_connection and transport.
 */
internal object AdbTls {
    private const val ALIAS = "adb"
    const val HOST = "127.0.0.1"

    fun context(keys: AdbKeys): SSLContext {
        val context = SSLContext.getInstance("TLSv1.3")
        context.init(arrayOf(keyManager(keys)), arrayOf(NoServerCheck), SecureRandom())
        return context
    }

    /** Layers TLS 1.3 over an open plain socket. Closing the returned socket closes [plain] too. */
    fun layer(context: SSLContext, plain: Socket, port: Int): SSLSocket {
        val socket = context.socketFactory.createSocket(plain, HOST, port, true) as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.3")
        socket.useClientMode = true
        return socket
    }

    /** The keying material that the pairing mixes into the code. Throws when the platform refuses. */
    fun exportKeyingMaterial(socket: SSLSocket): ByteArray =
        SSLSockets.exportKeyingMaterial(socket, AdbPairingWire.EXPORTED_KEY_LABEL, null, AdbPairingWire.EXPORTED_KEY_SIZE)
            ?: throw javax.net.ssl.SSLException("no keying material: the handshake is not done")

    private fun keyManager(keys: AdbKeys) = object : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)

        override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
            ALIAS.takeIf { keyTypes?.any { it.startsWith("RSA") } == true }

        override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
            chooseClientAlias(keyTypes, issuers, null)

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null

        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(keys.certificate)

        override fun getPrivateKey(alias: String?): PrivateKey = keys.privateKey
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object NoServerCheck : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
