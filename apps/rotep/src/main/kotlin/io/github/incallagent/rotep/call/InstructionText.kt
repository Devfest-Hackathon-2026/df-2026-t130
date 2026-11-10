// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.call

import io.github.incallagent.core.policy.DelegationPolicy

private val LINE_BREAKS = Regex("[\\r\\n\\u000B\\u000C\\u0085\\u2028\\u2029]+")

/** The text on one line: what comes from outside cannot start a line of a prompt of its own. */
internal fun String.oneLine(): String = replace(LINE_BREAKS, " ").trim()

/**
 * Words of the lines the speaking AI repeats word for word, as they are said. In a real call (11/9)
 * it misread 「お忙しいところ」. Native Japanese readings are where it slips, and in kana there is
 * nothing to read wrong.
 */
private val SAID_IN_KANA = listOf(
    "お忙しいところ" to "おいそがしいところ",
    "改めて" to "あらためて",
    "折り返し" to "おりかえし",
)

internal fun String.saidInKana(): String = SAID_IN_KANA.fold(this) { text, (written, said) -> text.replace(written, said) }

/** The lines the speaking AI repeats, [saidInKana]. Only for the AI: the confirm screen keeps what was approved. */
internal fun DelegationPolicy.withSpokenPhrases(): DelegationPolicy = copy(
    conversation = conversation.copy(
        aiDisclosure = conversation.aiDisclosure.saidInKana(),
        fallbackPhrase = conversation.fallbackPhrase.saidInKana(),
    ),
)

/**
 * The request as the speaking AI's instruction may take it. agent-live writes these fields into the
 * instruction as they are, among its own rules: a line break in one of them could start a line
 * that passes for a rule. They come from people and from other AIs.
 */
internal fun DelegationPolicy.withFlatText(): DelegationPolicy = copy(
    target = target.copy(businessName = target.businessName.oneLine()),
    request = request.copy(
        preferences = request.preferences.map { it.copy(text = it.text.oneLine()) },
        notesForShop = request.notesForShop?.oneLine(),
    ),
    disclosure = disclosure.copy(
        reservationName = disclosure.reservationName?.oneLine(),
        mayTell = disclosure.mayTell.map { it.copy(label = it.label.oneLine(), value = it.value.oneLine()) },
    ),
)
