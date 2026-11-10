/*
 * SPDX-FileCopyrightText: 2022-2026 Andrew Gunnerson (BCR, build layout)
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * App of member "rotep". Generated from apps/_template by `./gradlew newApp -Pid=rotep`.
 * This file belongs to its owner: change dependencies and settings freely.
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    id("incallagent.magisk-module")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

android {
    namespace = "io.github.incallagent.rotep"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        // One package per member, so every member's app can be installed side by side
        applicationId = "io.github.incallagent.rotep"
        minSdk = 31
        targetSdk = 37
        versionCode = providers.gradleProperty("incallagent.versionCode").get().toInt()
        versionName = providers.gradleProperty("incallagent.versionName").get()
    }
    signingConfigs {
        // Release signing only when the keystore is provided via environment variables
        // (never commit keystores). Without it, assembleRelease produces an unsigned APK.
        val keystore = System.getenv("RELEASE_KEYSTORE")
        if (keystore != null) {
            create("release") {
                storeFile = File(keystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSPHRASE")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSPHRASE")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_21)
        targetCompatibility(JavaVersion.VERSION_21)
    }
    buildFeatures {
        compose = true
        // The version on the screen 「このアプリについて」
        buildConfig = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    sourceSets.getByName("main") {
        // The script a PC runs to start the helper. The app runs the same text over its own adb connection (ADR-041).
        assets.srcDir("helper")
    }
}

magiskModule {
    moduleName = "incall-agent-rotep"
    moduleDescription = "incall-agent (rotep): AI-assisted calls (priv-app)"
}

// The notices and the full license texts, as assets/licenses/ in the APK: the app shows them on 「このアプリについて」
abstract class CopyLicenseTexts : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val texts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().dir("licenses").asFile
        target.mkdirs()
        texts.files.forEach { it.copyTo(target.resolve(it.name), overwrite = true) }
    }
}

val copyLicenseTexts = tasks.register<CopyLicenseTexts>("copyLicenseTexts") {
    texts.from("NOTICE", fileTree("licenses"), rootProject.file("LICENSE"))
    outputDir.set(layout.buildDirectory.dir("generated/licenseTexts"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLicenseTexts, CopyLicenseTexts::outputDir)
    }
}

// PC check of the Gemini Live connection with the real API (src/test/.../smoke/LiveSmokeTest.kt).
// Only on purpose, with GEMINI_API_KEY in the environment:
//   ./gradlew --no-daemon :apps:rotep:testDebugUnitTest -PliveSmoke [-PliveSmokeModel=<model>]
// Then only that test runs, every time (never up-to-date or from the build cache), and the result
// goes to build/live-smoke/result.txt. Without -PliveSmoke the test is skipped even if the key is set.
if (providers.gradleProperty("liveSmoke").isPresent) {
    val liveSmokeResult = layout.buildDirectory.file("live-smoke/result.txt")
    val liveSmokeModel = providers.gradleProperty("liveSmokeModel")
    tasks.withType<Test>().configureEach {
        filter.includeTestsMatching("io.github.incallagent.rotep.smoke.LiveSmokeTest")
        systemProperty("incallagent.liveSmoke", "true")
        systemProperty("incallagent.liveSmoke.resultFile", liveSmokeResult.get().asFile.absolutePath)
        liveSmokeModel.orNull?.let { systemProperty("incallagent.liveSmoke.model", it) }
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("talks to the real Gemini Live API") { true }
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }
}

dependencies {
    // Shared foundation. Do not depend on another member's app (apps/*).
    implementation(project(":core"))
    implementation(project(":telecom"))
    implementation(project(":audio-bridge"))
    implementation(project(":agent-live"))

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    // StateFlow of the call flow and the screens
    implementation(libs.kotlinx.coroutines.android)
    // Rotep's own Gemini Live connection (call/RotepLive.kt), which adds the speaking rules and
    // the voice to the setup. Same library and version as agent-live (Apache-2.0, see NOTICE).
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit4)
    // WebSocket of the PC check of the Gemini Live connection (src/test/.../smoke/)
    testImplementation(libs.okhttp)
}

// Track 1: helper
android {
    buildFeatures {
        // The helper (a process started from a PC with adb) is reached through AIDL (src/main/aidl).
        aidl = true
    }
}
