// SPDX-License-Identifier: GPL-3.0-only

plugins {
    alias(libs.plugins.android.library)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

android {
    namespace = "io.github.incallagent.agent.live"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_21)
        targetCompatibility(JavaVersion.VERSION_21)
    }
}

dependencies {
    implementation(project(":core"))
    // WebSocket client for the Gemini Live API (ADR-006)
    implementation(libs.okhttp)
    testImplementation(libs.junit4)
}
