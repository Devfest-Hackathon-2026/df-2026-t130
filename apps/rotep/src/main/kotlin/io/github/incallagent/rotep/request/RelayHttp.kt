// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.request

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * The real [RelayTransport]. Going out only, over HTTPS; the phone's token is read for every call,
 * so that changing it in the settings takes effect. Logs neither the token nor anything of a request.
 */
class RelayHttp(
    private val baseUrl: String,
    private val token: () -> String?,
    private val client: OkHttpClient = DEFAULT_CLIENT,
) : RelayTransport {
    override fun next(instance: String, waitSeconds: Int): List<RelayCommand> =
        execute(builder("/phone/next?instance=$instance&wait=$waitSeconds").get().build()) {
            RelayWire.commands(RelayWire.readAll(it.body.byteStream(), RelayWire.MAX_ANSWER_BYTES))
        }

    override fun reply(cmd: String, reply: JsonObject) {
        post("/phone/reply", buildJsonObject {
            put("cmd", cmd)
            put("reply", reply)
        })
    }

    override fun state(id: String, state: JsonObject) {
        post("/phone/state", buildJsonObject {
            put("id", id)
            put("state", state)
        })
    }

    private fun post(path: String, body: JsonObject) {
        execute(builder(path).post(body.toString().toRequestBody(JSON)).build()) { }
    }

    private fun builder(path: String): Request.Builder {
        val value = token() ?: throw IOException("no token")
        return Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $value")
    }

    private inline fun <T> execute(request: Request, read: (Response) -> T): T = client.newCall(request).execute().use { response ->
        if (response.code == 401) throw RelayUnauthorized()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        read(response)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val DEFAULT_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                // The token goes to the address the user saved, and nowhere it might be sent on to
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                // Longer than the longest wait the server is asked for
                .readTimeout(RELAY_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(RELAY_READ_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS)
                .build()
        }
    }
}

private const val RELAY_READ_TIMEOUT_SECONDS = 40L

/** The server's answers, apart from the network: unit tests read them directly. */
object RelayWire {
    const val MAX_ANSWER_BYTES = 256 * 1024

    private const val MAX_CMD_CHARS = 64
    private val ID_FORM = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")

    /** All of [stream] as text, or an [IOException] once it passes [max] bytes. */
    fun readAll(stream: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) break
            out.write(chunk, 0, n)
            if (out.size() > max) throw IOException("the server's answer is too long")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** The commands of a /phone/next answer. A command in an unknown form is left out, not guessed at. */
    fun commands(text: String): List<RelayCommand> {
        val array = try {
            (Json.parseToJsonElement(text) as? JsonObject)?.get("commands") as? JsonArray
        } catch (e: SerializationException) {
            null
        } ?: throw IOException("the server's answer cannot be read")
        return array.mapNotNull { element ->
            val command = element as? JsonObject ?: return@mapNotNull null
            val cmd = (command["cmd"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= MAX_CMD_CHARS } ?: return@mapNotNull null
            val method = (command["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            // Goes to the ledger on disk, so only the form the server makes
            val id = (command["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(ID_FORM::matches) ?: return@mapNotNull null
            val arg = command["arg"] as? JsonObject ?: return@mapNotNull null
            RelayCommand(cmd, method, id, arg, (command["assistant"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        }
    }
}
