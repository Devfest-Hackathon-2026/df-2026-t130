// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import io.github.incallagent.core.policy.OutcomeStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Function declarations of the speaking agent (Gemini API `FunctionDeclaration`,
 * `parameters` in the OpenAPI-style Schema with upper-case types).
 *
 * The five axes from docs/design.md map to:
 *   空き確認・代替案判定 -> evaluate_offer, 結果報告 -> report_result, 終話 -> end_call,
 *   プッシュ音 -> send_dtmf.
 */
object LiveTools {
    const val EVALUATE_OFFER = "evaluate_offer"
    const val REPORT_RESULT = "report_result"
    const val END_CALL = "end_call"
    const val SEND_DTMF = "send_dtmf"

    val NAMES = listOf(EVALUATE_OFFER, REPORT_RESULT, END_CALL, SEND_DTMF)

    fun declarations(): JsonArray = buildJsonArray {
        add(
            function(
                EVALUATE_OFFER,
                "お店から日時・人数・金額・条件の提案があったとき、受けてよいかを判断する。受ける返事をする前に必ず呼ぶ。",
                properties = buildJsonObject {
                    put("start", prop("STRING", "開始日時。ISO 8601 の日時（例 2026-11-14T19:30）。時差の表記は付けない。取り消しの用件でキャンセル料だけの話のときは要らない"))
                    put("partySize", prop("INTEGER", "人数"))
                    put("pricePerPersonYen", prop("INTEGER", "1人あたりの金額（円）。金額の話が出たときだけ"))
                    put("confirmedPreferences", stringArray("お店が「できる」と言った希望（席、コースなど）"))
                    put("requiresCancellationFee", prop("BOOLEAN", "キャンセル料などの条件があるか"))
                    put("requiresPrepayment", prop("BOOLEAN", "前払い・予約金が必要か"))
                    put("isWaitlist", prop("BOOLEAN", "予約ではなくキャンセル待ちか"))
                },
                // A booking offer without a start is refused by OfferEvaluator
                required = emptyList(),
            )
        )
        add(
            function(
                REPORT_RESULT,
                "通話の結果を報告する。終話の前に必ず1回呼ぶ。",
                properties = buildJsonObject {
                    put("status", enumProp(OutcomeStatus.entries.map { it.name },
                        "BOOKED=第一希望で予約、BOOKED_ALTERNATIVE=範囲内の別の候補で予約、" +
                            "DONE=予約以外の用件が済んだ（予約を取り消せた、問い合わせの答えを聞けた。中身は summary に書く）、" +
                            "NEEDS_USER_DECISION=折り返すと伝えた、UNAVAILABLE=空きがない・休み、" +
                            "NOT_REACHED=つながらない・留守番電話、FAILED=その他の失敗"))
                    put("start", prop("STRING", "予約できた開始日時（ISO 8601、例 2026-11-14T19:30）"))
                    put("partySize", prop("INTEGER", "予約できた人数"))
                    put("nameUsed", prop("STRING", "お店に伝えた予約名"))
                    put("confirmedPreferences", stringArray("お店が確認した希望"))
                    put("shopConditions", stringArray("お店が言った条件（キャンセル料、時間制など）"))
                    put("pendingItems", stringArray("自分で決められず、折り返しにしたこと"))
                    put("summary", prop("STRING", "結果の短い要約（日本語）"))
                },
                required = listOf("status", "summary"),
            )
        )
        add(
            function(
                END_CALL,
                "電話を切る。report_result を呼び、お礼を言い終えてから呼ぶ。",
                properties = buildJsonObject {
                    put("reason", prop("STRING", "切る理由（用件が済んだ、折り返しにした、など）"))
                },
                required = listOf("reason"),
            )
        )
        add(
            function(
                SEND_DTMF,
                "自動音声の案内に従って、電話のボタンを押す。",
                properties = buildJsonObject {
                    put("digits", prop("STRING", "押すボタン。0〜9、*、# だけ（例 \"1\"、\"3#\"）"))
                },
                required = listOf("digits"),
            )
        )
    }

    private fun function(name: String, description: String, properties: JsonObject, required: List<String>) =
        buildJsonObject {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "OBJECT")
                put("properties", properties)
                if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            }
        }

    private fun prop(type: String, description: String) = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun enumProp(values: List<String>, description: String) = buildJsonObject {
        put("type", "STRING")
        put("description", description)
        putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
    }

    private fun stringArray(description: String) = buildJsonObject {
        put("type", "ARRAY")
        put("description", description)
        putJsonObject("items") { put("type", "STRING") }
    }
}
