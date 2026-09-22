package dev.aegis.remote.desktop.agent

import io.netty.handler.ssl.util.SelfSignedCertificate
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.UUID

data class LocalTlsIdentity(
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
) {
    val fingerprint: String
        get() =
            "SHA256:" +
                Base64.getEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(certificate.encoded),
                )
}

interface LocalTlsIdentityStore {
    fun getOrCreate(): LocalTlsIdentity
}

class LocalTlsIdentityStorageException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Stable desktop LAN identity. Only the public certificate is stored in the
 * clear; PKCS#8 is protected by the platform secure store (DPAPI on Windows or
 * Secret Service on Linux). Existing corruption fails closed so a transient
 * read/decrypt problem cannot silently invalidate every Android certificate
 * pin. The persisted protection-scheme field keeps existing Windows DPAPI
 * records compatible.
 */
class PersistentLocalTlsIdentityStore(
    private val file: Path = defaultLocalTlsIdentityPath(),
    private val protector: DesktopPrivateKeyProtector = defaultDesktopPrivateKeyProtector(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val generator: () -> LocalTlsIdentity = ::generateLocalTlsIdentity,
) : LocalTlsIdentityStore {
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun getOrCreate(): LocalTlsIdentity {
        if (!Files.exists(file)) return createAndPersist(replaceExisting = false)
        val current = loadExisting()
        try {
            current.certificate.checkValidity(Date(clock()))
        } catch (_: CertificateExpiredException) {
            return createAndPersist(replaceExisting = true)
        } catch (error: CertificateNotYetValidException) {
            throw LocalTlsIdentityStorageException("Persisted local TLS certificate is not currently valid", error)
        }
        return current
    }

    private fun createAndPersist(replaceExisting: Boolean): LocalTlsIdentity {
        val identity = generator()
        validateKeyPair(identity)
        val pkcs8 = identity.privateKey.encoded ?: throw LocalTlsIdentityStorageException("Local TLS private key is not PKCS#8 encodable")
        val protectedKey =
            try {
                protector.protect(pkcs8)
            } finally {
                pkcs8.fill(0)
            }
        try {
            val record =
                LocalTlsIdentityRecord(
                    keyAlgorithm = identity.privateKey.algorithm,
                    protectorScheme = protector.scheme,
                    certificateDer = Base64.getEncoder().encodeToString(identity.certificate.encoded),
                    protectedPrivateKey = Base64.getEncoder().encodeToString(protectedKey),
                )
            writeAtomically(json.encodeToString(LocalTlsIdentityRecord.serializer(), record), replaceExisting)
        } finally {
            protectedKey.fill(0)
        }
        return identity
    }

    private fun loadExisting(): LocalTlsIdentity =
        try {
            val record = json.decodeFromString(LocalTlsIdentityRecord.serializer(), Files.readString(file, StandardCharsets.UTF_8))
            check(record.version == LOCAL_TLS_IDENTITY_VERSION) { "unsupported format version" }
            check(record.protectorScheme == protector.scheme) { "private-key protection scheme mismatch" }
            val certificateBytes = Base64.getDecoder().decode(record.certificateDer)
            val protectedKey = Base64.getDecoder().decode(record.protectedPrivateKey)
            val pkcs8 =
                try {
                    protector.unprotect(protectedKey)
                } finally {
                    protectedKey.fill(0)
                }
            try {
                val certificate =
                    CertificateFactory
                        .getInstance("X.509")
                        .generateCertificate(certificateBytes.inputStream()) as X509Certificate
                val privateKey = KeyFactory.getInstance(record.keyAlgorithm).generatePrivate(PKCS8EncodedKeySpec(pkcs8))
                LocalTlsIdentity(certificate, privateKey).also(::validateKeyPair)
            } finally {
                certificateBytes.fill(0)
                pkcs8.fill(0)
            }
        } catch (error: LocalTlsIdentityStorageException) {
            throw error
        } catch (error: IOException) {
            corruptIdentity(error)
        } catch (error: GeneralSecurityException) {
            corruptIdentity(error)
        } catch (error: SerializationException) {
            corruptIdentity(error)
        } catch (error: IllegalArgumentException) {
            corruptIdentity(error)
        } catch (error: IllegalStateException) {
            corruptIdentity(error)
        }

    private fun corruptIdentity(error: Exception): Nothing =
        throw LocalTlsIdentityStorageException(
            "Persisted local TLS identity is corrupt or cannot be decrypted",
            error,
        )

    private fun writeAtomically(
        payload: String,
        replaceExisting: Boolean,
    ) {
        file.parent?.let(Files::createDirectories)
        val temporary = file.resolveSibling("${file.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temporary, payload, StandardCharsets.UTF_8)
            try {
                if (replaceExisting) {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE)
                }
            } catch (_: AtomicMoveNotSupportedException) {
                if (replaceExisting) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    Files.move(temporary, file)
                }
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

typealias PersistentWindowsLocalTlsIdentityStore = PersistentLocalTlsIdentityStore

internal class EphemeralLocalTlsIdentityStore : LocalTlsIdentityStore {
    private val identity by lazy(::generateLocalTlsIdentity)

    override fun getOrCreate(): LocalTlsIdentity = identity
}

internal fun generateLocalTlsIdentity(): LocalTlsIdentity {
    val generated = SelfSignedCertificate("Aegis Remote Desktop")
    return try {
        LocalTlsIdentity(generated.cert(), generated.key()).also(::validateKeyPair)
    } finally {
        generated.delete()
    }
}

private fun validateKeyPair(identity: LocalTlsIdentity) {
    val signatureAlgorithm =
        when (identity.privateKey.algorithm.uppercase()) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            "ED25519" -> "Ed25519"
            else -> throw LocalTlsIdentityStorageException("Unsupported local TLS private-key algorithm")
        }
    val challenge = "Aegis local TLS key match v1".toByteArray(StandardCharsets.UTF_8)
    val signature =
        Signature.getInstance(signatureAlgorithm).run {
            initSign(identity.privateKey)
            update(challenge)
            sign()
        }
    val matches =
        Signature.getInstance(signatureAlgorithm).run {
            initVerify(identity.certificate.publicKey)
            update(challenge)
            verify(signature)
        }
    challenge.fill(0)
    signature.fill(0)
    if (!matches) throw LocalTlsIdentityStorageException("Local TLS certificate does not match its protected private key")
}

private fun defaultLocalTlsIdentityPath(): Path = Path.of(System.getProperty("user.home"), ".aegis", "local-tls-identity.json")

@Serializable
private data class LocalTlsIdentityRecord(
    val version: Int = LOCAL_TLS_IDENTITY_VERSION,
    val keyAlgorithm: String,
    val protectorScheme: String,
    val certificateDer: String,
    val protectedPrivateKey: String,
)

private const val LOCAL_TLS_IDENTITY_VERSION = 1
