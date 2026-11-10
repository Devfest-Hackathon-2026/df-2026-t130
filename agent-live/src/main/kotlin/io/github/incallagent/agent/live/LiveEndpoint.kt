// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import java.net.URLEncoder

/** How the app authenticates to the Gemini Live API. toString() never shows the secret. */
sealed interface LiveAuth {
    /**
     * A Gemini API key. Only for trying things out on your own device: ADR-006 says the
     * finished app uses [EphemeralToken] from token-server so no key is in the app.
     */
    class ApiKey(val key: String) : LiveAuth {
        override fun toString() = "ApiKey(<hidden>)"
    }

    /** Short-lived token issued by token-server (ADR-006). */
    class EphemeralToken(val token: String) : LiveAuth {
        override fun toString() = "EphemeralToken(<hidden>)"
    }
}

/**
 * WebSocket endpoints of the Live API (checked against the official "get started with
 * WebSockets" page on 2026-11-06).
 */
object LiveEndpoint {
    private const val BASE = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService."

    /** Contains the secret: never log the result. */
    fun url(auth: LiveAuth): String = when (auth) {
        is LiveAuth.ApiKey -> BASE + "BidiGenerateContent?key=" + enc(auth.key)
        is LiveAuth.EphemeralToken -> BASE + "BidiGenerateContentConstrained?access_token=" + enc(auth.token)
    }

    // The (String, Charset) overload is API 33+; minSdk is 31
    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
}
