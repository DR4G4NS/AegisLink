package dev.aegis.remote.core.security

import dev.aegis.remote.core.model.AEGIS_P256_AESGCM_V1
import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val AEGIS_E2EE_PROTOCOL_VERSION: Int = 2
const val AEGIS_E2EE_MIN_SUPPORTED_PROTOCOL_VERSION: Int = 1
const val AEGIS_CAPABILITY_VERSION: Int = 1

@Serializable
data class SessionHandshakeTranscript(
    // V1 omitted this field; the serialization default preserves N-1 decoding.
    val protocolVersion: Int = AEGIS_E2EE_MIN_SUPPORTED_PROTOCOL_VERSION,
    val sessionId: String,
    val sourceIdentity: DevicePublicIdentity,
    val targetIdentity: DevicePublicIdentity,
    val sourceEphemeralKeySpki: ByteArray,
    val targetEphemeralKeySpki: ByteArray,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val relayOrigin: String,
    val capabilitiesHash: ByteArray,
    val generation: Long,
    val cryptoSuiteId: String = AEGIS_P256_AESGCM_V1.id,
) {
    init {
        require(protocolVersion in AEGIS_E2EE_MIN_SUPPORTED_PROTOCOL_VERSION..AEGIS_E2EE_PROTOCOL_VERSION)
        require(sessionId.isNotBlank())
        require(expiresAtEpochMillis > createdAtEpochMillis)
        require(relayOrigin.isNotBlank())
        require(capabilitiesHash.size == 32)
        require(generation > 0)
        require(cryptoSuiteId == AEGIS_P256_AESGCM_V1.id)
    }

    fun canonicalBytes(): ByteArray =
        CanonicalWriter()
            .apply {
                bytes("Aegis Remote Session Handshake v$protocolVersion".encodeToByteArray())
                int(protocolVersion)
                bytes(cryptoSuiteId.encodeToByteArray())
                bytes(sessionId.encodeToByteArray())
                identity(sourceIdentity)
                identity(targetIdentity)
                bytes(sourceEphemeralKeySpki)
                bytes(targetEphemeralKeySpki)
                long(createdAtEpochMillis)
                long(expiresAtEpochMillis)
                bytes(relayOrigin.encodeToByteArray())
                bytes(capabilitiesHash)
                long(generation)
            }.toByteArray()
}

@Serializable
data class SignedSessionHandshake(
    val transcript: SessionHandshakeTranscript,
    val sourceSignature: ByteArray,
    val targetSignature: ByteArray,
)

@Serializable
data class EncryptedEnvelope(
    val protocolVersion: Int,
    val messageId: String = "",
    val cryptoSuiteId: String,
    val sessionId: String,
    val senderDeviceId: DeviceId,
    val generation: Long,
    val sequenceNumber: ULong,
    val timestampEpochMillis: Long = 0,
    val messageType: String,
    val capabilityVersion: Int = AEGIS_CAPABILITY_VERSION,
    val ciphertext: ByteArray,
)

class P256EphemeralKeyPair internal constructor(
    val publicKeySpki: ByteArray,
    internal val privateKey: PrivateKey,
)

object P256SessionE2ee {
    fun ephemeralProviderDescription(): String {
        val provider = nonKeyStoreProvider("KeyPairGenerator", "EC")
        val implementation = provider.getService("KeyPairGenerator", "EC")?.className.orEmpty()
        return "provider=${provider.name} implementation=$implementation"
    }

    fun generateEphemeralKeyPair(): P256EphemeralKeyPair {
        val generator = KeyPairGenerator.getInstance("EC", nonKeyStoreProvider("KeyPairGenerator", "EC"))
        val pair =
            runCatching {
                generator.initialize(ECGenParameterSpec("secp256r1"))
                generator.generateKeyPair()
            }.getOrElse { error ->
                throw IllegalStateException(
                    "EPHEMERAL_EC_GENERATION_FAILED provider=${generator.provider.name} " +
                        "implementation=${generator.javaClass.name} cause=${error.message}",
                    error,
                )
            }
        requireP256(pair.public)
        return P256EphemeralKeyPair(pair.public.encoded.copyOf(), pair.private)
    }

    suspend fun sign(
        identity: LocalDeviceIdentity,
        transcript: SessionHandshakeTranscript,
    ): ByteArray = identity.sign(transcript.canonicalBytes())

    fun verify(
        signed: SignedSessionHandshake,
        nowEpochMillis: Long,
        expectedRelayOrigin: String,
    ) {
        val transcript = signed.transcript
        require(nowEpochMillis in transcript.createdAtEpochMillis..transcript.expiresAtEpochMillis) {
            "HANDSHAKE_EXPIRED_OR_NOT_YET_VALID"
        }
        require(transcript.relayOrigin == expectedRelayOrigin) { "WRONG_RELAY_ORIGIN" }
        require(transcript.cryptoSuiteId == AEGIS_P256_AESGCM_V1.id) { "CRYPTO_SUITE_DOWNGRADE" }
        // Reject P-256 substitutions before any untrusted signature is verified.
        parseP256PublicKey(transcript.sourceEphemeralKeySpki)
        parseP256PublicKey(transcript.targetEphemeralKeySpki)
        requireP256Identity(transcript.sourceIdentity)
        requireP256Identity(transcript.targetIdentity)
        verifyIdentitySignature(transcript.sourceIdentity, transcript.canonicalBytes(), signed.sourceSignature)
        verifyIdentitySignature(transcript.targetIdentity, transcript.canonicalBytes(), signed.targetSignature)
    }

    fun verifySignature(
        identity: DevicePublicIdentity,
        transcript: SessionHandshakeTranscript,
        signature: ByteArray,
    ) {
        verifyIdentitySignature(identity, transcript.canonicalBytes(), signature)
    }

    fun establish(
        signed: SignedSessionHandshake,
        localEphemeral: P256EphemeralKeyPair,
        localIsSource: Boolean,
    ): E2eeSession {
        val transcript = signed.transcript
        parseP256PublicKey(localEphemeral.publicKeySpki)
        val expectedLocal = if (localIsSource) transcript.sourceEphemeralKeySpki else transcript.targetEphemeralKeySpki
        require(MessageDigest.isEqual(localEphemeral.publicKeySpki, expectedLocal)) { "LOCAL_EPHEMERAL_KEY_MISMATCH" }
        val peerBytes = if (localIsSource) transcript.targetEphemeralKeySpki else transcript.sourceEphemeralKeySpki
        val agreement = KeyAgreement.getInstance("ECDH", nonKeyStoreProvider("KeyAgreement", "ECDH"))
        agreement.init(localEphemeral.privateKey)
        agreement.doPhase(parseP256PublicKey(peerBytes), true)
        val sharedSecret = agreement.generateSecret()
        val transcriptHash = MessageDigest.getInstance("SHA-256").digest(transcript.canonicalBytes())
        val material = hkdfSha256(sharedSecret, transcriptHash, "Aegis P256 AESGCM v1 keys".encodeToByteArray(), 152)
        sharedSecret.fill(0)
        val sourceToTarget = DirectionKeys(material.copyOfRange(0, 32), material.copyOfRange(64, 76))
        val targetToSource = DirectionKeys(material.copyOfRange(32, 64), material.copyOfRange(76, 88))
        val exporter = material.copyOfRange(88, 120)
        val rekey = material.copyOfRange(120, 152)
        material.fill(0)
        return E2eeSession(
            transcript = transcript,
            senderIdentity = if (localIsSource) transcript.sourceIdentity else transcript.targetIdentity,
            localIsSource = localIsSource,
            outbound = if (localIsSource) sourceToTarget else targetToSource,
            inbound = if (localIsSource) targetToSource else sourceToTarget,
            exporterSecret = exporter,
            rekeySecret = rekey,
        )
    }
}

class E2eeSession internal constructor(
    private val transcript: SessionHandshakeTranscript,
    private val senderIdentity: DevicePublicIdentity,
    private val localIsSource: Boolean,
    private val outbound: DirectionKeys,
    private val inbound: DirectionKeys,
    val exporterSecret: ByteArray,
    val rekeySecret: ByteArray,
) : AutoCloseable {
    private var nextOutboundSequence = 0uL
    private var nextInboundSequence = 0uL
    private var currentGeneration = transcript.generation
    private var generationStartedAtEpochMillis = transcript.createdAtEpochMillis
    private var closed = false

    /**
     * The source side is the sole traffic-key-update initiator. Exposing the
     * immutable handshake role lets transport adapters enforce that rule
     * without duplicating or guessing identity ordering.
     */
    val isSource: Boolean
        get() = localIsSource

    @Synchronized
    fun generation(): Long = currentGeneration

    @Synchronized
    fun encrypt(
        messageType: String,
        plaintext: ByteArray,
    ): EncryptedEnvelope {
        check(!closed) { "SESSION_CLOSED" }
        require(messageType.isNotBlank())
        check(nextOutboundSequence != ULong.MAX_VALUE) { "SEQUENCE_EXHAUSTED_REKEY_REQUIRED" }
        val sequence = nextOutboundSequence++
        val envelope =
            EncryptedEnvelope(
                protocolVersion = transcript.protocolVersion,
                messageId = if (transcript.protocolVersion >= 2) UUID.randomUUID().toString() else "",
                cryptoSuiteId = transcript.cryptoSuiteId,
                sessionId = transcript.sessionId,
                senderDeviceId = senderIdentity.deviceId,
                generation = currentGeneration,
                sequenceNumber = sequence,
                timestampEpochMillis = if (transcript.protocolVersion >= 2) System.currentTimeMillis() else 0,
                messageType = messageType,
                capabilityVersion = AEGIS_CAPABILITY_VERSION,
                ciphertext = byteArrayOf(),
            )
        return envelope.copy(ciphertext = crypt(Cipher.ENCRYPT_MODE, outbound, sequence, envelope.aad(), plaintext))
    }

    @Synchronized
    fun decrypt(envelope: EncryptedEnvelope): ByteArray {
        check(!closed) { "SESSION_CLOSED" }
        try {
            require(envelope.protocolVersion == transcript.protocolVersion) { "PROTOCOL_VERSION_DOWNGRADE" }
            if (envelope.protocolVersion >= 2) {
                require(envelope.messageId.isNotBlank()) { "MISSING_MESSAGE_ID" }
                require(envelope.timestampEpochMillis > 0) { "INVALID_MESSAGE_TIMESTAMP" }
                require(envelope.capabilityVersion == AEGIS_CAPABILITY_VERSION) { "UNKNOWN_CAPABILITY_VERSION" }
            }
            require(envelope.cryptoSuiteId == transcript.cryptoSuiteId) { "CRYPTO_SUITE_DOWNGRADE" }
            require(envelope.sessionId == transcript.sessionId) { "WRONG_SESSION" }
            val expectedPeer =
                if (senderIdentity.deviceId == transcript.sourceIdentity.deviceId) {
                    transcript.targetIdentity.deviceId
                } else {
                    transcript.sourceIdentity.deviceId
                }
            require(envelope.senderDeviceId == expectedPeer) { "WRONG_SENDER" }
            require(envelope.generation == currentGeneration) { "WRONG_GENERATION" }
            require(envelope.sequenceNumber == nextInboundSequence) { "REPLAY_REORDER_OR_GAP" }
            val plaintext =
                runCatching {
                    crypt(Cipher.DECRYPT_MODE, inbound, envelope.sequenceNumber, envelope.aad(), envelope.ciphertext)
                }.getOrElse { error ->
                    throw SecurityException("AEAD_AUTHENTICATION_FAILED", error)
                }
            nextInboundSequence++
            return plaintext
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    @Synchronized
    fun shouldRekey(
        nowEpochMillis: Long,
        maxAgeMillis: Long = 15 * 60 * 1_000L,
        maxMessagesPerDirection: ULong = 1_000_000uL,
    ): Boolean =
        nowEpochMillis - generationStartedAtEpochMillis >= maxAgeMillis ||
            nextOutboundSequence >= maxMessagesPerDirection ||
            nextInboundSequence >= maxMessagesPerDirection

    /**
     * Applies a deterministic, transcript-bound traffic-key update. Both peers
     * must invoke it for exactly the same next generation after an encrypted
     * rekey control message has been acknowledged.
     */
    @Synchronized
    fun rekey(
        nextGeneration: Long,
        nowEpochMillis: Long,
    ) {
        check(!closed) { "SESSION_CLOSED" }
        try {
            check(currentGeneration < Long.MAX_VALUE) { "REKEY_GENERATION_EXHAUSTED" }
            require(nextGeneration == currentGeneration + 1) { "INVALID_REKEY_GENERATION" }
            val salt =
                MessageDigest.getInstance("SHA-256").digest(
                    CanonicalWriter()
                        .apply {
                            bytes("Aegis Remote Session Rekey v1".encodeToByteArray())
                            bytes(transcript.sessionId.encodeToByteArray())
                            long(currentGeneration)
                            long(nextGeneration)
                        }.toByteArray(),
                )
            val material = hkdfSha256(rekeySecret, salt, "Aegis P256 AESGCM v1 rekey".encodeToByteArray(), 120)
            val sourceToTarget = DirectionKeys(material.copyOfRange(0, 32), material.copyOfRange(64, 76))
            val targetToSource = DirectionKeys(material.copyOfRange(32, 64), material.copyOfRange(76, 88))
            val nextRekeySecret = material.copyOfRange(88, 120)
            outbound.replaceWith(if (localIsSource) sourceToTarget else targetToSource)
            inbound.replaceWith(if (localIsSource) targetToSource else sourceToTarget)
            rekeySecret.fill(0)
            nextRekeySecret.copyInto(rekeySecret)
            sourceToTarget.clear()
            targetToSource.clear()
            nextRekeySecret.fill(0)
            material.fill(0)
            salt.fill(0)
            currentGeneration = nextGeneration
            generationStartedAtEpochMillis = nowEpochMillis
            nextOutboundSequence = 0uL
            nextInboundSequence = 0uL
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        outbound.clear()
        inbound.clear()
        exporterSecret.fill(0)
        rekeySecret.fill(0)
    }
}

internal data class DirectionKeys(
    val key: ByteArray,
    val nonceBase: ByteArray,
) {
    fun clear() {
        key.fill(0)
        nonceBase.fill(0)
    }

    fun replaceWith(other: DirectionKeys) {
        other.key.copyInto(key)
        other.nonceBase.copyInto(nonceBase)
    }
}

private fun EncryptedEnvelope.aad(): ByteArray =
    CanonicalWriter()
        .apply {
            bytes("Aegis Remote Encrypted Envelope v$protocolVersion".encodeToByteArray())
            int(protocolVersion)
            if (protocolVersion >= 2) bytes(messageId.encodeToByteArray())
            bytes(cryptoSuiteId.encodeToByteArray())
            bytes(sessionId.encodeToByteArray())
            bytes(senderDeviceId.value.encodeToByteArray())
            long(generation)
            ulong(sequenceNumber)
            if (protocolVersion >= 2) long(timestampEpochMillis)
            bytes(messageType.encodeToByteArray())
            if (protocolVersion >= 2) int(capabilityVersion)
        }.toByteArray()

private fun crypt(
    mode: Int,
    keys: DirectionKeys,
    sequence: ULong,
    aad: ByteArray,
    input: ByteArray,
): ByteArray {
    val nonce = keys.nonceBase.copyOf()
    val sequenceBytes = ByteBuffer.allocate(8).putLong(sequence.toLong()).array()
    for (index in sequenceBytes.indices) nonce[nonce.size - 8 + index] = (nonce[nonce.size - 8 + index].toInt() xor sequenceBytes[index].toInt()).toByte()
    return Cipher
        .getInstance("AES/GCM/NoPadding")
        .run {
            init(mode, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(input)
        }.also { nonce.fill(0) }
}

private fun verifyIdentitySignature(
    identity: DevicePublicIdentity,
    payload: ByteArray,
    signatureBytes: ByteArray,
) {
    if (identity.algorithm == IdentitySignatureAlgorithm.ED25519) {
        require(verifyExternalEd25519(identity.publicKeySpki, payload, signatureBytes)) {
            "INVALID_IDENTITY_SIGNATURE"
        }
        return
    }
    val algorithm =
        when (identity.algorithm) {
            IdentitySignatureAlgorithm.ED25519 -> error("Ed25519 verification is handled above")
            IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> "SHA256withECDSA"
        }
    val keyAlgorithm = "EC"
    val keyFactory = KeyFactory.getInstance(keyAlgorithm, nonKeyStoreProvider("KeyFactory", keyAlgorithm))
    val key =
        keyFactory
            .generatePublic(X509EncodedKeySpec(identity.publicKeySpki))
    if (identity.algorithm == IdentitySignatureAlgorithm.ECDSA_P256_SHA256) requireP256(key)
    val verifier = Signature.getInstance(algorithm, nonKeyStoreProvider("Signature", algorithm))
    require(
        verifier.run {
            initVerify(key)
            update(payload)
            verify(signatureBytes)
        },
    ) {
        "INVALID_IDENTITY_SIGNATURE"
    }
}

private fun requireP256Identity(identity: DevicePublicIdentity) {
    if (identity.algorithm != IdentitySignatureAlgorithm.ECDSA_P256_SHA256) return
    KeyFactory
        .getInstance("EC", nonKeyStoreProvider("KeyFactory", "EC"))
        .generatePublic(X509EncodedKeySpec(identity.publicKeySpki))
        .also(::requireP256)
}

private fun verifyExternalEd25519(
    spki: ByteArray,
    payload: ByteArray,
    signatureBytes: ByteArray,
): Boolean {
    val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    require(spki.size == prefix.size + Ed25519PublicKeyParameters.KEY_SIZE && spki.copyOfRange(0, prefix.size).contentEquals(prefix)) {
        "IDENTITY_KEY_NOT_CANONICAL_ED25519_SPKI"
    }
    return Ed25519Signer().run {
        init(false, Ed25519PublicKeyParameters(spki, prefix.size))
        update(payload, 0, payload.size)
        verifySignature(signatureBytes)
    }
}

private fun parseP256PublicKey(spki: ByteArray): PublicKey =
    KeyFactory
        .getInstance(
            "EC",
            nonKeyStoreProvider("KeyFactory", "EC"),
        ).generatePublic(X509EncodedKeySpec(spki))
        .also(::requireP256)

private fun nonKeyStoreProvider(
    service: String,
    algorithm: String,
): Provider = JcaSoftwareProviders.required(service, algorithm)

private fun requireP256(key: PublicKey) {
    require(ExactP256Curve.isSecp256r1(key)) { ExactP256Curve.REJECTION_CODE }
}

private fun hkdfSha256(
    ikm: ByteArray,
    salt: ByteArray,
    info: ByteArray,
    length: Int,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt, "HmacSHA256"))
    val prk = mac.doFinal(ikm)
    val output = ByteArrayOutputStream(length)
    var previous = byteArrayOf()
    var counter = 1
    while (output.size() < length) {
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(previous)
        mac.update(info)
        mac.update(counter.toByte())
        previous = mac.doFinal()
        output.write(previous, 0, minOf(previous.size, length - output.size()))
        counter++
    }
    prk.fill(0)
    previous.fill(0)
    return output.toByteArray()
}

private class CanonicalWriter {
    private val bytes = ByteArrayOutputStream()
    private val output = DataOutputStream(bytes)

    fun int(value: Int) {
        output.writeInt(value)
    }

    fun long(value: Long) {
        output.writeLong(value)
    }

    fun ulong(value: ULong) {
        output.writeLong(value.toLong())
    }

    fun bytes(value: ByteArray) {
        output.writeInt(value.size)
        output.write(value)
    }

    fun identity(value: DevicePublicIdentity) {
        bytes(value.deviceId.value.encodeToByteArray())
        bytes(value.algorithm.wireId.encodeToByteArray())
        bytes(MessageDigest.getInstance("SHA-256").digest(value.publicKeySpki))
        long(value.keyGeneration)
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}
