// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.schedule

import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.PolicyJson
import io.github.incallagent.rotep.app.writtenByOtherVersion
import io.github.incallagent.rotep.call.SpokenReading
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * A call the user approved for a time they chose: the approved policy, the time, and how the AI says
 * the names in it. [toString] shows none of it (the policy holds a number and a name; ADR-021).
 */
class ScheduledCall(
    val policy: DelegationPolicy,
    val fireAt: Instant,
    val readings: List<SpokenReading> = emptyList(),
) {
    override fun toString() = "ScheduledCall(<hidden>)"
}

/** The form of the file, without any file, so unit tests see it. By hand: this module has no serialization plugin. */
object ScheduledCallJson {
    private const val VERSION = 1

    fun encode(call: ScheduledCall): String = buildJsonObject {
        put("version", VERSION)
        put("fireAt", call.fireAt.toString())
        put("policy", PolicyJson.encode(call.policy))
        put(
            "readings",
            buildJsonArray {
                for (r in call.readings) {
                    add(
                        buildJsonObject {
                            put("text", r.text)
                            put("kana", r.kana)
                        },
                    )
                }
            },
        )
    }.toString()

    /** null when [text] is not in a form this version of the app knows. */
    fun decode(text: String): ScheduledCall? = runCatching {
        val o = Json.parseToJsonElement(text).jsonObject
        if (o.getValue("version").jsonPrimitive.int != VERSION) return null
        ScheduledCall(
            policy = PolicyJson.decodePolicy(o.getValue("policy").jsonPrimitive.content),
            fireAt = Instant.parse(o.getValue("fireAt").jsonPrimitive.content),
            readings = o.getValue("readings").jsonArray.map {
                val r = it.jsonObject
                SpokenReading(r.getValue("text").jsonPrimitive.content, r.getValue("kana").jsonPrimitive.content)
            },
        )
    }.getOrNull()

    /** Another version of the app wrote [text]: not damaged, so it is not to be deleted. */
    fun fromOtherVersion(text: String): Boolean = writtenByOtherVersion(text, VERSION)
}
