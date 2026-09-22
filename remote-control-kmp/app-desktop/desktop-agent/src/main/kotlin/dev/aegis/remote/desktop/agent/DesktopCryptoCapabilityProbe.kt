package dev.aegis.remote.desktop.agent

import dev.aegis.remote.core.model.AEGIS_25519_CHACHA_V1
import dev.aegis.remote.core.model.AEGIS_P256_AESGCM_V1
import dev.aegis.remote.core.model.AeadAlgorithm
import dev.aegis.remote.core.model.IdentitySecurityLevel
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.model.KeyAgreementAlgorithm
import dev.aegis.remote.core.security.AeadCapability
import dev.aegis.remote.core.security.CryptoCapabilityFailure
import dev.aegis.remote.core.security.CryptoCapabilityProbe
import dev.aegis.remote.core.security.CryptoCapabilityReport
import dev.aegis.remote.core.security.IdentityCapability
import dev.aegis.remote.core.security.KeyAgreementCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.logging.Level
import java.util.logging.Logger
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec

/**
 * Executes real desktop cryptographic operations once per compatible runtime
 * and persists only the non-secret capability result. Provider declarations by
 * themselves are never treated as proof that an algorithm works.
 */
class DesktopCryptoCapabilityProbe(
    private val privateKeyProtector: DesktopPrivateKeyProtector = defaultDesktopPrivateKeyProtector(),
    private val cacheFile: Path = defaultDesktopCryptoCapabilityCachePath(),
    private val compatibilityInputs: () -> List<String> = ::defaultDesktopCryptoCompatibilityInputs,
    private val evaluator: (DesktopPrivateKeyProtector) -> CryptoCapabilityReport = ::evaluateDesktopCryptoCapabilities,
    private val json: Json = Json { ignoreUnknownKeys = false },
) : CryptoCapabilityProbe {
    private val mutex = Mutex()

    override suspend fun evaluate(forceRefresh: Boolean): CryptoCapabilityReport =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val compatibilityKey = compatibilityKey()
                if (!forceRefresh) {
                    readCache(compatibilityKey)?.let { return@withContext it }
                }
                evaluator(privateKeyProtector).also { report -> writeCache(compatibilityKey, report) }
            }
        }

    private fun compatibilityKey(): String {
        val raw =
            buildList {
                add(CACHE_SCHEMA_ID)
                add(privateKeyProtector.scheme)
                addAll(compatibilityInputs())
            }.joinToString("\u0000")
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(raw.encodeToByteArray()),
        )
    }

    private fun readCache(compatibilityKey: String): CryptoCapabilityReport? {
        if (Files.notExists(cacheFile)) return null
        return runCatching {
            val cached = json.decodeFromString<DesktopCryptoCapabilityCache>(Files.readString(cacheFile))
            cached.report.takeIf {
                cached.schemaVersion == CACHE_SCHEMA_VERSION &&
                    cached.compatibilityKey == compatibilityKey &&
                    it.isCoherent()
            } ?: run {
                invalidateCache("incompatible or incoherent record")
                null
            }
        }.getOrElse { error ->
            invalidateCache(error.javaClass.simpleName)
            null
        }
    }

    private fun writeCache(
        compatibilityKey: String,
        report: CryptoCapabilityReport,
    ) {
        check(report.isCoherent()) { "IDN-1007: desktop crypto capability evaluator returned an incoherent report" }
        val directory = cacheFile.parent ?: Path.of(".")
        var temporary: Path? = null
        try {
            runCatching {
                Files.createDirectories(directory)
                restrictCapabilityPermissions(
                    directory,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                )
                val temp = Files.createTempFile(directory, "${cacheFile.fileName}.", ".tmp")
                temporary = temp
                Files.writeString(
                    temp,
                    json.encodeToString(
                        DesktopCryptoCapabilityCache(
                            schemaVersion = CACHE_SCHEMA_VERSION,
                            compatibilityKey = compatibilityKey,
                            report = report,
                        ),
                    ),
                )
                FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
                restrictCapabilityPermissions(
                    temp,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
                runCatching {
                    Files.move(
                        temp,
                        cacheFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                }.getOrElse {
                    Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING)
                }
                restrictCapabilityPermissions(
                    cacheFile,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
                temporary = null
            }.onFailure { error ->
                LOG.log(
                    Level.WARNING,
                    "event=crypto_capability_cache_write_failed code=IDN-1009 type={0}",
                    error.javaClass.simpleName,
                )
            }
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun invalidateCache(reason: String) {
        LOG.log(Level.WARNING, "event=crypto_capability_cache_invalid code=IDN-1008 reason={0}", reason)
        runCatching { Files.deleteIfExists(cacheFile) }
    }

    private companion object {
        const val CACHE_SCHEMA_VERSION = 1
        const val CACHE_SCHEMA_ID = "aegis-desktop-crypto-capabilities-v1"
        val LOG: Logger = Logger.getLogger(DesktopCryptoCapabilityProbe::class.java.name)
    }
}

@Serializable
private data class DesktopCryptoCapabilityCache(
    val schemaVersion: Int,
    val compatibilityKey: String,
    val report: CryptoCapabilityReport,
)

internal fun evaluateDesktopCryptoCapabilities(protector: DesktopPrivateKeyProtector): CryptoCapabilityReport {
    val identities =
        listOf(
            probeDesktopIdentity(IdentitySignatureAlgorithm.ECDSA_P256_SHA256, protector),
            probeDesktopIdentity(IdentitySignatureAlgorithm.ED25519, protector),
        )
    val keyAgreements =
        listOf(
            probeDesktopP256KeyAgreement(),
            probeDesktopX25519KeyAgreement(),
        )
    val aeads =
        listOf(
            probeDesktopAesGcm(),
            probeDesktopChaCha(),
        )
    val suites =
        buildList {
            if (
                identities.supports(IdentitySignatureAlgorithm.ECDSA_P256_SHA256) &&
                keyAgreements.supports(KeyAgreementAlgorithm.ECDH_P256) &&
                aeads.supports(AeadAlgorithm.AES_256_GCM)
            ) {
                add(AEGIS_P256_AESGCM_V1)
            }
            if (
                identities.supports(IdentitySignatureAlgorithm.ED25519) &&
                keyAgreements.supports(KeyAgreementAlgorithm.X25519) &&
                aeads.supports(AeadAlgorithm.CHACHA20_POLY1305)
            ) {
                add(AEGIS_25519_CHACHA_V1)
            }
        }
    val failures =
        buildList {
            identities.filterNot(IdentityCapability::supported).forEach { capability ->
                add(CryptoCapabilityFailure(capability.failureCode ?: "IDN-1005", capability.algorithm.wireId))
            }
            keyAgreements.filterNot(KeyAgreementCapability::supported).forEach { capability ->
                add(CryptoCapabilityFailure(capability.failureCode ?: "IDN-1006", capability.algorithm.name))
            }
            aeads.filterNot(AeadCapability::supported).forEach { capability ->
                add(CryptoCapabilityFailure(capability.failureCode ?: "IDN-1007", capability.algorithm.name))
            }
        }
    return CryptoCapabilityReport(
        identityOptions = identities,
        keyAgreementOptions = keyAgreements,
        aeadOptions = aeads,
        supportedSuites = suites,
        recommendedSuite = suites.firstOrNull(),
        failures = failures,
    )
}

private fun probeDesktopIdentity(
    algorithm: IdentitySignatureAlgorithm,
    protector: DesktopPrivateKeyProtector,
): IdentityCapability =
    runCatching {
        val pair =
            when (algorithm) {
                IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> {
                    KeyPairGenerator.getInstance("EC").run {
                        initialize(ECGenParameterSpec("secp256r1"))
                        generateKeyPair()
                    }
                }

                IdentitySignatureAlgorithm.ED25519 -> {
                    KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                }
            }
        requireExactIdentityAlgorithm(algorithm, pair)
        verifySignatureOperation(algorithm, pair)
        verifyProtectedRoundTrip(pair, protector)
        IdentityCapability(
            algorithm = algorithm,
            supported = true,
            securityLevel = IdentitySecurityLevel.WRAPPED_SOFTWARE,
        )
    }.getOrElse { error ->
        IdentityCapability(
            algorithm = algorithm,
            supported = false,
            securityLevel = IdentitySecurityLevel.UNSUPPORTED,
            failureCode =
                when (algorithm) {
                    IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> "IDN-1005_P256_SELF_TEST_FAILED"
                    IdentitySignatureAlgorithm.ED25519 -> "IDN-1006_ED25519_SELF_TEST_FAILED"
                } + ":${error.javaClass.simpleName}",
        )
    }

private fun requireExactIdentityAlgorithm(
    algorithm: IdentitySignatureAlgorithm,
    pair: KeyPair,
) {
    when (algorithm) {
        IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> requireExactP256PublicKey(pair.public)
        IdentitySignatureAlgorithm.ED25519 -> requireExactNamedCurveSpki(pair.public, ED25519_SPKI_PREFIX, "Ed25519")
    }
}

private fun verifySignatureOperation(
    algorithm: IdentitySignatureAlgorithm,
    pair: KeyPair,
) {
    val payload = "Aegis desktop crypto capability probe v1".encodeToByteArray()
    val signatureName =
        when (algorithm) {
            IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> "SHA256withECDSA"
            IdentitySignatureAlgorithm.ED25519 -> "Ed25519"
        }
    val signature =
        Signature.getInstance(signatureName).run {
            initSign(pair.private)
            update(payload)
            sign()
        }
    try {
        check(
            Signature.getInstance(signatureName).run {
                initVerify(pair.public)
                update(payload)
                verify(signature)
            },
        ) { "identity signature self-test failed" }
    } finally {
        payload.fill(0)
        signature.fill(0)
    }
}

private fun verifyProtectedRoundTrip(
    pair: KeyPair,
    protector: DesktopPrivateKeyProtector,
) {
    val plain = requireNotNull(pair.private.encoded) { "identity private key is not encodable for protected storage" }
    var protected: ByteArray? = null
    var recovered: ByteArray? = null
    try {
        protected = protector.protect(plain)
        check(protected.isNotEmpty()) { "identity protector returned an empty value" }
        check(!MessageDigest.isEqual(plain, protected)) { "identity protector returned plaintext" }
        recovered = protector.unprotect(protected)
        check(MessageDigest.isEqual(plain, recovered)) { "identity protector round-trip failed" }
    } finally {
        protected?.let { value -> runCatching { protector.discard(value) } }
        plain.fill(0)
        protected?.fill(0)
        recovered?.fill(0)
    }
}

private fun probeDesktopP256KeyAgreement(): KeyAgreementCapability =
    runCatching {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val first = generator.generateKeyPair().also { requireExactP256PublicKey(it.public) }
        val second = generator.generateKeyPair().also { requireExactP256PublicKey(it.public) }
        verifyKeyAgreement("ECDH", first, second)
        KeyAgreementCapability(KeyAgreementAlgorithm.ECDH_P256, supported = true)
    }.getOrElse { error ->
        KeyAgreementCapability(
            KeyAgreementAlgorithm.ECDH_P256,
            supported = false,
            failureCode = "IDN-1005_ECDH_P256_SELF_TEST_FAILED:${error.javaClass.simpleName}",
        )
    }

private fun probeDesktopX25519KeyAgreement(): KeyAgreementCapability =
    runCatching {
        val generator = KeyPairGenerator.getInstance("X25519")
        val first = generator.generateKeyPair().also { requireExactNamedCurveSpki(it.public, X25519_SPKI_PREFIX, "X25519") }
        val second = generator.generateKeyPair().also { requireExactNamedCurveSpki(it.public, X25519_SPKI_PREFIX, "X25519") }
        verifyKeyAgreement("X25519", first, second)
        KeyAgreementCapability(KeyAgreementAlgorithm.X25519, supported = true)
    }.getOrElse { error ->
        KeyAgreementCapability(
            KeyAgreementAlgorithm.X25519,
            supported = false,
            failureCode = "IDN-1006_X25519_SELF_TEST_FAILED:${error.javaClass.simpleName}",
        )
    }

private fun verifyKeyAgreement(
    algorithm: String,
    first: KeyPair,
    second: KeyPair,
) {
    val firstSecret =
        KeyAgreement.getInstance(algorithm).run {
            init(first.private)
            doPhase(second.public, true)
            generateSecret()
        }
    val secondSecret =
        KeyAgreement.getInstance(algorithm).run {
            init(second.private)
            doPhase(first.public, true)
            generateSecret()
        }
    try {
        check(firstSecret.isNotEmpty() && MessageDigest.isEqual(firstSecret, secondSecret)) {
            "$algorithm agreement self-test failed"
        }
    } finally {
        firstSecret.fill(0)
        secondSecret.fill(0)
    }
}

private fun probeDesktopAesGcm(): AeadCapability =
    runCatching {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val nonce = ByteArray(12) { index -> index.toByte() }
        verifyAeadRoundTrip(
            transformation = "AES/GCM/NoPadding",
            key = key,
            nonce = nonce,
            initialize = { cipher, mode -> cipher.init(mode, key, GCMParameterSpec(128, nonce)) },
        )
        AeadCapability(AeadAlgorithm.AES_256_GCM, supported = true)
    }.getOrElse { error ->
        AeadCapability(
            AeadAlgorithm.AES_256_GCM,
            supported = false,
            failureCode = "IDN-1005_AES_256_GCM_SELF_TEST_FAILED:${error.javaClass.simpleName}",
        )
    }

private fun probeDesktopChaCha(): AeadCapability =
    runCatching {
        val key = KeyGenerator.getInstance("ChaCha20").apply { init(256) }.generateKey()
        val nonce = ByteArray(12) { index -> (index + 1).toByte() }
        verifyAeadRoundTrip(
            transformation = "ChaCha20-Poly1305",
            key = key,
            nonce = nonce,
            initialize = { cipher, mode -> cipher.init(mode, key, IvParameterSpec(nonce)) },
        )
        AeadCapability(AeadAlgorithm.CHACHA20_POLY1305, supported = true)
    }.getOrElse { error ->
        AeadCapability(
            AeadAlgorithm.CHACHA20_POLY1305,
            supported = false,
            failureCode = "IDN-1006_CHACHA20_POLY1305_SELF_TEST_FAILED:${error.javaClass.simpleName}",
        )
    }

private fun verifyAeadRoundTrip(
    transformation: String,
    key: javax.crypto.SecretKey,
    nonce: ByteArray,
    initialize: (Cipher, Int) -> Unit,
) {
    val plaintext = "Aegis AEAD capability probe".encodeToByteArray()
    val associatedData = "aegis-capability-v1".encodeToByteArray()
    var ciphertext: ByteArray? = null
    var recovered: ByteArray? = null
    try {
        ciphertext =
            Cipher.getInstance(transformation).run {
                initialize(this, Cipher.ENCRYPT_MODE)
                updateAAD(associatedData)
                doFinal(plaintext)
            }
        recovered =
            Cipher.getInstance(transformation).run {
                initialize(this, Cipher.DECRYPT_MODE)
                updateAAD(associatedData)
                doFinal(requireNotNull(ciphertext))
            }
        check(MessageDigest.isEqual(plaintext, recovered)) { "$transformation self-test failed" }
    } finally {
        key.encoded?.fill(0)
        nonce.fill(0)
        plaintext.fill(0)
        associatedData.fill(0)
        ciphertext?.fill(0)
        recovered?.fill(0)
    }
}

internal fun requireExactP256PublicKey(publicKey: PublicKey) {
    val key = publicKey as? ECPublicKey ?: invalidP256Key("Desktop P-256 identity is not an EC key")
    val field =
        key.params.curve.field as? ECFieldFp
            ?: invalidP256Key("Desktop P-256 identity does not use a prime field")
    val exact =
        field.p == P256_FIELD_PRIME &&
            key.params.curve.a == P256_CURVE_A &&
            key.params.curve.b == P256_CURVE_B &&
            key.params.generator.affineX == P256_GENERATOR_X &&
            key.params.generator.affineY == P256_GENERATOR_Y &&
            key.params.order == P256_ORDER &&
            key.params.cofactor == 1
    if (!exact) {
        invalidP256Key("Desktop identity provider substituted a curve other than secp256r1")
    }
}

private fun invalidP256Key(message: String): Nothing = throw DeviceIdentityStorageException(message)

private fun requireExactNamedCurveSpki(
    publicKey: PublicKey,
    prefix: ByteArray,
    label: String,
) {
    val encoded = requireNotNull(publicKey.encoded) { "$label public key has no canonical encoding" }
    check(encoded.size == prefix.size + 32 && encoded.copyOfRange(0, prefix.size).contentEquals(prefix)) {
        "$label provider substituted another public-key algorithm"
    }
}

private fun CryptoCapabilityReport.isCoherent(): Boolean {
    if (identityOptions.map(IdentityCapability::algorithm).distinct().size != identityOptions.size) return false
    if (keyAgreementOptions.map(KeyAgreementCapability::algorithm).distinct().size != keyAgreementOptions.size) return false
    if (aeadOptions.map(AeadCapability::algorithm).distinct().size != aeadOptions.size) return false
    val expectedSuites =
        buildList {
            if (
                identityOptions.supports(IdentitySignatureAlgorithm.ECDSA_P256_SHA256) &&
                keyAgreementOptions.supports(KeyAgreementAlgorithm.ECDH_P256) &&
                aeadOptions.supports(AeadAlgorithm.AES_256_GCM)
            ) {
                add(AEGIS_P256_AESGCM_V1)
            }
            if (
                identityOptions.supports(IdentitySignatureAlgorithm.ED25519) &&
                keyAgreementOptions.supports(KeyAgreementAlgorithm.X25519) &&
                aeadOptions.supports(AeadAlgorithm.CHACHA20_POLY1305)
            ) {
                add(AEGIS_25519_CHACHA_V1)
            }
        }
    return supportedSuites == expectedSuites && recommendedSuite == expectedSuites.firstOrNull()
}

private fun List<IdentityCapability>.supports(algorithm: IdentitySignatureAlgorithm): Boolean = any { it.algorithm == algorithm && it.supported }

private fun List<KeyAgreementCapability>.supports(algorithm: KeyAgreementAlgorithm): Boolean = any { it.algorithm == algorithm && it.supported }

private fun List<AeadCapability>.supports(algorithm: AeadAlgorithm): Boolean = any { it.algorithm == algorithm && it.supported }

private fun defaultDesktopCryptoCapabilityCachePath(): Path = Path.of(System.getProperty("user.home") ?: ".", ".aegis", "desktop-crypto-capabilities.json")

private fun defaultDesktopCryptoCompatibilityInputs(): List<String> =
    buildList {
        add(
            System.getProperty("aegis.version")
                ?: DesktopCryptoCapabilityProbe::class.java.`package`?.implementationVersion
                ?: "development",
        )
        add(System.getProperty("os.name").orEmpty())
        add(System.getProperty("os.version").orEmpty())
        add(System.getProperty("os.arch").orEmpty())
        add(System.getenv("PROCESSOR_ARCHITECTURE").orEmpty())
        add(System.getenv("PROCESSOR_IDENTIFIER").orEmpty())
        add(System.getProperty("java.runtime.version").orEmpty())
        add(System.getProperty("java.vm.name").orEmpty())
        add(
            Security.getProviders().joinToString("|") { provider ->
                "${provider.name}:${provider.versionStr}:${provider.info}:${provider.javaClass.name}"
            },
        )
        add(
            runCatching {
                Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider").`package`?.implementationVersion
            }.getOrNull().orEmpty(),
        )
    }

private fun restrictCapabilityPermissions(
    path: Path,
    permissions: Set<PosixFilePermission>,
) {
    if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
        runCatching { Files.setPosixFilePermissions(path, permissions) }
    }
}

private fun hex(value: String): BigInteger = BigInteger(value, 16)

private val P256_FIELD_PRIME = hex("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff")
private val P256_CURVE_A = hex("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc")
private val P256_CURVE_B = hex("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b")
private val P256_GENERATOR_X = hex("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296")
private val P256_GENERATOR_Y = hex("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
private val P256_ORDER = hex("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")
private val ED25519_SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
private val X25519_SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)
