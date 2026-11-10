// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.core.policy

import java.io.File
import java.time.Instant

/** The documented example (docs/examples/), so the docs and the tests cannot drift apart. */
object Fixtures {
    /** Unit tests run with the module directory (core/) as working directory. */
    val exampleFile = File("../docs/examples/delegation-policy.example.json")

    fun example(): DelegationPolicy = PolicyJson.decodePolicy(exampleFile.readText())

    /** "Now" for tests: the day the example was written, before every window. */
    val now: Instant = Instant.parse("2026-11-06T03:10:00Z")
}
