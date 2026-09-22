package dev.aegis.remote.protocol

import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DeviceIdentityCanonicalEncoding
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.IdentitySecurityLevel
import dev.aegis.remote.core.model.IdentitySignatureAlgorithm
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.security.LocalDeviceIdentity
import dev.aegis.remote.core.webrtc.SignalingMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class P256LocalProtocolHandshakeTest {
    @Test
    fun lanHandshakeAuthenticatesPinnedIdentitiesBeforeEncryptedProtocolMessages() =
        runTest {
            val phone = identity(IdentitySignatureAlgorithm.ECDSA_P256_SHA256)
            val host = identity(IdentitySignatureAlgorithm.ED25519)
            val phoneTransport = LinkedTextTransport()
            val hostTransport = LinkedTextTransport()
            phoneTransport.peer = hostTransport
            hostTransport.peer = phoneTransport

            val hostChannel =
                async(Dispatchers.Default) {
                    openLanE2eeProtocolMessageChannel(
                        transport = hostTransport,
                        sessionId = "lan-session",
                        localIdentity = host,
                        expectedPeerIdentity = phone.publicIdentity,
                        localIsSource = false,
                        nowEpochMillis = 1_000,
                    )
                }
            val phoneChannel =
                async(Dispatchers.Default) {
                    openLanE2eeProtocolMessageChannel(
                        transport = phoneTransport,
                        sessionId = "lan-session",
                        localIdentity = phone,
                        expectedPeerIdentity = host.publicIdentity,
                        localIsSource = true,
                        nowEpochMillis = 1_000,
                    )
                }

            val source = phoneChannel.await()
            val target = hostChannel.await()
            val message = ProtocolMessage.Signaling(SessionId("lan-session"), SignalingMessage.Offer(SessionId("lan-session"), "private-sdp"))
            source.send(message)

            assertEquals(message, target.incoming.first())
            assertTrue(phoneTransport.sent.any { it.contains("E2EE_ENVELOPE") })
            assertFalse(phoneTransport.sent.any { it.contains("private-sdp") })

            source.close()
            target.close()
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
                    .getInstance(if (algorithm == IdentitySignatureAlgorithm.ED25519) "Ed25519" else "SHA256withECDSA")
                    .run {
                        initSign(pair.private)
                        update(payload)
                        sign()
                    }
        }
    }
}

private class LinkedTextTransport : ProtocolTextTransport {
    private val received = Channel<String>(Channel.UNLIMITED)
    lateinit var peer: LinkedTextTransport
    val sent = mutableListOf<String>()
    override val incomingText: Flow<String> = received.receiveAsFlow()

    override suspend fun sendText(payload: String) {
        sent += payload
        peer.received.send(payload)
    }

    override suspend fun close() {
        received.close()
    }
}
