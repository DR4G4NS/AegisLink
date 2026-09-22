package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.RelayConfig
import dev.aegis.remote.core.storage.AppSettingsRepository
import dev.aegis.remote.core.storage.RelayConfigRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FileDesktopAppSettingsRepository(
    private val file: Path = defaultDesktopSettingsPath(),
    private val json: Json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        },
) : AppSettingsRepository,
    RelayConfigRepository {
    private val lock = Any()

    override suspend fun getBoolean(
        key: String,
        default: Boolean,
    ): Boolean =
        synchronized(lock) {
            readSettings().booleans[key] ?: default
        }

    override suspend fun putBoolean(
        key: String,
        value: Boolean,
    ) {
        synchronized(lock) {
            val next =
                readSettings().let { settings ->
                    settings.copy(booleans = settings.booleans + (key to value))
                }
            writeSettings(next)
        }
    }

    override suspend fun getRelayConfig(): RelayConfig? =
        synchronized(lock) {
            readSettings().relayConfig?.takeIf { it.relayUrl.isNotBlank() }
        }

    override suspend fun saveRelayConfig(config: RelayConfig) {
        synchronized(lock) {
            val next = readSettings().copy(relayConfig = config)
            writeSettings(next)
        }
    }

    private fun readSettings(): StoredDesktopSettings {
        if (!file.exists()) return StoredDesktopSettings()
        return runCatching {
            json.decodeFromString<StoredDesktopSettings>(file.readText())
        }.getOrElse {
            StoredDesktopSettings()
        }
    }

    private fun writeSettings(settings: StoredDesktopSettings) {
        val directory = file.parent
        if (directory != null) {
            directory.createDirectories()
            restrictSettingsPosixPermissions(
                path = directory,
                permissions =
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
            )
            restrictSettingsWindowsAcl(directory, directory = true)
        }
        val temp = Files.createTempFile(directory ?: Path.of("."), "${file.fileName}.", ".tmp")
        try {
            temp.writeText(json.encodeToString(StoredDesktopSettings.serializer(), settings))
            restrictSettingsPosixPermissions(
                path = temp,
                permissions =
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    ),
            )
            restrictSettingsWindowsAcl(temp, directory = false)
            runCatching {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
            restrictSettingsPosixPermissions(
                path = file,
                permissions =
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    ),
            )
            restrictSettingsWindowsAcl(file, directory = false)
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
    }
}

@Serializable
private data class StoredDesktopSettings(
    val booleans: Map<String, Boolean> = emptyMap(),
    val relayConfig: RelayConfig? = null,
)

const val DESKTOP_CLIPBOARD_SYNC_ENABLED_KEY = "desktop.clipboardSync.enabled"

private fun defaultDesktopSettingsPath(): Path {
    val home = System.getProperty("user.home") ?: "."
    return Path.of(home, ".aegis", "desktop-settings.json")
}

private fun restrictSettingsPosixPermissions(
    path: Path,
    permissions: Set<PosixFilePermission>,
) {
    if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
        Files.setPosixFilePermissions(path, permissions)
    }
}

private fun restrictSettingsWindowsAcl(
    path: Path,
    directory: Boolean,
) {
    val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
    val owner = Files.getOwner(path)
    val permissions =
        if (directory) {
            enumValues<AclEntryPermission>().toSet()
        } else {
            enumValues<AclEntryPermission>().toSet()
        }
    val ownerOnlyEntry =
        AclEntry
            .newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(permissions)
            .build()
    view.acl = listOf(ownerOnlyEntry)
}
