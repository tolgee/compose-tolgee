package dev.datlag.tolgee.common

import dev.datlag.tooling.existsSafely
import dev.datlag.tooling.scopeCatching
import java.io.File

@JvmOverloads
internal fun File.fullPathSafely(checkExists: Boolean = true): String? {
    if (checkExists && !this.existsSafely()) {
        return null
    }

    return scopeCatching {
        this.canonicalPath
    }.getOrNull()?.ifBlank { null } ?: scopeCatching {
        this.absolutePath
    }.getOrNull()?.ifBlank { null }
}