// SPDX-License-Identifier: GPL-3.0-only

plugins {
    `kotlin-dsl`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("magiskModule") {
            id = "incallagent.magisk-module"
            implementationClass = "MagiskModulePlugin"
        }
    }
}
