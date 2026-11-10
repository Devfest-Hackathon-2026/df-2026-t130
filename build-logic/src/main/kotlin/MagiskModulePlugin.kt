/*
 * SPDX-FileCopyrightText: 2022-2026 Andrew Gunnerson (BCR)
 * SPDX-FileCopyrightText: 2026 incall-agent contributors
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from BCR app/build.gradle.kts (https://github.com/chenxiaolong/BCR,
 * commit 8c218fa60cda9c86c4e92bed6a4254e3ffcbb9e2): the `moduleProp*`,
 * `permissionsXml*` and `zip*` tasks. Moved into a convention plugin so that
 * every member app (:apps:<id>) and :poc can produce a Magisk module, and the permission list is
 * read from magisk-module/privapp-permissions.xml instead of being inlined.
 * The addon.d and update-json tasks were not ported.
 */

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.VariantOutputConfiguration
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.bundling.Zip
import java.io.File

abstract class MagiskModuleExtension {
    /** Human-readable module name, shown in the Magisk app and used for the zip file name. */
    abstract val moduleName: Property<String>

    /** One-line module description shown in the Magisk app. */
    abstract val moduleDescription: Property<String>

    /** module.prop `author`. */
    abstract val author: Property<String>
}

class MagiskModulePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("magiskModule", MagiskModuleExtension::class.java)
        ext.author.convention("incall-agent")

        project.pluginManager.withPlugin("com.android.application") {
            configure(project, ext)
        }
    }

    private fun configure(project: Project, ext: MagiskModuleExtension) {
        val androidComponents = project.extensions
            .getByType(ApplicationAndroidComponentsExtension::class.java)
        val rootDir = project.rootDir
        val magiskDir = File(rootDir, "magisk-module")
        val permissionsTemplate = File(magiskDir, "privapp-permissions.xml")
        val extraDir = project.layout.buildDirectory.dir("magisk")

        androidComponents.onVariants { variant ->
            val capitalized = variant.name.replaceFirstChar { it.uppercase() }
            val variantDir = extraDir.map { it.dir(variant.name) }
            val variantOutput = variant.outputs.first {
                it.outputType == VariantOutputConfiguration.OutputType.SINGLE
            }
            val variantVersionCode = variantOutput.versionCode
            val variantVersionName = variantOutput.versionName
            val variantApkFiles = variant.artifacts.get(SingleArtifact.APK).map {
                variant.artifacts.getBuiltArtifactsLoader().load(it)!!.elements.map { element ->
                    element.outputFile
                }
            }

            val moduleProp = project.tasks.register("moduleProp$capitalized") {
                inputs.property("moduleName", ext.moduleName)
                inputs.property("moduleDescription", ext.moduleDescription)
                inputs.property("author", ext.author)
                inputs.property("variant.applicationId", variant.applicationId)
                inputs.property("variantVersionCode", variantVersionCode)
                inputs.property("variantVersionName", variantVersionName)

                val outputFile = variantDir.map { it.file("module.prop") }
                outputs.file(outputFile)

                doLast {
                    val props = LinkedHashMap<String, String>()
                    props["id"] = variant.applicationId.get()
                    props["name"] = ext.moduleName.get()
                    props["version"] = "v${variantVersionName.get()}"
                    props["versionCode"] = variantVersionCode.get().toString()
                    props["author"] = ext.author.get()
                    props["description"] = ext.moduleDescription.get()

                    outputFile.get().asFile.writeText(
                        props.map { "${it.key}=${it.value}" }.joinToString("\n") + "\n"
                    )
                }
            }

            val permissionsXml = project.tasks.register("permissionsXml$capitalized") {
                inputs.property("variant.applicationId", variant.applicationId)
                inputs.file(permissionsTemplate)

                val outputFile = variantDir.map {
                    it.file("privapp-permissions-${variant.applicationId.get()}.xml")
                }
                outputs.file(outputFile)

                doLast {
                    val text = permissionsTemplate.readText()
                        .replace("@PACKAGE@", variant.applicationId.get())
                    outputFile.get().asFile.writeText(text)
                }
            }

            project.tasks.register("zip$capitalized", Zip::class.java) {
                group = "magisk"
                description = "Builds the Magisk module zip for the ${variant.name} variant."

                inputs.property("moduleName", ext.moduleName)
                inputs.property("variant.applicationId", variant.applicationId)
                inputs.property("variantVersionName", variantVersionName)
                inputs.files(variantApkFiles)

                archiveFileName.set(
                    ext.moduleName.zip(variantVersionName) { name, version ->
                        "$name-$version-${variant.name}.zip"
                    }
                )
                destinationDirectory.set(
                    project.layout.buildDirectory.dir("distributions/${variant.name}")
                )

                // Make the zip byte-for-byte reproducible (the APK itself is not)
                isPreserveFileTimestamps = false
                isReproducibleFileOrder = true

                from(moduleProp.map { it.outputs })
                from(permissionsXml.map { it.outputs }) {
                    into("system/etc/permissions")
                }
                from(variantApkFiles) {
                    into("system/priv-app/${variant.applicationId.get()}")
                }

                for (script in arrayOf("update-binary", "updater-script")) {
                    from(File(magiskDir, script)) {
                        into("META-INF/com/google/android")
                    }
                }

                from(File(rootDir, "LICENSE"))
            }
        }
    }
}
