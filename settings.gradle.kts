/*
 * SPDX-FileCopyrightText: 2022 Andrew Gunnerson (BCR, original layout)
 * SPDX-License-Identifier: GPL-3.0-only
 */

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "incall-agent"

include(":core")
include(":telecom")
include(":audio-bridge")
include(":agent-live")
include(":agent-think")

// Members' apps: every folder apps/<id>/ with a build.gradle.kts becomes :apps:<id>.
// Nobody has to edit this file to add an app, so PRs from different members never collide here.
// Folders starting with "_" (apps/_template) are skipped. Create a new app with:
//   ./gradlew newApp -Pid=<id>
file("apps").listFiles()
    ?.filter { it.isDirectory && !it.name.startsWith("_") && File(it, "build.gradle.kts").isFile }
    ?.sortedBy { it.name }
    ?.forEach { include(":apps:${it.name}") }
// token-server/ is not a Gradle project (folder + README only for now).
// magisk-module/ holds the Magisk scripts and the privapp permission template;
// the zip tasks live in build-logic/.
