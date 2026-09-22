package dev.aegis.remote.core.security

import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DeviceIdentityCanonicalEncoding
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.IdentitySecurityLevel
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class P256SessionE2eeTest {
    @Test
    fun p256HandshakeDerivesInteroperableDirectionalAesGcmKeys() {
        val fixture = fixture()
        val source = P256SessionE2ee.establish(fixture.signed, fixture.sourceEphemeral, true)
        val target = P256SessionE2ee.establish(fixture.signed, fixture.targetEphemeral, false)

        val first = source.encrypt("sdp", "offer-secret".encodeToByteArray())
        assertContentEquals("offer-secret".encodeToByteArray(), target.decrypt(first))
        val reply = target.encrypt("ice", "candidate-secret".encodeToByteArray())
        assertContentEquals("candidate-secret".encodeToByteArray(), source.decrypt(reply))
        assertContentEquals(source.exporterSecret, target.exporterSecret)
        assertTrue(first.messageId.isNotBlank())
        assertTrue(first.timestampEpochMillis > 0)
        assertEquals(AEGIS_CAPABILITY_VERSION, first.capabilityVersion)
    }

    @Test
    fun currentAndPreviousProtocolVersionsInteroperateOnlyAtTheSignedVersion() {
        for (version in AEGIS_E2EE_MIN_SUPPORTED_PROTOCOL_VERSION..AEGIS_E2EE_PROTOCOL_VERSION) {
            val fixture = fixture(protocolVersion = version)
            val source = P256SessionE2ee.establish(fixture.signed, fixture.sourceEphemeral, true)
            val target = P256SessionE2ee.establish(fixture.signed, fixture.targetEphemeral, false)
            val envelope = source.encrypt("control", "version-$version".encodeToByteArray())

            assertEquals(version, envelope.protocolVersion)
            assertContentEquals("version-$version".encodeToByteArray(), target.decrypt(envelope))
            assertFailsWith<IllegalArgumentException> {
                target.decrypt(envelope.copy(protocolVersion = if (version == 1) 2 else 1))
            }
        }
    }

    @Test
    fun decodesPreviousVersionTranscriptThatOmittedVersionField() {
        val previous = fixture(protocolVersion = AEGIS_E2EE_MIN_SUPPORTED_PROTOCOL_VERSION).signed.transcript
        val json = Json { encodeDefaults = false }
        val encoded = json.encodeToString(previous)

        assertTrue(!encoded.contains("protocolVersion"))
        val decoded = json.decodeFromString<SessionHandshakeTranscript>(encoded)
        assertEquals(AEGIS_E2EE_MIN_SUPPORTED_PROTOCOL_VERSION, decoded.protocolVersion)
        assertContentEquals(previous.canonicalBytes(), decoded.canonicalBytes())
    }

    @Test
    fun bitFlipFailsAuthenticationAndClosesSession() {
        val fixture = fixture()
        val source = P256SessionE2ee.establish(fixture.signed, fixture.sourceEphemeral, true)
        val target = P256SessionE2ee.establish(fixture.signed, fixture.targetEphemeral, false)
        val envelope = source.encrypt("input", "mouse".encodeToByteArray())
        val changed = envelope.ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        assertFailsWith<SecurityException> { target.decrypt(envelope.copy(ciphertext = changed)) }
        assertFailsWith<IllegalStateException> { target.decrypt(envelope) }
    }

    @Test
    fun everyAuthenticatedEnvelopeRejectionClosesTheSession() {
        val mutations =
            listOf<Pair<String, (EncryptedEnvelope) -> EncryptedEnvelope>>(
                "protocolVersion" to { it.copy(protocolVersion = AEGIS_E2EE_MIN_SUPPORTED_PROTOCOL_VERSION) },
                "capabilityVersion" to { it.copy(capabilityVersion = AEGIS_CAPABILITY_VERSION + 1) },
                "cryptoSuiteId" to { it.copy(cryptoSuiteId = "AEGIS_WEAK_V0") },
                "sessionId" to { it.copy(sessionId = "other") },
                "senderDeviceId" to { it.copy(senderDeviceId = DeviceId("wrong-sender")) },
                "generation" to { it.copy(generation = it.generation + 1) },
                "sequence-replay-gap" to { it.copy(sequenceNumber = it.sequenceNumber + 1uL) },
                "aead" to {
                    it.copy(
                        ciphertext =
                            it.ciphertext.copyOf().also { ciphertext ->
                                ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
                            },
                    )
                },
            )

        mutations.forEach { (field, mutate) ->
            val fixture = fixture()
            val source = P256SessionE2ee.establish(fixture.signed, fixture.sourceEphemeral, true)
            val target = P256SessionE2ee.establish(fixture.signed, fixture.targetEphemeral, false)
            val valid = source.encrypt("clipboard", "valid".encodeToByteArray())

            assertFailsWith<Throwable>(field) { target.decrypt(mutate(valid)) }
            assertSessionClosed(field) { target.encrypt("after-rejection", byteArrayOf(1)) }
            assertSessionClosed(field) { target.decrypt(valid) }
            assertSessionClosed(field) { target.rekey(2, 2_000) }
        }
    }

    @Test
    fun invalidRekeyGenerationClosesTheSession() {
        val fixture = fixture()
        val source = P256SessionE2ee.establish(fixture.signed, fixture.sourceEphemeral, true)

        assertFailsWith<IllegalArgumentException> { source.rekey(3, 2_000) }
        assertSessionClosed { source.encrypt("after-rejection", byteArrayOf(1)) }
        assertSessionClosed { source.rekey(2, 2_000) }
    }

    @Test
    fun signedTranscriptDetectsRelayOriginExpiryAndAnyMutation() {
        val fixture = fixture()
        P256SessionE2ee.verify(fixture.signed, 1_500, "https://relay.test")
        assertFailsWith<IllegalArgumentException> { P256SessionE2ee.verify(fixture.signed, 2_001, "https://relay.test") }
        assertFailsWith<IllegalArgumentException> { P256SessionE2ee.verify(fixture.signed, 1_500, "https://evil.test") }
        val changed = fixture.signed.copy(transcript = fixture.signed.transcript.copy(sessionId = "rewritten"))
        assertFailsWith<IllegalArgumentException> { P256SessionE2ee.verify(changed, 1_500, "https://relay.test") }
    }

    @Test
    fun rejectsNonP256EphemeralBeforeVerifyingHandshakeSignatures() {
        val fixture = fixture()
        val substituted =
            fixture.signed.copy(
                transcript = fixture.signed.transcript.copy(sourceEphemeralKeySpki = secp384r1KeyPair().public.encoded),
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                P256SessionE2ee.verify(substituted, 1_500, "https://relay.test")
            }

        assertEquals(ExactP256Curve.REJECTION_CODE, failure.message)
    }

    @Test
    fun reconnectUsesFreshEphemeralsAndDifferentTrafficKeys() {
        val first = fixture("session-1")
        val second = fixture("session-2")
        val firstSource = P256SessionE2ee.establish(first.signed, first.sourceEphemeral, true)
        val secondSource = P256SessionE2ee.establish(second.signed, second.sourceEphemeral, true)

        assertTrue(!first.sourceEphemeral.publicKeySpki.contentEquals(second.sourceEphemeral.publicKeySpki))
        assertTrue(!firstSource.exporterSecret.contentEquals(secondSource.exporterSecret))
    }

    @Test
    fun acknowledgedRekeyRotatesBothDirectionsAndResetsSequences() {
        val fixture = fixture()
        val source = P256SessionE2ee.establish(fixture.signed, fixture.sourceEphemeral, true)
        val target = P256SessionE2ee.establish(fixture.signed, fixture.targetEphemeral, false)
        val oldEnvelope = source.encrypt("control", "before".encodeToByteArray())
        assertContentEquals("before".encodeToByteArray(), target.decrypt(oldEnvelope))
        assertTrue(source.shouldRekey(nowEpochMillis = 1_901, maxAgeMillis = 900))

        source.rekey(nextGeneration = 2, nowEpochMillis = 1_901)
        target.rekey(nextGeneration = 2, nowEpochMillis = 1_901)

        val after = source.encrypt("control", "after".encodeToByteArray())
        assertEquals(2L, after.generation)
        assertEquals(0uL, after.sequenceNumber)
        assertContentEquals("after".encodeToByteArray(), target.decrypt(after))
        assertFailsWith<IllegalArgumentException> { target.decrypt(oldEnvelope) }
        assertFailsWith<IllegalArgumentException> { source.rekey(4, 2_000) }
    }

    private fun assertSessionClosed(
        message: String? = null,
        block: () -> Unit,
    ) {
        val error = assertFailsWith<IllegalStateException>(message, block)
        assertEquals("SESSION_CLOSED", error.message)
    }

    private fun fixture(
        sessionId: String = "session-1",
        protocolVersion: Int = AEGIS_E2EE_PROTOCOL_VERSION,
    ): Fixture {
        val sourceIdentity = testIdentity(IdentitySignatureAlgorithm.ECDSA_P256_SHA256)
        val targetIdentity = testIdentity(IdentitySignatureAlgorithm.ED25519)
        val sourceEphemeral = P256SessionE2ee.generateEphemeralKeyPair()
        val targetEphemeral = P256SessionE2ee.generateEphemeralKeyPair()
        val transcript =
            SessionHandshakeTranscript(
                protocolVersion = protocolVersion,
                sessionId = sessionId,
                sourceIdentity = sourceIdentity.publicIdentity,
                targetIdentity = targetIdentity.publicIdentity,
                sourceEphemeralKeySpki = sourceEphemeral.publicKeySpki,
                targetEphemeralKeySpki = targetEphemeral.publicKeySpki,
                createdAtEpochMillis = 1_000,
                expiresAtEpochMillis = 2_000,
                relayOrigin = "https://relay.test",
                capabilitiesHash = MessageDigest.getInstance("SHA-256").digest("capabilities".encodeToByteArray()),
                generation = 1,
            )
        val signed =
            SignedSessionHandshake(
                transcript,
                runBlocking { P256SessionE2ee.sign(sourceIdentity, transcript) },
                runBlocking { P256SessionE2ee.sign(targetIdentity, transcript) },
            )
        return Fixture(signed, sourceEphemeral, targetEphemeral)
    }

    private fun secp384r1KeyPair(): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp384r1")) }
            .generateKeyPair()

    private fun testIdentity(algorithm: IdentitySignatureAlgorithm): LocalDeviceIdentity {
        val pair =
            when (algorithm) {
                IdentitySignatureAlgorithm.ED25519 -> {
                    KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                }

                IdentitySignatureAlgorithm.ECDSA_P256_SHA256 -> {
                    KeyPairGenerator.getInstance("EC").run {
                        initialize(ECGenParameterSpec("secp256r1"))
                        generateKeyPair()
                    }
                }
            }
        val preimage = DeviceIdentityCanonicalEncoding.deviceIdPreimage(algorithm, pair.public.encoded)
        val deviceId = DeviceId(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(preimage)))
        val publicIdentity =
            DevicePublicIdentity(
                deviceId,
                algorithm,
                pair.public.encoded,
                "SHA256:test",
                1,
                IdentitySecurityLevel.OS_KEYSTORE,
            )
        return object : LocalDeviceIdentity {
            override val publicIdentity = publicIdentity

            override suspend fun sign(payload: ByteArray): ByteArray =
                Signature
                    .getInstance(
                        if (algorithm == IdentitySignatureAlgorithm.ED25519) "Ed25519" else "SHA256withECDSA",
                    ).run {
                        initSign(pair.private)
                        update(payload)
                        sign()
                    }
        }
    }

    private data class Fixture(
        val signed: SignedSessionHandshake,
        val sourceEphemeral: P256EphemeralKeyPair,
        val targetEphemeral: P256EphemeralKeyPair,
    )
}
