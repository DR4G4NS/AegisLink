package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.DeviceAuthorization
import dev.aegis.remote.core.pairing.DeviceTrustStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

enum class TrustStoreFailureReason {
    FileRead,
    FileAccess,
    PayloadFormat,
    PayloadBase64,
    PayloadUnprotect,
    Json,
    Acl,
}

class TrustStoreAccessException(
    val reason: TrustStoreFailureReason,
    cause: Throwable,
) : IllegalStateException("Trust store unavailable: $reason", cause)

sealed interface TrustStoreReadResult {
    data object Missing : TrustStoreReadResult

    data class Available(
        val authorizations: List<DeviceAuthorization>,
    ) : TrustStoreReadResult

    data class Failed(
        val error: TrustStoreAccessException,
    ) : TrustStoreReadResult
}

/** Validates the owner-only access boundary before an existing trust blob is accepted. */
fun interface TrustStoreSecurityInspector {
    fun validate(
        path: Path,
        directory: Boolean,
    )
}

interface RecoverableDeviceTrustStore {
    /** Replaces the failed store only after this backup has passed the same security and decoding checks. */
    suspend fun restoreFromBackup(backup: Path)

    /** Discards trust records only after an explicit, visible user confirmation. */
    suspend fun resetAfterConsent()
}

class FileDeviceTrustStore(
    private val file: Path = defaultTrustStorePath(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val payloadProtector: TrustStorePayloadProtector = defaultTrustStorePayloadProtector(file),
    private val json: Json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        },
    private val securityInspector: TrustStoreSecurityInspector = TrustStoreSecurityInspector(::validateTrustStoreAccess),
    private val atomicMove: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    },
) : DeletableDeviceTrustStore,
    RecoverableDeviceTrustStore {
    private val lock = Any()

    /** Distinguishes a missing store from a malformed or inaccessible existing store. */
    fun readResult(): TrustStoreReadResult = synchronized(lock) { readStoreResult() }

    override suspend fun saveAuthorization(authorization: DeviceAuthorization) {
        synchronized(lock) {
            val current =
                readStore()
                    .authorizations
                    .filterNot { it.remoteDeviceId == authorization.remoteDeviceId } + authorization
            writeStore(StoredDeviceTrust(current))
        }
    }

    override suspend fun revoke(remoteDeviceId: String) {
        synchronized(lock) {
            val current =
                readStore().authorizations.map { authorization ->
                    if (authorization.remoteDeviceId.value == remoteDeviceId) {
                        authorization.copy(revokedAtEpochMillis = clock())
                    } else {
                        authorization
                    }
                }
            writeStore(StoredDeviceTrust(current))
        }
    }

    override suspend fun listAuthorizedDevices(): List<DeviceAuthorization> =
        synchronized(lock) {
            readStore().authorizations.sortedBy { it.displayName }
        }

    override suspend fun deleteRevoked(remoteDeviceId: String): DeleteRevokedDeviceResult {
        return synchronized(lock) {
            val store = readStore()
            val authorization =
                store.authorizations.firstOrNull { it.remoteDeviceId.value == remoteDeviceId }
                    ?: return@synchronized DeleteRevokedDeviceResult.NotFound
            if (authorization.revokedAtEpochMillis == null) {
                return@synchronized DeleteRevokedDeviceResult.MustRevokeFirst
            }
            writeStore(
                StoredDeviceTrust(
                    authorizations = store.authorizations.filterNot { it.remoteDeviceId.value == remoteDeviceId },
                ),
            )
            DeleteRevokedDeviceResult.Deleted
        }
    }

    override suspend fun restoreFromBackup(backup: Path) {
        synchronized(lock) {
            val restored = readStore(backup, missingIsEmpty = false)
            writeStore(restored)
        }
    }

    override suspend fun resetAfterConsent() {
        synchronized(lock) { writeStore(StoredDeviceTrust()) }
    }

    private fun readStore(
        path: Path = file,
        missingIsEmpty: Boolean = true,
    ): StoredDeviceTrust =
        when (val result = readStoreResult(path)) {
            TrustStoreReadResult.Missing -> {
                if (missingIsEmpty) {
                    StoredDeviceTrust()
                } else {
                    throw TrustStoreAccessException(
                        TrustStoreFailureReason.FileRead,
                        java.nio.file.NoSuchFileException(path.toString()),
                    )
                }
            }

            is TrustStoreReadResult.Available -> {
                StoredDeviceTrust(result.authorizations)
            }

            is TrustStoreReadResult.Failed -> {
                throw result.error
            }
        }

    private fun readStoreResult(path: Path = file): TrustStoreReadResult =
        try {
            val payload = readPayloadTextOrMissing(path) ?: return TrustStoreReadResult.Missing
            validateSecureAccess(path)
            val plainText = unprotectPayload(payload)
            val store = json.decodeFromString<StoredDeviceTrust>(plainText)
            TrustStoreReadResult.Available(store.authorizations)
        } catch (error: TrustStoreAccessException) {
            TrustStoreReadResult.Failed(error)
        } catch (error: Throwable) {
            TrustStoreReadResult.Failed(TrustStoreAccessException(TrustStoreFailureReason.Json, error))
        }

    private fun validateSecureAccess(path: Path) {
        try {
            securityInspector.validate(path, directory = false)
            path.parent?.let { securityInspector.validate(it, directory = true) }
        } catch (error: TrustStoreAccessException) {
            throw error
        } catch (error: Throwable) {
            throw TrustStoreAccessException(TrustStoreFailureReason.Acl, error)
        }
    }

    private fun writeStore(store: StoredDeviceTrust) {
        val directory = file.parent
        if (directory != null) {
            directory.createDirectories()
            restrictPosixPermissions(
                path = directory,
                permissions =
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
            )
            restrictWindowsAcl(directory, directory = true)
        }
        val payload = payloadProtector.protect(json.encodeToString(StoredDeviceTrust.serializer(), store))
        val temp = Files.createTempFile(directory ?: Path.of("."), "${file.fileName}.", ".tmp")
        try {
            temp.writeText(payload)
            restrictPosixPermissions(
                path = temp,
                permissions =
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    ),
            )
            restrictWindowsAcl(temp, directory = false)
            atomicMove(temp, file)
            restrictPosixPermissions(
                path = file,
                permissions =
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    ),
            )
            restrictWindowsAcl(file, directory = false)
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    private fun readPayloadTextOrMissing(path: Path): String? =
        try {
            Files.readString(path)
        } catch (_: java.nio.file.NoSuchFileException) {
            null
        } catch (error: java.nio.file.AccessDeniedException) {
            throw TrustStoreAccessException(TrustStoreFailureReason.FileAccess, error)
        } catch (error: SecurityException) {
            throw TrustStoreAccessException(TrustStoreFailureReason.FileAccess, error)
        } catch (error: Throwable) {
            throw TrustStoreAccessException(TrustStoreFailureReason.FileRead, error)
        }

    private fun unprotectPayload(payload: String): String {
        val protected =
            try {
                payloadProtector.canUnprotect(payload)
            } catch (error: Throwable) {
                throw TrustStoreAccessException(TrustStoreFailureReason.PayloadFormat, error)
            }
        if (!protected) return payload
        return try {
            payloadProtector.unprotect(payload)
        } catch (error: IllegalArgumentException) {
            throw TrustStoreAccessException(TrustStoreFailureReason.PayloadBase64, error)
        } catch (error: Throwable) {
            throw TrustStoreAccessException(TrustStoreFailureReason.PayloadUnprotect, error)
        }
    }
}

@Serializable
private data class StoredDeviceTrust(
    val authorizations: List<DeviceAuthorization> = emptyList(),
)

interface TrustStorePayloadProtector {
    fun protect(plainText: String): String

    fun canUnprotect(payload: String): Boolean

    fun unprotect(payload: String): String
}

object PlainTrustStorePayloadProtector : TrustStorePayloadProtector {
    override fun protect(plainText: String): String = plainText

    override fun canUnprotect(payload: String): Boolean = false

    override fun unprotect(payload: String): String = payload
}

class WindowsDpapiTrustStorePayloadProtector : TrustStorePayloadProtector {
    override fun protect(plainText: String): String {
        val protected = cryptProtect(plainText.encodeToByteArray())
        return "$DPAPI_PAYLOAD_PREFIX${Base64.getEncoder().encodeToString(protected)}"
    }

    override fun canUnprotect(payload: String): Boolean = payload.startsWith(DPAPI_PAYLOAD_PREFIX)

    override fun unprotect(payload: String): String {
        require(canUnprotect(payload)) { "Unsupported trust-store payload format" }
        val encrypted = Base64.getDecoder().decode(payload.removePrefix(DPAPI_PAYLOAD_PREFIX))
        return cryptUnprotect(encrypted).decodeToString()
    }

    private fun cryptProtect(data: ByteArray): ByteArray {
        val type = Class.forName("com.sun.jna.platform.win32.Crypt32Util")
        return type
            .getMethod("cryptProtectData", ByteArray::class.java)
            .invoke(null, data) as ByteArray
    }

    private fun cryptUnprotect(data: ByteArray): ByteArray {
        val type = Class.forName("com.sun.jna.platform.win32.Crypt32Util")
        return type
            .getMethod("cryptUnprotectData", ByteArray::class.java)
            .invoke(null, data) as ByteArray
    }
}

interface SecretToolCommandRunner {
    fun run(
        command: List<String>,
        stdin: String? = null,
    ): SecretToolCommandResult
}

data class SecretToolCommandResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
)

class ProcessSecretToolCommandRunner : SecretToolCommandRunner {
    override fun run(
        command: List<String>,
        stdin: String?,
    ): SecretToolCommandResult =
        try {
            val process = ProcessBuilder(command).start()
            if (stdin != null) {
                process.outputStream.use { output ->
                    output.write(stdin.toByteArray(StandardCharsets.UTF_8))
                }
            } else {
                process.outputStream.close()
            }
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            SecretToolCommandResult(process.waitFor(), stdout, stderr)
        } catch (error: Exception) {
            SecretToolCommandResult(127, stderr = error.message.orEmpty())
        }
}

object SecretToolLocator {
    fun isAvailable(
        path: String = System.getenv("PATH").orEmpty(),
        dbusSessionBusAddress: String? = System.getenv("DBUS_SESSION_BUS_ADDRESS"),
    ): Boolean {
        if (dbusSessionBusAddress.isNullOrBlank()) return false
        return path
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map(::File)
            .any { directory ->
                File(directory, "secret-tool").canExecute()
            }
    }
}

class LinuxSecretServiceTrustStorePayloadProtector(
    private val storeId: String,
    private val runner: SecretToolCommandRunner = ProcessSecretToolCommandRunner(),
) : TrustStorePayloadProtector {
    override fun protect(plainText: String): String {
        val result =
            runner.run(
                listOf(
                    "secret-tool",
                    "store",
                    "--label",
                    "Aegis desktop trust store",
                    "app",
                    "aegis",
                    "purpose",
                    "desktop-trust-store",
                    "store-id",
                    storeId,
                ),
                stdin = plainText,
            )
        if (result.exitCode != 0) {
            error("Linux Secret Service trust-store write failed: ${result.stderr.take(300)}")
        }
        return "$SECRET_SERVICE_PAYLOAD_PREFIX$storeId"
    }

    override fun canUnprotect(payload: String): Boolean = payload.startsWith(SECRET_SERVICE_PAYLOAD_PREFIX)

    override fun unprotect(payload: String): String {
        require(canUnprotect(payload)) { "Unsupported trust-store payload format" }
        val id = payload.removePrefix(SECRET_SERVICE_PAYLOAD_PREFIX)
        val result =
            runner.run(
                listOf(
                    "secret-tool",
                    "lookup",
                    "app",
                    "aegis",
                    "purpose",
                    "desktop-trust-store",
                    "store-id",
                    id,
                ),
            )
        if (result.exitCode != 0 || result.stdout.isEmpty()) {
            error("Linux Secret Service trust-store lookup failed: ${result.stderr.take(300)}")
        }
        return result.stdout.trimEnd('\r', '\n')
    }
}

private const val DPAPI_PAYLOAD_PREFIX = "aegis-dpapi:v1:"
private const val SECRET_SERVICE_PAYLOAD_PREFIX = "aegis-secret-service:v1:"

private fun defaultTrustStorePath(): Path {
    val home = System.getProperty("user.home") ?: "."
    return Path.of(home, ".aegis", "authorized-devices.json")
}

private fun defaultTrustStorePayloadProtector(
    file: Path,
    osName: String = System.getProperty("os.name").orEmpty(),
): TrustStorePayloadProtector {
    val normalizedOs = osName.lowercase()
    return when {
        "windows" in normalizedOs -> {
            WindowsDpapiTrustStorePayloadProtector()
        }

        "linux" in normalizedOs && SecretToolLocator.isAvailable() -> {
            LinuxSecretServiceTrustStorePayloadProtector(trustStoreId(file))
        }

        else -> {
            PlainTrustStorePayloadProtector
        }
    }
}

private fun trustStoreId(file: Path): String {
    val digest =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                file
                    .toAbsolutePath()
                    .normalize()
                    .toString()
                    .toByteArray(StandardCharsets.UTF_8),
            )
    return Base64
        .getUrlEncoder()
        .withoutPadding()
        .encodeToString(digest)
        .take(32)
}

private fun restrictPosixPermissions(
    path: Path,
    permissions: Set<PosixFilePermission>,
) {
    if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
        Files.setPosixFilePermissions(path, permissions)
    }
}

private fun validateTrustStoreAccess(
    path: Path,
    directory: Boolean,
) {
    val expectedPosixPermissions =
        if (directory) {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
    try {
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null &&
            Files.getPosixFilePermissions(path) != expectedPosixPermissions
        ) {
            throw SecurityException("Trust store POSIX permissions are not owner-only")
        }
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
        val owner = Files.getOwner(path)
        val acl = view.acl
        val onlyEntry = acl.singleOrNull()
        val aclIsOwnerOnly =
            listOf(
                acl.size == 1,
                onlyEntry?.type() == AclEntryType.ALLOW,
                onlyEntry?.principal() == owner,
                onlyEntry?.permissions() == enumValues<AclEntryPermission>().toSet(),
            ).all { it }
        if (!aclIsOwnerOnly) {
            throw SecurityException("Trust store ACL is not owner-only")
        }
    } catch (error: TrustStoreAccessException) {
        throw error
    } catch (error: Throwable) {
        throw TrustStoreAccessException(TrustStoreFailureReason.Acl, error)
    }
}

private fun restrictWindowsAcl(
    path: Path,
    directory: Boolean,
) {
    val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
    val owner = Files.getOwner(path)
    val permissions =
        if (directory) {
            directoryOwnerPermissions()
        } else {
            fileOwnerPermissions()
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

private fun fileOwnerPermissions(): Set<AclEntryPermission> = enumValues<AclEntryPermission>().toSet()

private fun directoryOwnerPermissions(): Set<AclEntryPermission> = enumValues<AclEntryPermission>().toSet()
