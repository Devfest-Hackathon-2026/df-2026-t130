// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.agent.live

import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.PolicyJson
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** The documented example policy (docs/examples/), shared with :core tests. */
object TestPolicy {
    fun example(): DelegationPolicy =
        PolicyJson.decodePolicy(File("../docs/examples/delegation-policy.example.json").readText())

    val today: LocalDate = LocalDate.of(2026, 11, 7)
    val sat19: LocalDateTime = LocalDateTime.of(2026, 11, 14, 19, 0)
}
