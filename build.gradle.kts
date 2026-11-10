// SPDX-License-Identifier: GPL-3.0-only

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/**
 * Creates apps/<id>/ from apps/_template/, replacing "__ID__" in file contents and paths.
 *
 *   ./gradlew newApp -Pid=<id>
 *
 * <id> becomes part of the package name (io.github.incallagent.<id>), so it must be
 * lowercase letters and digits only, starting with a letter (e.g. GitHub name "Taro-Y" -> "taroy").
 */
abstract class NewAppTask : DefaultTask() {
    @get:Inject
    abstract val fs: FileSystemOperations

    @get:Input
    @get:Optional
    abstract val appId: Property<String>

    @get:Internal
    abstract val templateDir: DirectoryProperty

    @get:Internal
    abstract val appsDir: DirectoryProperty

    init {
        doNotTrackState("Creates source files in the working tree")
    }

    @TaskAction
    fun create() {
        val id = appId.orNull
            ?: throw GradleException("Give the app id: ./gradlew newApp -Pid=<id>")
        if (!Regex("[a-z][a-z0-9]{1,29}").matches(id)) {
            throw GradleException(
                "Invalid id '$id': use 2-30 lowercase letters/digits, starting with a letter " +
                    "(no '-', '_' or capitals)."
            )
        }
        if (id in RESERVED) {
            throw GradleException("'$id' is reserved (module name or Kotlin/Java keyword). Choose another id.")
        }
        val target = appsDir.get().asFile.resolve(id)
        if (target.exists()) {
            throw GradleException("${target.path} already exists.")
        }

        fs.copy {
            from(templateDir)
            into(target)
            exclude("build/**")
            includeEmptyDirs = false
            eachFile { path = path.replace("__ID__", id) }
            filter { line: String -> line.replace("__ID__", id) }
        }

        logger.lifecycle("Created apps/$id (package io.github.incallagent.$id).")
        logger.lifecycle("Build it with: ./gradlew :apps:$id:zipDebug")
    }

    companion object {
        val RESERVED = setOf(
            // shared modules / existing packages
            "core", "telecom", "audiobridge", "agent", "poc", "template", "apps",
            // Kotlin hard keywords and common Java keywords (not allowed as package segments)
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
            "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
            "true", "try", "typealias", "typeof", "val", "var", "when", "while",
            "abstract", "boolean", "byte", "case", "catch", "char", "const", "default", "double",
            "enum", "extends", "final", "finally", "float", "goto", "implements", "import",
            "instanceof", "int", "long", "native", "new", "private", "protected", "public",
            "short", "static", "switch", "synchronized", "throws", "transient", "void", "volatile",
        )
    }
}

tasks.register<NewAppTask>("newApp") {
    group = "incall-agent"
    description = "Creates apps/<id>/ from apps/_template. Usage: ./gradlew newApp -Pid=<id>"
    appId.set(providers.gradleProperty("id"))
    templateDir.set(layout.projectDirectory.dir("apps/_template"))
    appsDir.set(layout.projectDirectory.dir("apps"))
}
