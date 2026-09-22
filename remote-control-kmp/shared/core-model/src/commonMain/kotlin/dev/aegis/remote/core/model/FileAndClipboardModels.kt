package dev.aegis.remote.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class FileEntryType {
    File,
    Directory,
    Symlink,
    Other,
}

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val type: FileEntryType,
    val size: Long,
    val modifiedAtEpochMillis: Long? = null,
    val permissions: String? = null,
)

@Serializable
sealed interface ClipboardPayload {
    @Serializable
    data class Text(
        val value: String,
    ) : ClipboardPayload
}
