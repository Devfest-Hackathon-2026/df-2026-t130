// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Puts [bytes] in [target], by way of [temp]. On the disk before the move: without that, a power cut
 * right after it can leave [target] empty, and the old contents are gone by then. Throws when it
 * cannot; [target] is then as it was, and [temp] is the caller's to delete.
 */
internal fun replaceFile(temp: File, target: File, bytes: ByteArray) {
    FileOutputStream(temp).use {
        it.write(bytes)
        it.fd.sync()
    }
    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/** [text] says it has another "version" than [known]: another version of the app wrote it, and it is not damaged. */
internal fun writtenByOtherVersion(text: String, known: Int): Boolean = runCatching {
    Json.parseToJsonElement(text).jsonObject.getValue("version").jsonPrimitive.int != known
}.getOrDefault(false)
