package dev.aegis.remote.desktop.agent

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DeviceIdentityCanonicalEncoding
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.IdentitySecurityLevel
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.security.CryptoCapabilityProbe
import dev.aegis.remote.core.security.CryptoCapabilityReport
import dev.aegis.remote.core.security.IdentityRotationPhase
import dev.aegis.remote.core.security.KeyRotationReason
import dev.aegis.remote.core.security.LocalDeviceIdentity
import dev.aegis.remote.core.security.PreparedDeviceIdentityRotation
import dev.aegis.remote.core.security.TransactionalDeviceIdentityStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Persists the desktop signing identity without ever writing PKCS#8 material
 * in plaintext. The public identity metadata is intentionally separate from
 * the platform-protected private key material.
 */
class DesktopDeviceIdentityStore(
    private val file: Path = defaultDesktopDeviceIdentityPath(),
    private val privateKeyProtector: DesktopPrivateKeyProtector = defaultDesktopPrivateKeyProtector(),
    private val json: Json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            prettyPrint = true
        },
    private val capabilityProbe: CryptoCapabilityProbe =
        DesktopCryptoCapabilityProbe(privateKeyProtector = privateKeyProtector),
) : TransactionalDeviceIdentityStore {
    private val lock = Any()

    override suspend fun capabilityReport(): CryptoCapabilityReport = capabilityProbe.evaluate()

    override suspend fun getOrCreate(): LocalDeviceIdentity =
        synchronized(lock) {
            withExclusiveStoreLock {
                val stored = readStoredIdentity()
                if (stored == null) {
                    createAndPersist(keyGeneration = INITIAL_KEY_GENERATION, previous = null)
                } else if (stored.pendingRotation?.phase() == IdentityRotationPhase.RemoteConfirmed) {
                    commitStoredRotation(stored, stored.pendingRotation.operationId)
                } else {
                    loadIdentity(stored)
                }
            }
        }

    override suspend fun rotate(reason: KeyRotationReason): LocalDeviceIdentity =
        throw DeviceIdentityStorageException(
            "Local-only identity rotation is disabled; use the relay identity lifecycle coordinator for $reason",
        )

    override suspend fun prepareRotation(reason: KeyRotationReason): PreparedDeviceIdentityRotation =
        synchronized(lock) {
            withExclusiveStoreLock {
                val current =
                    readStoredIdentity()
                        ?: throw DeviceIdentityStorageException("A desktop identity must exist before a $reason rotation")
                current.pendingRotation?.let { pending ->
                    return@withExclusiveStoreLock preparedRotation(current, pending)
                }
                loadIdentity(current)
                val nextGeneration =
                    try {
                        Math.addExact(current.keyGeneration, 1L)
                    } catch (error: ArithmeticException) {
                        throw DeviceIdentityStorageException("Desktop device key generation overflowed", error)
                    }
                val generated = generateStoredKeyMaterial(nextGeneration)
                val pending =
                    StoredPendingDesktopIdentityRotation(
                        operationId = UUID.randomUUID().toString(),
                        reason = reason.name,
                        phase = IdentityRotationPhase.Prepared.name,
                        deviceId = generated.material.deviceId,
                        signingPublicKey = generated.material.signingPublicKey,
                        keyAlgorithm = generated.material.keyAlgorithm,
                        keyGeneration = generated.material.keyGeneration,
                        privateKeyProtection = generated.material.privateKeyProtection,
                        protectedPrivateKey = generated.material.protectedPrivateKey,
                    )
                runCatching {
                    writeStoredIdentity(current.copy(formatVersion = FORMAT_VERSION, pendingRotation = pending))
                }.onFailure {
                    discardStoredKeyMaterialBestEffort(generated.material)
                }.getOrThrow()
                preparedRotation(current.copy(pendingRotation = pending), pending)
            }
        }

    override suspend fun pendingRotation(): PreparedDeviceIdentityRotation? =
        synchronized(lock) {
            withExclusiveStoreLock {
                val stored = readStoredIdentity() ?: return@withExclusiveStoreLock null
                stored.pendingRotation?.let { pending -> preparedRotation(stored, pending) }
            }
        }

    override suspend fun markRotationRemoteConfirmed(operationId: String): PreparedDeviceIdentityRotation =
        synchronized(lock) {
            withExclusiveStoreLock {
                val stored = readStoredIdentity() ?: throw DeviceIdentityStorageException("The active identity is missing")
                val pending = stored.requirePendingOperation(operationId)
                if (pending.phase() == IdentityRotationPhase.RemoteConfirmed) {
                    return@withExclusiveStoreLock preparedRotation(stored, pending)
                }
                val confirmed = pending.copy(phase = IdentityRotationPhase.RemoteConfirmed.name)
                val updated = stored.copy(formatVersion = FORMAT_VERSION, pendingRotation = confirmed)
                writeStoredIdentity(updated)
                preparedRotation(updated, confirmed)
            }
        }

    override suspend fun commitRotation(operationId: String): LocalDeviceIdentity =
        synchronized(lock) {
            withExclusiveStoreLock {
                val stored = readStoredIdentity() ?: throw DeviceIdentityStorageException("The active identity is missing")
                commitStoredRotation(stored, operationId)
            }
        }

    override suspend fun rollbackRotation(operationId: String) {
        synchronized(lock) {
            withExclusiveStoreLock {
                val stored = readStoredIdentity() ?: throw DeviceIdentityStorageException("The active identity is missing")
                val pending = stored.requirePendingOperation(operationId)
                check(pending.phase() == IdentityRotationPhase.Prepared) { "CONFIRMED_IDENTITY_ROTATION_CANNOT_ROLL_BACK" }
                writeStoredIdentity(stored.copy(formatVersion = FORMAT_VERSION, pendingRotation = null))
                discardStoredKeyMaterialBestEffort(pending.keyMaterial())
            }
        }
    }

    /**
     * Returns either the active identity or a retained generation so relay key
     * rotation can sign its proof with the previous private key. This is kept
     * internal until the relay-v2 rotation coordinator consumes it.
     */
    internal suspend fun identityForKeyGeneration(keyGeneration: Long): LocalDeviceIdentity? =
        synchronized(lock) {
            withExclusiveStoreLock {
                val stored = readStoredIdentity() ?: return@withExclusiveStoreLock null
                when {
                    stored.keyGeneration == keyGeneration -> {
                        loadIdentity(stored.keyMaterial())
                    }

                    else -> {
                        stored.pendingRotation
                            ?.takeIf { pending -> pending.keyGeneration == keyGeneration }
                            ?.let { pending -> loadIdentity(pending.keyMaterial()) }
                            ?: stored.retiredIdentities
                                .firstOrNull { identity -> identity.keyGeneration == keyGeneration }
                                ?.let { identity -> loadIdentity(identity.keyMaterial()) }
                    }
                }
            }
        }

    private fun createAndPersist(
        keyGeneration: Long,
        previous: StoredDesktopDeviceIdentity?,
    ): LocalDeviceIdentity {
        if (keyGeneration < INITIAL_KEY_GENERATION) {
            throw DeviceIdentityStorageException("Desktop device key generation is invalid")
        }
        var privateKeyPkcs8: ByteArray? = null
        var protectedPrivateKey: ByteArray? = null
        var persisted = false
        try {
            val keyPair =
                try {
                    KeyPairGenerator.getInstance(EC).run {
                        initialize(ECGenParameterSpec(P256_CURVE))
                        generateKeyPair()
                    }
                } catch (error: Exception) {
                    throw DeviceIdentityStorageException("Unable to generate the desktop P-256 identity", error)
                }
            val encodedPublicKey =
                keyPair.public.encoded
                    ?: throw DeviceIdentityStorageException("The generated desktop public key has no X.509 encoding")
            requireP256PublicKey(keyPair.public)
            val publicKey = encodedPublicKey.copyOf()
            privateKeyPkcs8 = keyPair.private.encoded
                ?: throw DeviceIdentityStorageException("The generated desktop private key has no PKCS#8 encoding")

            verifyKeyPair(keyPair.private, keyPair.public, IdentitySignatureAlgorithm.ECDSA_P256_SHA256)
            protectedPrivateKey = privateKeyProtector.protect(privateKeyPkcs8)
            if (protectedPrivateKey.isEmpty()) {
                throw DeviceIdentityStorageException("The platform key protector returned an empty value")
            }

            val deviceId = deriveDeviceId(IdentitySignatureAlgorithm.ECDSA_P256_SHA256, publicKey)
            val stored =
                StoredDesktopDeviceIdentity(
                    formatVersion = FORMAT_VERSION,
                    deviceId = deviceId.value,
                    signingPublicKey = encodeBase64Url(publicKey),
                    keyAlgorithm = IdentitySignatureAlgorithm.ECDSA_P256_SHA256.wireId,
                    keyGeneration = keyGeneration,
                    privateKeyProtection = privateKeyProtector.scheme,
                    protectedPrivateKey = encodeBase64Url(protectedPrivateKey),
                    retiredIdentities =
                        previous
                            ?.let { identity ->
                                identity.retiredIdentities + identity.toRetiredIdentity()
                            }.orEmpty(),
                )
            writeStoredIdentity(stored)
            persisted = true

            return JcaDesktopLocalDeviceIdentity(
                deviceId = deviceId,
                publicKeySpki = publicKey,
                algorithm = IdentitySignatureAlgorithm.ECDSA_P256_SHA256,
                keyGeneration = keyGeneration,
                privateKey = keyPair.private,
            )
        } catch (error: DeviceIdentityStorageException) {
            if (!persisted) {
                protectedPrivateKey?.let { value -> runCatching { privateKeyProtector.discard(value) } }
            }
            throw error
        } catch (error: Exception) {
            if (!persisted) {
                protectedPrivateKey?.let { value -> runCatching { privateKeyProtector.discard(value) } }
            }
            throw DeviceIdentityStorageException("Unable to persist the desktop device identity", error)
        } finally {
            privateKeyPkcs8?.fill(0)
            protectedPrivateKey?.fill(0)
        }
    }

    @Suppress("ThrowsCount")
    private fun generateStoredKeyMaterial(keyGeneration: Long): GeneratedStoredIdentityKeyMaterial {
        if (keyGeneration < INITIAL_KEY_GENERATION) {
            throw DeviceIdentityStorageException("Desktop device key generation is invalid")
        }
        var privateKeyPkcs8: ByteArray? = null
        var protectedPrivateKey: ByteArray? = null
        var retainedBySecureStore = false
        try {
            val keyPair =
                try {
                    KeyPairGenerator.getInstance(EC).run {
                        initialize(ECGenParameterSpec(P256_CURVE))
                        generateKeyPair()
                    }
                } catch (error: Exception) {
                    throw DeviceIdentityStorageException("Unable to generate the desktop P-256 identity", error)
                }
            val publicKey =
                keyPair.public.encoded?.copyOf()
                    ?: throw DeviceIdentityStorageException("The generated desktop public key has no X.509 encoding")
            requireP256PublicKey(keyPair.public)
            privateKeyPkcs8 =
                keyPair.private.encoded
                    ?: throw DeviceIdentityStorageException("The generated desktop private key has no PKCS#8 encoding")
            verifyKeyPair(keyPair.private, keyPair.public, IdentitySignatureAlgorithm.ECDSA_P256_SHA256)
            protectedPrivateKey = privateKeyProtector.protect(privateKeyPkcs8)
            if (protectedPrivateKey.isEmpty()) {
                throw DeviceIdentityStorageException("The platform key protector returned an empty value")
            }
            val material =
                StoredIdentityKeyMaterial(
                    deviceId = deriveDeviceId(IdentitySignatureAlgorithm.ECDSA_P256_SHA256, publicKey).value,
                    signingPublicKey = encodeBase64Url(publicKey),
                    keyAlgorithm = IdentitySignatureAlgorithm.ECDSA_P256_SHA256.wireId,
                    keyGeneration = keyGeneration,
                    privateKeyProtection = privateKeyProtector.scheme,
                    protectedPrivateKey = encodeBase64Url(protectedPrivateKey),
                )
            retainedBySecureStore = true
            return GeneratedStoredIdentityKeyMaterial(material)
        } catch (error: DeviceIdentityStorageException) {
            throw error
        } catch (error: Exception) {
            throw DeviceIdentityStorageException("Unable to prepare the desktop device identity", error)
        } finally {
            if (!retainedBySecureStore) {
                protectedPrivateKey?.let { value -> runCatching { privateKeyProtector.discard(value) } }
            }
            privateKeyPkcs8?.fill(0)
            protectedPrivateKey?.fill(0)
        }
    }

    private fun preparedRotation(
        stored: StoredDesktopDeviceIdentity,
        pending: StoredPendingDesktopIdentityRotation,
    ): PreparedDeviceIdentityRotation {
        require(pending.keyGeneration == stored.keyGeneration + 1L) { "IDENTITY_ROTATION_GENERATION_MISMATCH" }
        return PreparedDeviceIdentityRotation(
            operationId = pending.operationId,
            reason = pending.reason(),
            phase = pending.phase(),
            currentIdentity = loadIdentity(stored),
            replacementIdentity = loadIdentity(pending.keyMaterial()),
        )
    }

    private fun commitStoredRotation(
        stored: StoredDesktopDeviceIdentity,
        operationId: String,
    ): LocalDeviceIdentity {
        val pending = stored.requirePendingOperation(operationId)
        check(pending.phase() == IdentityRotationPhase.RemoteConfirmed) { "IDENTITY_ROTATION_NOT_REMOTE_CONFIRMED" }
        val replacement = pending.keyMaterial()
        validateStoredKeyMaterial(replacement)
        val updated =
            StoredDesktopDeviceIdentity(
                formatVersion = FORMAT_VERSION,
                deviceId = replacement.deviceId,
                signingPublicKey = replacement.signingPublicKey,
                keyAlgorithm = replacement.keyAlgorithm,
                keyGeneration = replacement.keyGeneration,
                privateKeyProtection = replacement.privateKeyProtection,
                protectedPrivateKey = replacement.protectedPrivateKey,
                retiredIdentities = stored.retiredIdentities + stored.toRetiredIdentity(),
                pendingRotation = null,
            )
        writeStoredIdentity(updated)
        return loadIdentity(updated)
    }

    private fun discardStoredKeyMaterialBestEffort(material: StoredIdentityKeyMaterial) {
        val protectedPrivateKey =
            runCatching { decodeBase64Url(material.protectedPrivateKey, "protected private key") }.getOrNull() ?: return
        try {
            runCatching { privateKeyProtector.discard(protectedPrivateKey) }
        } finally {
            protectedPrivateKey.fill(0)
        }
    }

    private fun loadIdentity(stored: StoredDesktopDeviceIdentity): LocalDeviceIdentity = loadIdentity(stored.keyMaterial())

    private fun loadIdentity(stored: StoredIdentityKeyMaterial): LocalDeviceIdentity {
        validateStoredKeyMaterial(stored)
        val storedPublicKey = decodeBase64Url(stored.signingPublicKey, "stored signing public key")
        val protectedPrivateKey = decodeBase64Url(stored.protectedPrivateKey, "protected private key")
        var privateKeyPkcs8: ByteArray? = null
        try {
            val algorithm = stored.identityAlgorithm()
            val publicKeySpki = storedPublicKey.toSubjectPublicKeyInfo(algorithm)
            val publicKey = decodePublicKey(publicKeySpki, algorithm)
            privateKeyPkcs8 = privateKeyProtector.unprotect(protectedPrivateKey)
            val privateKey = decodePrivateKey(privateKeyPkcs8, algorithm)
            verifyKeyPair(privateKey, publicKey, algorithm)
            return JcaDesktopLocalDeviceIdentity(
                deviceId = DeviceId(stored.deviceId),
                publicKeySpki = publicKeySpki,
                algorithm = algorithm,
                keyGeneration = stored.keyGeneration,
                privateKey = privateKey,
            )
        } catch (error: DeviceIdentityStorageException) {
            throw error
        } catch (error: Exception) {
            throw DeviceIdentityStorageException("The stored desktop device identity cannot be opened", error)
        } finally {
            storedPublicKey.fill(0)
            protectedPrivateKey.fill(0)
            privateKeyPkcs8?.fill(0)
        }
    }

    private fun readStoredIdentity(): StoredDesktopDeviceIdentity? {
        val interruptedWrite = !Files.notExists(pendingFile())
        val identityWasInitialized = !Files.notExists(initializedFile())
        if (Files.notExists(file)) {
            if (interruptedWrite || identityWasInitialized) {
                throw DeviceIdentityStorageException(
                    "Desktop device identity metadata is missing after initialization; refusing to create a replacement identity",
                )
            }
            return null
        }
        if (!Files.isRegularFile(file)) {
            throw DeviceIdentityStorageException("Desktop device identity metadata is not a regular file")
        }
        return try {
            json.decodeFromString<StoredDesktopDeviceIdentity>(Files.readString(file)).also(::validateStoredMetadata)
        } catch (error: DeviceIdentityStorageException) {
            throw error
        } catch (error: Exception) {
            throw DeviceIdentityStorageException("Desktop device identity metadata is invalid", error)
        }.also {
            writeInitializationMarker(file.parent ?: Path.of("."))
            // A valid current or prior metadata record is sufficient to recover
            // safely from a crash before a pending marker was removed.
            if (interruptedWrite) {
                runCatching { Files.deleteIfExists(pendingFile()) }
            }
        }
    }

    @Suppress("ThrowsCount")
    private fun validateStoredMetadata(stored: StoredDesktopDeviceIdentity) {
        if (stored.formatVersion !in 1..FORMAT_VERSION) {
            throw DeviceIdentityStorageException("Unsupported desktop device identity format")
        }
        validateStoredKeyMaterial(stored.keyMaterial())
        val allGenerations = mutableSetOf(stored.keyGeneration)
        stored.retiredIdentities.forEach { retired ->
            val material = retired.keyMaterial()
            validateStoredKeyMaterial(material)
            if (retired.keyGeneration >= stored.keyGeneration || !allGenerations.add(retired.keyGeneration)) {
                throw DeviceIdentityStorageException("Retired desktop device identity generations are invalid")
            }
        }
        stored.pendingRotation?.let { pending ->
            if (pending.operationId.isBlank()) {
                throw DeviceIdentityStorageException("Prepared desktop identity rotation has no operation ID")
            }
            pending.reason()
            pending.phase()
            val material = pending.keyMaterial()
            validateStoredKeyMaterial(material)
            if (pending.keyGeneration != stored.keyGeneration + 1L || !allGenerations.add(pending.keyGeneration)) {
                throw DeviceIdentityStorageException("Prepared desktop identity generation is invalid")
            }
        }
    }

    private fun validateStoredKeyMaterial(stored: StoredIdentityKeyMaterial) {
        val algorithm = stored.identityAlgorithm()
        if (stored.keyGeneration < INITIAL_KEY_GENERATION) {
            throw DeviceIdentityStorageException("Desktop device key generation is invalid")
        }
        if (stored.privateKeyProtection != privateKeyProtector.scheme) {
            throw DeviceIdentityStorageException("Desktop device identity is protected by a different secure-store backend")
        }

        val publicKeyBytes = decodeBase64Url(stored.signingPublicKey, "stored signing public key")
        try {
            val publicKeySpki = publicKeyBytes.toSubjectPublicKeyInfo(algorithm)
            decodePublicKey(publicKeySpki, algorithm)
            val currentId = deriveDeviceId(algorithm, publicKeySpki).value
            val legacyId = if (algorithm == IdentitySignatureAlgorithm.ED25519) deriveLegacyDeviceId(publicKeyBytes).value else null
            if (currentId != stored.deviceId && legacyId != stored.deviceId) {
                throw DeviceIdentityStorageException("Stored desktop device ID does not match its public key")
            }
        } finally {
            publicKeyBytes.fill(0)
        }

        val protectedPrivateKey = decodeBase64Url(stored.protectedPrivateKey, "protected private key")
        try {
            if (protectedPrivateKey.isEmpty()) {
                throw DeviceIdentityStorageException("Stored protected private key is empty")
            }
        } finally {
            protectedPrivateKey.fill(0)
        }
    }

    private fun writeStoredIdentity(stored: StoredDesktopDeviceIdentity) {
        val directory = file.parent ?: Path.of(".")
        try {
            Files.createDirectories(directory)
            restrictPosixPermissions(
                directory,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            writeInitializationMarker(directory)
            writePendingMarker(directory)

            val prefix = file.fileName.toString().padEnd(3, '_') + "."
            val temporary = Files.createTempFile(directory, prefix, ".tmp")
            try {
                Files.writeString(temporary, json.encodeToString(StoredDesktopDeviceIdentity.serializer(), stored))
                forceFile(temporary)
                restrictPosixPermissions(
                    temporary,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                restrictPosixPermissions(
                    file,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
                // The atomic rename is the commit point. Failures after it must
                // retain the new protected key rather than invalidating metadata
                // that may already be visible to another process.
                runCatching { forceFile(file) }
                forceDirectoryBestEffort(directory)
                runCatching { Files.deleteIfExists(pendingFile()) }
                forceDirectoryBestEffort(directory)
            } finally {
                runCatching { Files.deleteIfExists(temporary) }
            }
        } catch (error: DeviceIdentityStorageException) {
            throw error
        } catch (error: Exception) {
            throw DeviceIdentityStorageException("Unable to write desktop device identity metadata", error)
        }
    }

    private fun <T> withExclusiveStoreLock(block: () -> T): T {
        val directory = file.parent ?: Path.of(".")
        val normalizedFile = file.toAbsolutePath().normalize()
        return synchronized(DesktopIdentityJvmLockCoordinator.lockFor(normalizedFile)) {
            try {
                Files.createDirectories(directory)
                restrictPosixPermissions(
                    directory,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                )
                val lockFile = lockFile()
                FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    channel.lock().use {
                        block()
                    }
                }
            } catch (error: DeviceIdentityStorageException) {
                throw error
            } catch (error: Exception) {
                throw DeviceIdentityStorageException("Unable to acquire the desktop identity storage lock", error)
            }
        }
    }

    private fun writePendingMarker(directory: Path) {
        Files.writeString(
            pendingFile(),
            IDENTITY_WRITE_PENDING_MARKER,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        forceFile(pendingFile())
        forceDirectoryBestEffort(directory)
    }

    private fun writeInitializationMarker(directory: Path) {
        if (!Files.notExists(initializedFile())) return
        Files.writeString(
            initializedFile(),
            IDENTITY_INITIALIZED_MARKER,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        forceFile(initializedFile())
        forceDirectoryBestEffort(directory)
    }

    private fun lockFile(): Path = file.resolveSibling("${file.fileName}.lock")

    private fun pendingFile(): Path = file.resolveSibling("${file.fileName}.pending")

    private fun initializedFile(): Path = file.resolveSibling("${file.fileName}.initialized")
}

/**
 * A platform-backed protector for serialized PKCS#8 private key material.
 * Implementations must never return a plaintext representation suitable for
 * persistence.
 */
interface DesktopPrivateKeyProtector {
    val scheme: String

    fun protect(pkcs8: ByteArray): ByteArray

    fun unprotect(protectedValue: ByteArray): ByteArray

    fun discard(protectedValue: ByteArray) = Unit
}

/** Uses user-scoped Windows DPAPI with all UI prompts forbidden. */
class WindowsDpapiPrivateKeyProtector : DesktopPrivateKeyProtector {
    override val scheme: String = WINDOWS_DPAPI_SCHEME

    override fun protect(pkcs8: ByteArray): ByteArray =
        crypt("protect") {
            Crypt32Util.cryptProtectData(pkcs8, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)
        }

    override fun unprotect(protectedValue: ByteArray): ByteArray =
        crypt("unprotect") {
            Crypt32Util.cryptUnprotectData(protectedValue, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN)
        }

    private fun crypt(
        operation: String,
        block: () -> ByteArray,
    ): ByteArray =
        try {
            block().also { value ->
                if (value.isEmpty()) {
                    throw DeviceIdentityStorageException("Windows DPAPI returned an empty value while attempting to $operation")
                }
            }
        } catch (error: DeviceIdentityStorageException) {
            throw error
        } catch (error: Exception) {
            throw DeviceIdentityStorageException("Windows DPAPI failed to $operation the desktop private key", error)
        }
}

/** Minimal process boundary for Secret Service integration and deterministic tests. */
interface SecretServiceCommandRunner {
    fun run(
        command: List<String>,
        stdin: ByteArray? = null,
    ): SecretServiceCommandResult
}

data class SecretServiceCommandResult(
    val exitCode: Int,
    val stdout: ByteArray = byteArrayOf(),
    val stderr: String = "",
)

class ProcessSecretServiceCommandRunner : SecretServiceCommandRunner {
    override fun run(
        command: List<String>,
        stdin: ByteArray?,
    ): SecretServiceCommandResult =
        try {
            val process = ProcessBuilder(command).start()
            process.outputStream.use { output ->
                stdin?.let(output::write)
            }
            if (!process.waitFor(SECRET_SERVICE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                SecretServiceCommandResult(exitCode = SECRET_SERVICE_TIMEOUT_EXIT_CODE, stderr = "Secret Service command timed out")
            } else {
                SecretServiceCommandResult(
                    exitCode = process.exitValue(),
                    stdout = process.inputStream.readBytes(),
                    stderr = process.errorStream.bufferedReader(StandardCharsets.UTF_8).readText(),
                )
            }
        } catch (error: Exception) {
            SecretServiceCommandResult(exitCode = SECRET_SERVICE_COMMAND_ERROR_EXIT_CODE, stderr = error.javaClass.simpleName)
        }
}

object LinuxSecretServiceAvailability {
    fun isAvailable(
        path: String = System.getenv("PATH").orEmpty(),
        dbusSessionBusAddress: String? = System.getenv("DBUS_SESSION_BUS_ADDRESS"),
    ): Boolean {
        if (dbusSessionBusAddress.isNullOrBlank()) return false
        return path
            .split(File.pathSeparator)
            .asSequence()
            .filter(String::isNotBlank)
            .map(::File)
            .any { directory -> File(directory, SECRET_TOOL_COMMAND).canExecute() }
    }
}

/**
 * Stores private PKCS#8 material in the freedesktop Secret Service via
 * secret-tool. The value returned for persistence is only an opaque UUID
 * reference; the identity metadata never contains the private key.
 */
class LinuxSecretServicePrivateKeyProtector(
    private val runner: SecretServiceCommandRunner = ProcessSecretServiceCommandRunner(),
    private val isAvailable: () -> Boolean = LinuxSecretServiceAvailability::isAvailable,
    private val referenceFactory: () -> String = { UUID.randomUUID().toString() },
) : DesktopPrivateKeyProtector {
    override val scheme: String = LINUX_SECRET_SERVICE_SCHEME

    override fun protect(pkcs8: ByteArray): ByteArray {
        ensureAvailable()
        if (pkcs8.isEmpty()) {
            throw DeviceIdentityStorageException("Refusing to store an empty desktop private key")
        }
        val reference = validateReference(referenceFactory())
        val encodedPrivateKey = Base64.getUrlEncoder().withoutPadding().encode(pkcs8)
        try {
            val result = runner.run(storeCommand(reference), encodedPrivateKey)
            if (result.exitCode != 0) {
                throw DeviceIdentityStorageException("Linux Secret Service refused the desktop private key")
            }
            return "$SECRET_SERVICE_REFERENCE_PREFIX$reference".toByteArray(StandardCharsets.US_ASCII)
        } finally {
            encodedPrivateKey.fill(0)
        }
    }

    override fun unprotect(protectedValue: ByteArray): ByteArray {
        ensureAvailable()
        val reference = referenceFrom(protectedValue)
        val result = runner.run(lookupCommand(reference))
        if (result.exitCode != 0 || result.stdout.isEmpty()) {
            result.stdout.fill(0)
            throw DeviceIdentityStorageException("Linux Secret Service could not load the desktop private key")
        }
        val encodedPrivateKey = withoutTrailingLineEndings(result.stdout)
        result.stdout.fill(0)
        try {
            return Base64.getUrlDecoder().decode(encodedPrivateKey)
        } catch (error: IllegalArgumentException) {
            throw DeviceIdentityStorageException("Linux Secret Service returned an invalid desktop private key", error)
        } finally {
            encodedPrivateKey.fill(0)
        }
    }

    override fun discard(protectedValue: ByteArray) {
        val reference = referenceFrom(protectedValue)
        val result = runner.run(clearCommand(reference))
        if (result.exitCode != 0) {
            throw DeviceIdentityStorageException("Linux Secret Service could not remove an old desktop private key")
        }
    }

    private fun ensureAvailable() {
        if (!isAvailable()) {
            throw DeviceIdentityStorageException(
                "Linux Secret Service is unavailable; refusing to persist the desktop private key in plaintext",
            )
        }
    }

    private fun referenceFrom(protectedValue: ByteArray): String {
        val marker = protectedValue.toString(StandardCharsets.US_ASCII)
        if (!marker.startsWith(SECRET_SERVICE_REFERENCE_PREFIX)) {
            throw DeviceIdentityStorageException("Invalid Linux Secret Service private-key reference")
        }
        return validateReference(marker.removePrefix(SECRET_SERVICE_REFERENCE_PREFIX))
    }

    private fun validateReference(reference: String): String =
        try {
            val parsed = UUID.fromString(reference)
            if (parsed.toString() != reference.lowercase(Locale.ROOT)) {
                throw IllegalArgumentException("Secret Service reference is not canonical")
            }
            reference
        } catch (error: IllegalArgumentException) {
            throw DeviceIdentityStorageException("Invalid Linux Secret Service private-key reference", error)
        }

    private fun storeCommand(reference: String): List<String> =
        listOf(
            SECRET_TOOL_COMMAND,
            "store",
            "--label",
            "Aegis desktop device identity",
            "application",
            "aegis",
            "purpose",
            "desktop-device-identity",
            "reference",
            reference,
        )

    private fun lookupCommand(reference: String): List<String> =
        listOf(
            SECRET_TOOL_COMMAND,
            "lookup",
            "application",
            "aegis",
            "purpose",
            "desktop-device-identity",
            "reference",
            reference,
        )

    private fun clearCommand(reference: String): List<String> =
        listOf(
            SECRET_TOOL_COMMAND,
            "clear",
            "application",
            "aegis",
            "purpose",
            "desktop-device-identity",
            "reference",
            reference,
        )
}

private class UnavailableDesktopPrivateKeyProtector(
    private val message: String,
) : DesktopPrivateKeyProtector {
    override val scheme: String = "unavailable"

    override fun protect(pkcs8: ByteArray): ByteArray = throw DeviceIdentityStorageException(message)

    override fun unprotect(protectedValue: ByteArray): ByteArray = throw DeviceIdentityStorageException(message)
}

internal fun defaultDesktopPrivateKeyProtector(
    osName: String = System.getProperty("os.name").orEmpty(),
    linuxSecretServiceAvailable: () -> Boolean = LinuxSecretServiceAvailability::isAvailable,
): DesktopPrivateKeyProtector =
    when {
        osName.lowercase(Locale.ROOT).contains("windows") -> {
            WindowsDpapiPrivateKeyProtector()
        }

        osName.lowercase(Locale.ROOT).contains("linux") && linuxSecretServiceAvailable() -> {
            LinuxSecretServicePrivateKeyProtector(isAvailable = linuxSecretServiceAvailable)
        }

        osName.lowercase(Locale.ROOT).contains("linux") -> {
            UnavailableDesktopPrivateKeyProtector(
                "Linux Secret Service is unavailable; refusing to persist the desktop private key in plaintext",
            )
        }

        else -> {
            UnavailableDesktopPrivateKeyProtector(
                "No supported secure private-key store is available for this desktop platform",
            )
        }
    }

class DeviceIdentityStorageException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private class JcaDesktopLocalDeviceIdentity(
    @Suppress("unused") private val deviceId: DeviceId,
    publicKeySpki: ByteArray,
    private val algorithm: IdentitySignatureAlgorithm,
    private val keyGeneration: Long,
    private val privateKey: PrivateKey,
) : LocalDeviceIdentity {
    private val immutablePublicKeySpki = publicKeySpki.copyOf()

    override val publicIdentity: DevicePublicIdentity
        get() {
            val digest = MessageDigest.getInstance("SHA-256").digest(immutablePublicKeySpki)
            return DevicePublicIdentity(
                deviceId = deriveDeviceId(algorithm, immutablePublicKeySpki),
                algorithm = algorithm,
                publicKeySpki = immutablePublicKeySpki.copyOf(),
                fingerprint = "SHA256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest),
                keyGeneration = keyGeneration,
                securityLevel = IdentitySecurityLevel.WRAPPED_SOFTWARE,
            )
        }

    override suspend fun sign(payload: ByteArray): ByteArray =
        try {
            Signature.getInstance(algorithm.signatureName()).run {
                initSign(privateKey)
                update(payload)
                sign()
            }
        } catch (error: Exception) {
            throw DeviceIdentityStorageException("Unable to sign with the desktop device identity", error)
        }
}

@Serializable
private data class StoredDesktopDeviceIdentity(
    val formatVersion: Int,
    val deviceId: String,
    val signingPublicKey: String,
    val keyAlgorithm: String,
    val keyGeneration: Long,
    val privateKeyProtection: String,
    val protectedPrivateKey: String,
    val retiredIdentities: List<StoredRetiredDesktopDeviceIdentity> = emptyList(),
    val pendingRotation: StoredPendingDesktopIdentityRotation? = null,
)

@Serializable
private data class StoredRetiredDesktopDeviceIdentity(
    val deviceId: String,
    val signingPublicKey: String,
    val keyAlgorithm: String,
    val keyGeneration: Long,
    val privateKeyProtection: String,
    val protectedPrivateKey: String,
)

@Serializable
private data class StoredPendingDesktopIdentityRotation(
    val operationId: String,
    val reason: String,
    val phase: String,
    val deviceId: String,
    val signingPublicKey: String,
    val keyAlgorithm: String,
    val keyGeneration: Long,
    val privateKeyProtection: String,
    val protectedPrivateKey: String,
)

private data class StoredIdentityKeyMaterial(
    val deviceId: String,
    val signingPublicKey: String,
    val keyAlgorithm: String,
    val keyGeneration: Long,
    val privateKeyProtection: String,
    val protectedPrivateKey: String,
)

private data class GeneratedStoredIdentityKeyMaterial(
    val material: StoredIdentityKeyMaterial,
)

private fun StoredDesktopDeviceIdentity.keyMaterial(): StoredIdentityKeyMaterial =
    StoredIdentityKeyMaterial(
        deviceId = deviceId,
        signingPublicKey = signingPublicKey,
        keyAlgorithm = keyAlgorithm,
        keyGeneration = keyGeneration,
        privateKeyProtection = privateKeyProtection,
        protectedPrivateKey = protectedPrivateKey,
    )

private fun StoredRetiredDesktopDeviceIdentity.keyMaterial(): StoredIdentityKeyMaterial =
    StoredIdentityKeyMaterial(
        deviceId = deviceId,
        signingPublicKey = signingPublicKey,
        keyAlgorithm = keyAlgorithm,
        keyGeneration = keyGeneration,
        privateKeyProtection = privateKeyProtection,
        protectedPrivateKey = protectedPrivateKey,
    )

private fun StoredPendingDesktopIdentityRotation.keyMaterial(): StoredIdentityKeyMaterial =
    StoredIdentityKeyMaterial(
        deviceId = deviceId,
        signingPublicKey = signingPublicKey,
        keyAlgorithm = keyAlgorithm,
        keyGeneration = keyGeneration,
        privateKeyProtection = privateKeyProtection,
        protectedPrivateKey = protectedPrivateKey,
    )

private fun StoredPendingDesktopIdentityRotation.phase(): IdentityRotationPhase =
    try {
        IdentityRotationPhase.valueOf(phase)
    } catch (error: IllegalArgumentException) {
        throw DeviceIdentityStorageException("Prepared desktop identity rotation phase is invalid", error)
    }

private fun StoredPendingDesktopIdentityRotation.reason(): KeyRotationReason =
    try {
        KeyRotationReason.valueOf(reason)
    } catch (error: IllegalArgumentException) {
        throw DeviceIdentityStorageException("Prepared desktop identity rotation reason is invalid", error)
    }

private fun StoredDesktopDeviceIdentity.requirePendingOperation(operationId: String): StoredPendingDesktopIdentityRotation {
    require(operationId.isNotBlank()) { "IDENTITY_ROTATION_OPERATION_ID_REQUIRED" }
    val pending = pendingRotation ?: error("NO_PREPARED_IDENTITY_ROTATION")
    require(pending.operationId == operationId) { "IDENTITY_ROTATION_OPERATION_MISMATCH" }
    return pending
}

private fun StoredIdentityKeyMaterial.identityAlgorithm(): IdentitySignatureAlgorithm =
    when (keyAlgorithm.uppercase(Locale.ROOT)) {
        "ED25519", "EDDSA" -> IdentitySignatureAlgorithm.ED25519
        "ECDSA_P256_SHA256", "EC" -> IdentitySignatureAlgorithm.ECDSA_P256_SHA256
        else -> throw DeviceIdentityStorageException("Unsupported desktop device key algorithm")
    }

private fun ByteArray.toSubjectPublicKeyInfo(algorithm: IdentitySignatureAlgorithm): ByteArray =
    when (algorithm) {
        IdentitySignatureAlgorithm.ED25519 -> subjectPublicKeyInfoForRawEd25519PublicKey(this)
        IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> copyOf()
    }

private fun StoredDesktopDeviceIdentity.toRetiredIdentity(): StoredRetiredDesktopDeviceIdentity =
    StoredRetiredDesktopDeviceIdentity(
        deviceId = deviceId,
        signingPublicKey = signingPublicKey,
        keyAlgorithm = keyAlgorithm,
        keyGeneration = keyGeneration,
        privateKeyProtection = privateKeyProtection,
        protectedPrivateKey = protectedPrivateKey,
    )

private fun defaultDesktopDeviceIdentityPath(): Path {
    val home = System.getProperty("user.home") ?: "."
    return Path.of(home, ".aegis", "desktop-device-identity.json")
}

private fun deriveDeviceId(
    algorithm: IdentitySignatureAlgorithm,
    publicKeySpki: ByteArray,
): DeviceId {
    val digest =
        MessageDigest.getInstance("SHA-256").digest(
            DeviceIdentityCanonicalEncoding.deviceIdPreimage(algorithm, publicKeySpki),
        )
    return try {
        DeviceId(Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
    } finally {
        digest.fill(0)
    }
}

private fun deriveLegacyDeviceId(signingPublicKey: ByteArray): DeviceId {
    val digest = MessageDigest.getInstance("SHA-256").digest(signingPublicKey)
    return try {
        DeviceId(Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
    } finally {
        digest.fill(0)
    }
}

private fun decodePublicKey(
    publicKeySpki: ByteArray,
    algorithm: IdentitySignatureAlgorithm,
): PublicKey =
    try {
        KeyFactory.getInstance(algorithm.keyFactoryName()).generatePublic(X509EncodedKeySpec(publicKeySpki)).also { key ->
            if (algorithm == IdentitySignatureAlgorithm.ECDSA_P256_SHA256) requireP256PublicKey(key)
        }
    } catch (error: Exception) {
        throw DeviceIdentityStorageException("Stored desktop signing public key is not canonical ${algorithm.wireId} data", error)
    }

private fun decodePrivateKey(
    encoded: ByteArray,
    algorithm: IdentitySignatureAlgorithm,
): PrivateKey =
    try {
        KeyFactory.getInstance(algorithm.keyFactoryName()).generatePrivate(PKCS8EncodedKeySpec(encoded))
    } catch (error: Exception) {
        throw DeviceIdentityStorageException("Stored desktop private key is not ${algorithm.wireId} PKCS#8 data", error)
    }

private fun subjectPublicKeyInfoForRawEd25519PublicKey(rawPublicKey: ByteArray): ByteArray {
    if (rawPublicKey.size != ED25519_PUBLIC_KEY_BYTES) {
        throw DeviceIdentityStorageException("Desktop Ed25519 public key must be exactly $ED25519_PUBLIC_KEY_BYTES bytes")
    }
    return ED25519_SUBJECT_PUBLIC_KEY_INFO_PREFIX + rawPublicKey
}

@Suppress("ThrowsCount")
private fun verifyKeyPair(
    privateKey: PrivateKey,
    publicKey: PublicKey,
    algorithm: IdentitySignatureAlgorithm,
) {
    val challenge = "aegis-desktop-device-identity-v1".encodeToByteArray()
    var signature: ByteArray? = null
    try {
        signature =
            Signature.getInstance(algorithm.signatureName()).run {
                initSign(privateKey)
                update(challenge)
                sign()
            }
        val matches =
            Signature.getInstance(algorithm.signatureName()).run {
                initVerify(publicKey)
                update(challenge)
                verify(signature)
            }
        if (!matches) {
            throw DeviceIdentityStorageException("Stored desktop public and private keys do not form a pair")
        }
    } catch (error: DeviceIdentityStorageException) {
        throw error
    } catch (error: Exception) {
        throw DeviceIdentityStorageException("Unable to verify the desktop signing key pair", error)
    } finally {
        challenge.fill(0)
        signature?.fill(0)
    }
}

private fun IdentitySignatureAlgorithm.keyFactoryName(): String =
    when (this) {
        IdentitySignatureAlgorithm.ED25519 -> ED25519
        IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> EC
    }

private fun IdentitySignatureAlgorithm.signatureName(): String =
    when (this) {
        IdentitySignatureAlgorithm.ED25519 -> ED25519
        IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> "SHA256withECDSA"
    }

private fun requireP256PublicKey(publicKey: PublicKey) = requireExactP256PublicKey(publicKey)

private fun encodeBase64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

private fun decodeBase64Url(
    value: String,
    label: String,
): ByteArray =
    try {
        Base64.getUrlDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw DeviceIdentityStorageException("Stored $label is not valid base64url data", error)
    }

private fun withoutTrailingLineEndings(value: ByteArray): ByteArray {
    var end = value.size
    while (end > 0 && (value[end - 1] == '\n'.code.toByte() || value[end - 1] == '\r'.code.toByte())) {
        end -= 1
    }
    return value.copyOfRange(0, end)
}

private fun restrictPosixPermissions(
    path: Path,
    permissions: Set<PosixFilePermission>,
) {
    if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
        runCatching { Files.setPosixFilePermissions(path, permissions) }
    }
}

private fun forceFile(path: Path) {
    FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
        channel.force(true)
    }
}

private fun forceDirectoryBestEffort(directory: Path) {
    // Windows cannot open directories through FileChannel, and some Linux JVMs
    // reject force() on a directory. The committed metadata remains protected;
    // the pending marker still makes an interrupted first write fail closed.
    runCatching {
        FileChannel.open(directory, StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }
}

private object DesktopIdentityJvmLockCoordinator {
    private val stripes = Array(64) { Any() }

    fun lockFor(path: Path): Any = stripes[Math.floorMod(path.hashCode(), stripes.size)]
}

private const val ED25519 = "Ed25519"
private const val EC = "EC"
private const val P256_CURVE = "secp256r1"
private const val ED25519_PUBLIC_KEY_BYTES = 32
private const val FORMAT_VERSION = 3
private const val INITIAL_KEY_GENERATION = 1L
private const val WINDOWS_DPAPI_SCHEME = "windows-dpapi:v1"
private const val LINUX_SECRET_SERVICE_SCHEME = "linux-secret-service:v1"
private const val SECRET_SERVICE_REFERENCE_PREFIX = "aegis-secret-service:v1:"
private const val SECRET_TOOL_COMMAND = "secret-tool"
private const val SECRET_SERVICE_TIMEOUT_SECONDS = 15L
private const val SECRET_SERVICE_TIMEOUT_EXIT_CODE = 124
private const val SECRET_SERVICE_COMMAND_ERROR_EXIT_CODE = 127
private const val IDENTITY_WRITE_PENDING_MARKER = "aegis-desktop-device-identity-write-pending:v1"
private const val IDENTITY_INITIALIZED_MARKER = "aegis-desktop-device-identity-initialized:v1"

private val ED25519_SUBJECT_PUBLIC_KEY_INFO_PREFIX =
    byteArrayOf(
        0x30,
        0x2a,
        0x30,
        0x05,
        0x06,
        0x03,
        0x2b,
        0x65,
        0x70,
        0x03,
        0x21,
        0x00,
    )
