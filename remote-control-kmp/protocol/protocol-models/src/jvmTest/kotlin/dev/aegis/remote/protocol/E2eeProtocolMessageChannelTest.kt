package dev.aegis.remote.protocol

import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DeviceIdentityCanonicalEncoding
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.IdentitySecurityLevel
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.relay.RelayOpaqueChannel
import dev.aegis.remote.core.relay.RelayOpaqueFrame
import dev.aegis.remote.core.relay.RelayPayloadKind
import dev.aegis.remote.core.security.AEGIS_E2EE_PROTOCOL_VERSION
import dev.aegis.remote.core.security.E2eeSession
import dev.aegis.remote.core.security.LocalDeviceIdentity
import dev.aegis.remote.core.security.P256SessionE2ee
import dev.aegis.remote.core.security.SessionHandshakeTranscript
import dev.aegis.remote.core.security.SignedSessionHandshake
import dev.aegis.remote.core.webrtc.SignalingMessage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class E2eeProtocolMessageChannelTest {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
        }

    @Test
    fun relayHandshakeAuthenticatesMixedIdentitiesAndNegotiatesBaseline() =
        runTest {
            val sourceIdentity = identity(IdentitySignatureAlgorithm.ECDSA_P256_SHA256)
            val targetIdentity = identity(IdentitySignatureAlgorithm.ED25519)
            val sourceRelay = LinkedOpaqueChannel()
            val targetRelay = LinkedOpaqueChannel()
            sourceRelay.peer = targetRelay
            targetRelay.peer = sourceRelay
            val capabilityHash = MessageDigest.getInstance("SHA-256").digest("AEGIS_P256_AESGCM_V1".encodeToByteArray())
            val handshake = P256RelayHandshake(timeoutMillis = 2_000)
            val target =
                async {
                    handshake.establishAsTarget(
                        targetRelay,
                        "session-handshake",
                        sourceIdentity.publicIdentity,
                        targetIdentity,
                        "https://relay.test",
                        capabilityHash,
                        1_500,
                    )
                }
            val source =
                async {
                    handshake.establishAsSource(
                        sourceRelay,
                        "session-handshake",
                        sourceIdentity,
                        targetIdentity.publicIdentity,
                        "https://relay.test",
                        capabilityHash,
                        1_000,
                        2_000,
                        1,
                    )
                }
            val sourceResult = source.await()
            val targetResult = target.await()
            val encrypted = sourceResult.session.encrypt("test", "secret".encodeToByteArray())
            assertEquals("secret", targetResult.session.decrypt(encrypted).decodeToString())
            assertEquals(2, sourceRelay.sent.size)
            assertEquals(1, targetRelay.sent.size)
            sourceResult.session.close()
            targetResult.session.close()
        }

    @Test
    fun remoteProtocolChannelExposesOnlyCiphertextToRelay() =
        runTest {
            val sourceIdentity = identity(IdentitySignatureAlgorithm.ECDSA_P256_SHA256)
            val targetIdentity = identity(IdentitySignatureAlgorithm.ED25519)
            val sourceEphemeral = P256SessionE2ee.generateEphemeralKeyPair()
            val targetEphemeral = P256SessionE2ee.generateEphemeralKeyPair()
            val transcript =
                SessionHandshakeTranscript(
                    protocolVersion = AEGIS_E2EE_PROTOCOL_VERSION,
                    sessionId = "session-1",
                    sourceIdentity = sourceIdentity.publicIdentity,
                    targetIdentity = targetIdentity.publicIdentity,
                    sourceEphemeralKeySpki = sourceEphemeral.publicKeySpki,
                    targetEphemeralKeySpki = targetEphemeral.publicKeySpki,
                    createdAtEpochMillis = 1_000,
                    expiresAtEpochMillis = 2_000,
                    relayOrigin = "https://relay.test",
                    capabilitiesHash = MessageDigest.getInstance("SHA-256").digest("p256".encodeToByteArray()),
                    generation = 1,
                )
            val signed =
                SignedSessionHandshake(
                    transcript,
                    P256SessionE2ee.sign(sourceIdentity, transcript),
                    P256SessionE2ee.sign(targetIdentity, transcript),
                )
            val sourceRelay = LinkedOpaqueChannel()
            val targetRelay = LinkedOpaqueChannel()
            sourceRelay.peer = targetRelay
            targetRelay.peer = sourceRelay
            val source =
                E2eeProtocolMessageChannel(
                    sourceRelay,
                    P256SessionE2ee.establish(signed, sourceEphemeral, localIsSource = true),
                    role = E2eeChannelRole.SOURCE,
                    rekeyPolicy = E2eeRekeyPolicy(maxAgeMillis = Long.MAX_VALUE),
                    nowEpochMillis = { 1_500 },
                    coroutineContext = coroutineContext,
                )
            val target =
                E2eeProtocolMessageChannel(
                    targetRelay,
                    P256SessionE2ee.establish(signed, targetEphemeral, localIsSource = false),
                    role = E2eeChannelRole.TARGET,
                    rekeyPolicy = E2eeRekeyPolicy(maxAgeMillis = Long.MAX_VALUE),
                    nowEpochMillis = { 1_500 },
                    coroutineContext = coroutineContext,
                )
            val secretSdp = "v=0\\r\\na=ice-pwd:SUPER-SECRET-ICE-PASSWORD"
            val received = async { target.incoming.first() }

            source.send(
                ProtocolMessage.Signaling(
                    SessionId("session-1"),
                    SignalingMessage.Offer(SessionId("session-1"), secretSdp),
                ),
            )

            val relayVisible = sourceRelay.sent.single().payloadJson
            assertFalse(relayVisible.contains("SUPER-SECRET"))
            assertFalse(relayVisible.contains("ice-pwd"))
            val decoded = assertIs<ProtocolMessage.Signaling>(received.await())
            assertEquals(secretSdp, assertIs<SignalingMessage.Offer>(decoded.message).sdp)
            source.close()
            target.close()
        }

    @Test
    fun rejectedEnvelopeClosesTransportAndPreventsFollowingPlaintextDecode() =
        runTest {
            val sessions = sessionPair("session-fail-closed")
            val sourceRelay = LinkedOpaqueChannel()
            val targetRelay = LinkedOpaqueChannel()
            sourceRelay.peer = targetRelay
            targetRelay.peer = sourceRelay
            val codec = RecordingProtocolMessageJsonCodec()
            val target =
                E2eeProtocolMessageChannel(
                    relay = targetRelay,
                    session = sessions.target,
                    codec = codec,
                    role = E2eeChannelRole.TARGET,
                    nowEpochMillis = { 1_500 },
                    coroutineContext = coroutineContext,
                )
            val invalid =
                sessions.source.encrypt("ProtocolMessage", codec.encode(signaling("session-fail-closed", "rejected")).encodeToByteArray())
            val valid =
                sessions.source.encrypt("ProtocolMessage", codec.encode(signaling("session-fail-closed", "must-not-decode")).encodeToByteArray())

            sourceRelay.send(
                RelayOpaqueFrame(
                    kind = RelayPayloadKind.E2EE_ENVELOPE,
                    payloadJson = json.encodeToString(invalid.copy(sessionId = "wrong-session")),
                ),
            )
            sourceRelay.send(
                RelayOpaqueFrame(
                    kind = RelayPayloadKind.E2EE_ENVELOPE,
                    payloadJson = json.encodeToString(valid),
                ),
            )
            advanceUntilIdle()

            val error = assertFailsWith<IllegalArgumentException> { target.incoming.first() }
            assertEquals("WRONG_SESSION", error.message)
            assertEquals(0, codec.decodeCalls)
            assertTrue(targetRelay.closed)
            val sessionClosed =
                assertFailsWith<IllegalStateException> {
                    sessions.target.encrypt("after-rejection", byteArrayOf(1))
                }
            assertEquals("SESSION_CLOSED", sessionClosed.message)
            sessions.source.close()
        }

    @Test
    fun malformedEnvelopeSanitizesIncomingFailureAndPreventsFollowingPlaintextDecode() =
        runTest {
            val sessions = sessionPair("session-malformed-envelope")
            val sourceRelay = LinkedOpaqueChannel()
            val targetRelay = LinkedOpaqueChannel()
            sourceRelay.peer = targetRelay
            targetRelay.peer = sourceRelay
            val codec = RecordingProtocolMessageJsonCodec()
            val target =
                E2eeProtocolMessageChannel(
                    relay = targetRelay,
                    session = sessions.target,
                    codec = codec,
                    role = E2eeChannelRole.TARGET,
                    nowEpochMillis = { 1_500 },
                    coroutineContext = coroutineContext,
                )
            val payloadMarker = "UNTRUSTED_ENVELOPE_PAYLOAD_MARKER"
            val valid =
                sessions.source.encrypt(
                    "ProtocolMessage",
                    codec.encode(signaling("session-malformed-envelope", "must-not-decode")).encodeToByteArray(),
                )

            sourceRelay.send(
                RelayOpaqueFrame(
                    kind = RelayPayloadKind.E2EE_ENVELOPE,
                    payloadJson = "{\"sessionId\":\"$payloadMarker\"",
                ),
            )
            sourceRelay.send(
                RelayOpaqueFrame(
                    kind = RelayPayloadKind.E2EE_ENVELOPE,
                    payloadJson = json.encodeToString(valid),
                ),
            )
            advanceUntilIdle()

            val error = assertFailsWith<IllegalArgumentException> { target.incoming.first() }
            assertEquals("E2EE_PROTOCOL_FAILURE", error.message)
            assertFalse(error.message.orEmpty().contains(payloadMarker))
            assertEquals(0, codec.decodeCalls)
            assertTrue(targetRelay.closed)
            sessions.source.close()
        }

    @Test
    fun sourceAutomaticallyRekeysWithEncryptedRequestAndAckBeforeContinuing() =
        runTest {
            val sessions = sessionPair("session-rekey-success")
            val sourceRelay = LinkedOpaqueChannel()
            val targetRelay = LinkedOpaqueChannel()
            sourceRelay.peer = targetRelay
            targetRelay.peer = sourceRelay
            val policy =
                E2eeRekeyPolicy(
                    maxAgeMillis = Long.MAX_VALUE,
                    maxMessagesPerDirection = 1uL,
                    timeoutMillis = 1_000,
                )
            val source =
                E2eeProtocolMessageChannel(
                    relay = sourceRelay,
                    session = sessions.source,
                    role = E2eeChannelRole.SOURCE,
                    rekeyPolicy = policy,
                    nowEpochMillis = { 1_500 },
                    coroutineContext = coroutineContext,
                )
            val target =
                E2eeProtocolMessageChannel(
                    relay = targetRelay,
                    session = sessions.target,
                    role = E2eeChannelRole.TARGET,
                    rekeyPolicy = policy,
                    nowEpochMillis = { 1_500 },
                    coroutineContext = coroutineContext,
                )

            source.send(signaling("session-rekey-success", "offer-one"))
            advanceUntilIdle()
            val secondSend = async { source.send(signaling("session-rekey-success", "offer-two")) }
            advanceUntilIdle()
            secondSend.await()

            val received = target.incoming.take(2).toList()
            assertEquals(2, received.size)
            assertEquals(2, sessions.source.generation())
            assertEquals(2, sessions.target.generation())
            assertEquals(3, sourceRelay.sent.size)
            assertEquals(1, targetRelay.sent.size)
            assertTrue((sourceRelay.sent + targetRelay.sent).all { it.kind == RelayPayloadKind.E2EE_ENVELOPE })
            assertTrue((sourceRelay.sent + targetRelay.sent).none { it.payloadJson.contains("requestId") })
            assertTrue((sourceRelay.sent + targetRelay.sent).none { it.payloadJson.contains("nextGeneration") })

            source.close()
            target.close()
        }

    @Test
    fun targetRejectsNonConsecutiveRekeyGenerationAndClosesFailClosed() =
        runTest {
            val sessions = sessionPair("session-rekey-mismatch")
            val sourceRelay = LinkedOpaqueChannel()
            val targetRelay = LinkedOpaqueChannel()
            sourceRelay.peer = targetRelay
            targetRelay.peer = sourceRelay
            val target =
                E2eeProtocolMessageChannel(
                    relay = targetRelay,
                    session = sessions.target,
                    role = E2eeChannelRole.TARGET,
                    nowEpochMillis = { 1_500 },
                    coroutineContext = coroutineContext,
                )
            val malformed =
                EncryptedRekeyRequest(
                    requestId = "mismatched-generation",
                    currentGeneration = 1,
                    nextGeneration = 3,
                    requestedAtEpochMillis = 1_500,
                )

            sourceRelay.send(rekeyRequestFrame(sessions.source, malformed))
            advanceUntilIdle()

            val error = assertFailsWith<IllegalArgumentException> { target.incoming.first() }
            assertEquals("INVALID_REKEY_NEXT_GENERATION", error.message)
            assertTrue(targetRelay.closed)
            assertFailsWith<IllegalStateException> {
                sessions.target.encrypt("after-failure", byteArrayOf(1))
            }
            sessions.source.close()
        }

    @Test
    fun replayedEncryptedRekeyRequestClosesTarget() =
        runTest {
            val sessions = sessionPair("session-rekey-replay")
            val sourceRelay = LinkedOpaqueChannel()
            val targetRelay = LinkedOpaqueChannel()
            sourceRelay.peer = targetRelay
            targetRelay.peer = sourceRelay
            val target =
                E2eeProtocolMessageChannel(
                    relay = targetRelay,
                    session = sessions.target,
                    role = E2eeChannelRole.TARGET,
                    nowEpochMillis = { 1_500 },
                    coroutineContext = coroutineContext,
                )
            val request =
                EncryptedRekeyRequest(
                    requestId = "single-use-request",
                    currentGeneration = 1,
                    nextGeneration = 2,
                    requestedAtEpochMillis = 1_500,
                )
            val encryptedRequest = rekeyRequestFrame(sessions.source, request)

            sourceRelay.send(encryptedRequest)
            advanceUntilIdle()
            assertEquals(2, sessions.target.generation())
            sourceRelay.send(encryptedRequest)
            advanceUntilIdle()

            val error = assertFailsWith<IllegalArgumentException> { target.incoming.first() }
            assertEquals("WRONG_GENERATION", error.message)
            assertTrue(targetRelay.closed)
            sessions.source.close()
        }

    @Test
    fun sourceClosesWhenEncryptedRekeyAcknowledgementTimesOut() =
        runTest {
            val sessions = sessionPair("session-rekey-timeout")
            val sourceRelay = LinkedOpaqueChannel()
            val absentTargetRelay = LinkedOpaqueChannel()
            sourceRelay.peer = absentTargetRelay
            absentTargetRelay.peer = sourceRelay
            val source =
                E2eeProtocolMessageChannel(
                    relay = sourceRelay,
                    session = sessions.source,
                    role = E2eeChannelRole.SOURCE,
                    rekeyPolicy =
                        E2eeRekeyPolicy(
                            maxAgeMillis = 1,
                            timeoutMillis = 100,
                        ),
                    nowEpochMillis = { 1_500 },
                    coroutineContext = coroutineContext,
                )

            assertFailsWith<TimeoutCancellationException> {
                source.send(signaling("session-rekey-timeout", "never-sent-in-old-generation"))
            }
            assertTrue(sourceRelay.closed)
            assertEquals(1, sourceRelay.sent.size)
            assertTrue(sourceRelay.sent.all { it.kind == RelayPayloadKind.E2EE_ENVELOPE })
            assertFailsWith<IllegalStateException> {
                sessions.source.encrypt("after-timeout", byteArrayOf(1))
            }
            sessions.target.close()
        }

    private suspend fun sessionPair(sessionId: String): EstablishedSessions {
        val sourceIdentity = identity(IdentitySignatureAlgorithm.ECDSA_P256_SHA256)
        val targetIdentity = identity(IdentitySignatureAlgorithm.ED25519)
        val sourceEphemeral = P256SessionE2ee.generateEphemeralKeyPair()
        val targetEphemeral = P256SessionE2ee.generateEphemeralKeyPair()
        val transcript =
            SessionHandshakeTranscript(
                protocolVersion = AEGIS_E2EE_PROTOCOL_VERSION,
                sessionId = sessionId,
                sourceIdentity = sourceIdentity.publicIdentity,
                targetIdentity = targetIdentity.publicIdentity,
                sourceEphemeralKeySpki = sourceEphemeral.publicKeySpki,
                targetEphemeralKeySpki = targetEphemeral.publicKeySpki,
                createdAtEpochMillis = 1_000,
                expiresAtEpochMillis = 2_000,
                relayOrigin = "https://relay.test",
                capabilitiesHash = MessageDigest.getInstance("SHA-256").digest("p256".encodeToByteArray()),
                generation = 1,
            )
        val signed =
            SignedSessionHandshake(
                transcript,
                P256SessionE2ee.sign(sourceIdentity, transcript),
                P256SessionE2ee.sign(targetIdentity, transcript),
            )
        return EstablishedSessions(
            source = P256SessionE2ee.establish(signed, sourceEphemeral, localIsSource = true),
            target = P256SessionE2ee.establish(signed, targetEphemeral, localIsSource = false),
        )
    }

    private fun signaling(
        sessionId: String,
        sdp: String,
    ): ProtocolMessage.Signaling =
        ProtocolMessage.Signaling(
            SessionId(sessionId),
            SignalingMessage.Offer(SessionId(sessionId), sdp),
        )

    private fun rekeyRequestFrame(
        session: E2eeSession,
        request: EncryptedRekeyRequest,
    ): RelayOpaqueFrame {
        val plaintext = json.encodeToString(request).encodeToByteArray()
        val envelope =
            try {
                session.encrypt(AEGIS_REKEY_REQUEST_MESSAGE_TYPE, plaintext)
            } finally {
                plaintext.fill(0)
            }
        return RelayOpaqueFrame(
            kind = RelayPayloadKind.E2EE_ENVELOPE,
            payloadJson = json.encodeToString(envelope),
        )
    }

    private fun identity(algorithm: IdentitySignatureAlgorithm): LocalDeviceIdentity {
        val pair =
            if (algorithm == IdentitySignatureAlgorithm.ED25519) {
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            } else {
                KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            }
        val spki = pair.public.encoded
        val id =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(DeviceIdentityCanonicalEncoding.deviceIdPreimage(algorithm, spki)),
            )
        return object : LocalDeviceIdentity {
            override val publicIdentity =
                DevicePublicIdentity(
                    DeviceId(id),
                    algorithm,
                    spki,
                    "SHA256:test",
                    1,
                    IdentitySecurityLevel.OS_KEYSTORE,
                )

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
}

private data class EstablishedSessions(
    val source: E2eeSession,
    val target: E2eeSession,
)

private class RecordingProtocolMessageJsonCodec : ProtocolMessageJsonCodec() {
    var decodeCalls: Int = 0
        private set

    override fun decode(payload: String): ProtocolMessage {
        decodeCalls++
        return super.decode(payload)
    }
}

private class LinkedOpaqueChannel : RelayOpaqueChannel {
    private val received = Channel<RelayOpaqueFrame>(Channel.UNLIMITED)
    lateinit var peer: LinkedOpaqueChannel
    val sent = mutableListOf<RelayOpaqueFrame>()
    var closed: Boolean = false
        private set
    override val incoming: Flow<RelayOpaqueFrame> = received.receiveAsFlow()

    override suspend fun send(frame: RelayOpaqueFrame) {
        sent += frame
        peer.received.send(frame)
    }

    override suspend fun close() {
        closed = true
        received.close()
    }
}
