// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import io.github.incallagent.core.policy.OutcomeCheck
import io.github.incallagent.core.policy.PolicyJson
import io.github.incallagent.core.policy.Purpose
import io.github.incallagent.rotep.call.CallSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/**
 * What is kept of the last real call, so that it can be read again after "最初に戻る" or after the
 * system ended the app. [shopName] says which call it was. It holds what the result screen shows and
 * nothing else: never the conversation, and there is no field for a phone number (the AI's summary
 * can still quote one the shop said).
 */
data class SavedResult(
    val result: CallSession.Result,
    val stage: RotepApp.Stage,
    val shopName: String,
)

/** The form of the file, without any file, so unit tests see it. By hand: this module has no serialization plugin. */
object SavedResultJson {
    private const val VERSION = 1

    fun encode(saved: SavedResult): String = buildJsonObject {
        val r = saved.result
        put("version", VERSION)
        put("shopName", saved.shopName)
        put("stage", saved.stage.name)
        put("outcome", PolicyJson.encode(r.outcome))
        put(
            "violations",
            buildJsonArray {
                for (v in r.violations) {
                    add(
                        buildJsonObject {
                            put("field", v.field)
                            put("message", v.message)
                        },
                    )
                }
            },
        )
        put("reportedByAi", r.reportedByAi)
        put("endReason", r.endReason.name)
        put("detail", r.detail)
        put("answered", r.answered)
        r.talkMs?.let { put("talkMs", it) }
        put("purpose", r.purpose.name)
    }.toString()

    /** null when [text] is not in a form this version of the app knows. */
    fun decode(text: String): SavedResult? = runCatching {
        val o = Json.parseToJsonElement(text).jsonObject
        if (o.getValue("version").jsonPrimitive.int != VERSION) return null
        SavedResult(
            result = CallSession.Result(
                outcome = PolicyJson.decodeOutcome(o.text("outcome")),
                violations = o.getValue("violations").jsonArray.map {
                    val v = it.jsonObject
                    OutcomeCheck.Violation(v.text("field"), v.text("message"))
                },
                reportedByAi = o.getValue("reportedByAi").jsonPrimitive.boolean,
                endReason = CallSession.EndReason.valueOf(o.text("endReason")),
                detail = o.text("detail"),
                answered = o.getValue("answered").jsonPrimitive.boolean,
                talkMs = o["talkMs"]?.jsonPrimitive?.long,
                purpose = Purpose.valueOf(o.text("purpose")),
            ),
            stage = RotepApp.Stage.valueOf(o.text("stage")),
            shopName = o.text("shopName"),
        )
    }.getOrNull()

    /** Another version of the app wrote [text]: not damaged, so it is not to be deleted. */
    fun fromOtherVersion(text: String): Boolean = writtenByOtherVersion(text, VERSION)

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
}

/**
 * Keeps one [SavedResult], in a file under [dir] (noBackupFilesDir in the app: app-private, never
 * part of a cloud backup or a device-to-device transfer). The next one replaces it. It is only a
 * convenience, so nothing here stops a call: a damaged file is deleted (one from another version of
 * the app is left alone), and the log only gets what happened and the exception's class.
 */
class LastResultStore(dir: File, private val log: (String) -> Unit = {}) {
    private val file = File(dir, FILE)
    private val temp = File(dir, "$FILE.tmp")

    /** false when it could not be written: what was kept before then stays. */
    @Synchronized
    fun save(saved: SavedResult): Boolean = try {
        replaceFile(temp, file, SavedResultJson.encode(saved).toByteArray(Charsets.UTF_8))
        true
    } catch (t: Throwable) {
        temp.delete()
        log("last result: not saved (${t.javaClass.simpleName})")
        false
    }

    /** What was kept, or null. */
    @Synchronized
    fun read(): SavedResult? {
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (e: IOException) {
            log("last result: not read this time (${e.javaClass.simpleName})")
            return null
        }
        val saved = SavedResultJson.decode(text)
        if (saved == null) {
            if (SavedResultJson.fromOtherVersion(text)) {
                log("last result: written by another version, kept")
                return null
            }
            log("last result: not in a known form, deleted")
            clear()
        }
        return saved
    }

    @Synchronized
    fun clear() {
        file.delete()
        temp.delete()
    }

    companion object {
        const val FILE = "last_call_result.json"
    }
}
