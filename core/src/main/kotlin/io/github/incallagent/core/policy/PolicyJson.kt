// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.core.policy

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime

/** JSON form of [DelegationPolicy] and [CallOutcome] (storage, passing between agents, LLM output). */
object PolicyJson {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        // Newer app versions may add fields; older ones must still read the file
        ignoreUnknownKeys = true
    }

    fun encode(policy: DelegationPolicy): String = json.encodeToString(DelegationPolicy.serializer(), policy)

    fun decodePolicy(text: String): DelegationPolicy = json.decodeFromString(DelegationPolicy.serializer(), text)

    fun encode(outcome: CallOutcome): String = json.encodeToString(CallOutcome.serializer(), outcome)

    fun decodeOutcome(text: String): CallOutcome = json.decodeFromString(CallOutcome.serializer(), text)
}

/** "2026-11-06T03:00:00Z" */
object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** "2026-11-14T19:00" (no zone: see DelegationPolicy.timeZone) */
object LocalDateTimeIsoSerializer : KSerializer<LocalDateTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDateTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDateTime = LocalDateTime.parse(decoder.decodeString())
}
