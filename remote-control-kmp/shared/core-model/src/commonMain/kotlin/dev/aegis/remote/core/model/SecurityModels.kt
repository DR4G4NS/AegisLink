package dev.aegis.remote.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AuthMethod {
    Password,
    PrivateKey,
    Agent,
}

@Serializable
data class HostKeyFingerprint(
    val algorithm: String,
    val value: String,
)

@Serializable
data class DevicePermissions(
    val terminal: Boolean = false,
    val visual: Boolean = false,
    val input: Boolean = false,
    val sftp: Boolean = false,
    val clipboard: Boolean = false,
    val wakeOnLan: Boolean = false,
    val remoteAccess: Boolean = false,
)

@Serializable
data class PairingToken(
    val publicPairingId: String,
    val nonce: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

/**
 * Public, persistent device identity. The device ID is derived from the exact
 * algorithm and canonical SubjectPublicKeyInfo by the platform identity store
 * and verified by the relay.
 */
@Serializable
enum class IdentitySignatureAlgorithm(
    val wireId: String,
) {
    ECDSA_P256_SHA256("ECDSA_P256_SHA256"),
    ED25519("ED25519"),
    ;

    companion object {
        fun fromWireId(value: String): IdentitySignatureAlgorithm =
            when (value.uppercase()) {
                "ED25519", "EDDSA" -> ED25519
                "ECDSA_P256_SHA256", "EC", "SHA256WITHECDSA" -> ECDSA_P256_SHA256
                else -> throw IllegalArgumentException("Unsupported device signing algorithm: $value")
            }
    }
}

@Serializable
enum class IdentitySecurityLevel {
    STRONGBOX,
    TRUSTED_ENVIRONMENT,
    OS_KEYSTORE,
    WRAPPED_SOFTWARE,
    UNSUPPORTED,
}

@Serializable
enum class KeyAgreementAlgorithm {
    ECDH_P256,
    X25519,
}

@Serializable
enum class AeadAlgorithm {
    AES_256_GCM,
    CHACHA20_POLY1305,
}

@Serializable
data class CryptoSuite(
    val id: String,
    val identitySignature: IdentitySignatureAlgorithm,
    val keyAgreement: KeyAgreementAlgorithm,
    val hkdfHash: String = "SHA-256",
    val aead: AeadAlgorithm,
)

val AEGIS_P256_AESGCM_V1 =
    CryptoSuite(
        id = "AEGIS_P256_AESGCM_V1",
        identitySignature = IdentitySignatureAlgorithm.ECDSA_P256_SHA256,
        keyAgreement = KeyAgreementAlgorithm.ECDH_P256,
        aead = AeadAlgorithm.AES_256_GCM,
    )

val AEGIS_25519_CHACHA_V1 =
    CryptoSuite(
        id = "AEGIS_25519_CHACHA_V1",
        identitySignature = IdentitySignatureAlgorithm.ED25519,
        keyAgreement = KeyAgreementAlgorithm.X25519,
        aead = AeadAlgorithm.CHACHA20_POLY1305,
    )

/** Canonical preimage hashed by every platform and independently checked by the relay. */
object DeviceIdentityCanonicalEncoding {
    const val PROTOCOL_IDENTITY_VERSION: Int = 3

    fun deviceIdPreimage(
        algorithm: IdentitySignatureAlgorithm,
        publicKeySpki: ByteArray,
    ): ByteArray =
        buildList<Byte> {
            addAll(intBytes(PROTOCOL_IDENTITY_VERSION).asList())
            addLengthPrefixed(algorithm.wireId.encodeToByteArray())
            addLengthPrefixed(publicKeySpki)
        }.toByteArray()

    private fun MutableList<Byte>.addLengthPrefixed(value: ByteArray) {
        addAll(intBytes(value.size).asList())
        addAll(value.asList())
    }

    private fun intBytes(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
}

@Serializable
data class DevicePublicIdentity(
    val deviceId: DeviceId,
    val algorithm: IdentitySignatureAlgorithm,
    val publicKeySpki: ByteArray,
    val fingerprint: String = deviceId.value,
    val keyGeneration: Long = 1L,
    val securityLevel: IdentitySecurityLevel = IdentitySecurityLevel.OS_KEYSTORE,
) {
    init {
        require(deviceId.value.isNotBlank()) { "Device ID must not be blank" }
        require(publicKeySpki.isNotEmpty()) { "Device signing public key must not be empty" }
        require(fingerprint.isNotBlank()) { "Device fingerprint must not be blank" }
        require(keyGeneration > 0) { "Device key generation must be positive" }
        require(securityLevel != IdentitySecurityLevel.UNSUPPORTED) {
            "A public identity cannot claim an unsupported security level"
        }
    }

    /** Temporary source-compatible bridge while v2 call sites migrate to explicit fields. */
    constructor(
        deviceId: DeviceId,
        signingPublicKey: ByteArray,
        keyAlgorithm: String = "Ed25519",
        keyGeneration: Long = 1L,
    ) : this(
        deviceId = deviceId,
        algorithm = IdentitySignatureAlgorithm.fromWireId(keyAlgorithm),
        publicKeySpki = signingPublicKey,
        fingerprint = deviceId.value,
        keyGeneration = keyGeneration,
        securityLevel = IdentitySecurityLevel.OS_KEYSTORE,
    )

    @Deprecated("Use publicKeySpki")
    val signingPublicKey: ByteArray get() = publicKeySpki

    @Deprecated("Use algorithm")
    val keyAlgorithm: String get() = algorithm.wireId

    fun matches(other: DevicePublicIdentity): Boolean =
        deviceId == other.deviceId &&
            algorithm == other.algorithm &&
            keyGeneration == other.keyGeneration &&
            publicKeySpki.contentEquals(other.publicKeySpki)
}

@Serializable
enum class DeviceTrustTransport {
    LocalPairing,
    RelayRemote,
    Manual,
}

@Serializable
data class DeviceAuthorization(
    val remoteDeviceId: RemoteDeviceId,
    val displayName: String,
    val approvedAtEpochMillis: Long,
    val permissions: DevicePermissions,
    val localProtocolToken: String? = null,
    /**
     * Null records are legacy pairing records. They remain visible and
     * revocable, but are never eligible for remote relay auto-approval.
     */
    val publicIdentity: DevicePublicIdentity? = null,
    val relayDeviceId: RelayDeviceId? = null,
    val approvedTransport: DeviceTrustTransport? = null,
    val lastApprovedCryptoSuiteId: String? = null,
    val cryptoSuiteDowngradeFloor: String? = null,
    val revokedAtEpochMillis: Long? = null,
    /**
     * A local-pairing SSH key is still present or its exact marker could not be
     * confirmed removed. Revocation of Aegis control remains effective while
     * this flag prevents presenting the SSH portion as fully revoked.
     */
    val sshKeyRemovalPending: Boolean = false,
    /** Stable diagnostic code for the last incomplete managed SSH key removal. */
    val sshKeyRemovalFailureCode: String? = null,
)
