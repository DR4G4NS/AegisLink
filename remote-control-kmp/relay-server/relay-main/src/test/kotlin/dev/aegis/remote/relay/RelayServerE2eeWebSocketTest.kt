package dev.aegis.remote.relay

import dev.aegis.remote.core.relay.RelayPayloadKind
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RelayServerE2eeWebSocketTest {
    @Test
    fun relaysOpaqueCiphertextButRejectsPlaintextAndSpoofedMetadata() =
        testApplication {
            val now = 10_000L
            val registry = RelayIdentityTestFixtures.registry(clock = { now })
            val source =
                RelayIdentityTestFixtures.register(
                    registry,
                    RelayIdentityTestFixtures.p256Identity(),
                    now,
                    displayName = "Android",
                    remoteAccessEnabled = false,
                )
            val target =
                RelayIdentityTestFixtures.register(
                    registry,
                    RelayIdentityTestFixtures.identity(),
                    now,
                    displayName = "Desktop",
                    remoteAccessEnabled = true,
                )
            val rendezvous = assertNotNull(registry.createSession(source.relayDeviceId, target.relayDeviceId))
            val sessionId = rendezvous.session.sessionId
            assertNotNull(registry.decideSession(sessionId, target.relayDeviceId, true))
            val plaintextSessionId =
                assertNotNull(registry.createSession(source.relayDeviceId, target.relayDeviceId)).session.sessionId
            assertNotNull(registry.decideSession(plaintextSessionId, target.relayDeviceId, true))
            val spoofedSessionId =
                assertNotNull(registry.createSession(source.relayDeviceId, target.relayDeviceId)).session.sessionId
            assertNotNull(registry.decideSession(spoofedSessionId, target.relayDeviceId, true))
            application {
                relayServerModule(
                    registry = registry,
                    turnCredentialIssuer = null,
                    identityPolicy = RelayIdentityTestFixtures.policy,
                    runtimeConfig = RelayServerRuntimeConfig(clock = { now }),
                )
            }
            val wsClient = createClient { install(WebSockets) }
            val targetSocket =
                wsClient.webSocketSession {
                    url("/signaling/${sessionId.value}/${target.relayDeviceId.value}")
                    header(HttpHeaders.Authorization, "Bearer ${target.authToken}")
                }
            val sourceSocket =
                wsClient.webSocketSession {
                    url("/signaling/${sessionId.value}/${source.relayDeviceId.value}")
                    header(HttpHeaders.Authorization, "Bearer ${source.authToken}")
                }
            val ciphertext = "{\"ciphertext\":\"AAECAwQ\",\"sequenceNumber\":0}"
            sourceSocket.send(
                Frame.Text(
                    relayJson.encodeToString(
                        RelayWebSocketEnvelope(
                            sessionId = sessionId,
                            senderRelayDeviceId = source.relayDeviceId,
                            payloadKind = RelayPayloadKind.E2EE_ENVELOPE,
                            payloadJson = ciphertext,
                        ),
                    ),
                ),
            )
            val relayed = withTimeout(2_000) { targetSocket.incoming.receive() as Frame.Text }
            val relayedEnvelope = relayJson.decodeFromString<RelayWebSocketEnvelope>(relayed.readText())
            assertEquals(RelayPayloadKind.E2EE_ENVELOPE, relayedEnvelope.payloadKind)
            assertEquals(ciphertext, relayedEnvelope.payloadJson)

            val plaintextSocket =
                wsClient.webSocketSession {
                    url("/signaling/${plaintextSessionId.value}/${source.relayDeviceId.value}")
                    header(HttpHeaders.Authorization, "Bearer ${source.authToken}")
                }
            plaintextSocket.send(
                Frame.Text(
                    relayJson.encodeToString(
                        RelayWebSocketEnvelope(
                            sessionId = plaintextSessionId,
                            senderRelayDeviceId = source.relayDeviceId,
                            payloadKind = RelayPayloadKind.LEGACY_PLAINTEXT,
                            payloadJson = "v=0\\r\\nsecret-sdp",
                        ),
                    ),
                ),
            )
            assertEquals(1008.toShort(), withTimeout(2_000) { plaintextSocket.closeReason.await() }?.code)

            val spoofedSocket =
                wsClient.webSocketSession {
                    url("/signaling/${spoofedSessionId.value}/${source.relayDeviceId.value}")
                    header(HttpHeaders.Authorization, "Bearer ${source.authToken}")
                }
            spoofedSocket.send(
                Frame.Text(
                    relayJson.encodeToString(
                        RelayWebSocketEnvelope(
                            sessionId = spoofedSessionId,
                            senderRelayDeviceId = target.relayDeviceId,
                            payloadKind = RelayPayloadKind.E2EE_ENVELOPE,
                            payloadJson = ciphertext,
                        ),
                    ),
                ),
            )
            assertEquals(1008.toShort(), withTimeout(2_000) { spoofedSocket.closeReason.await() }?.code)
            sourceSocket.close()
            targetSocket.close()
            wsClient.close()
        }

    @Test
    fun retainsOpaqueHandshakeUntilSecondParticipantJoins() =
        testApplication {
            val now = 20_000L
            val registry = RelayIdentityTestFixtures.registry(clock = { now })
            val source =
                RelayIdentityTestFixtures.register(
                    registry,
                    RelayIdentityTestFixtures.p256Identity(),
                    now,
                    displayName = "Android",
                    remoteAccessEnabled = false,
                )
            val target =
                RelayIdentityTestFixtures.register(
                    registry,
                    RelayIdentityTestFixtures.identity(),
                    now,
                    displayName = "Desktop",
                    remoteAccessEnabled = true,
                )
            val rendezvous = assertNotNull(registry.createSession(source.relayDeviceId, target.relayDeviceId))
            val sessionId = rendezvous.session.sessionId
            assertNotNull(registry.decideSession(sessionId, target.relayDeviceId, true))
            application {
                relayServerModule(
                    registry = registry,
                    turnCredentialIssuer = null,
                    identityPolicy = RelayIdentityTestFixtures.policy,
                    runtimeConfig = RelayServerRuntimeConfig(clock = { now }),
                )
            }
            val wsClient = createClient { install(WebSockets) }
            val sourceSocket =
                wsClient.webSocketSession {
                    url("/signaling/${sessionId.value}/${source.relayDeviceId.value}")
                    header(HttpHeaders.Authorization, "Bearer ${source.authToken}")
                }
            val initPayload = "{\"opaqueHandshake\":\"ciphertext-only\"}"
            sourceSocket.send(
                Frame.Text(
                    relayJson.encodeToString(
                        RelayWebSocketEnvelope(
                            sessionId = sessionId,
                            senderRelayDeviceId = source.relayDeviceId,
                            payloadKind = RelayPayloadKind.E2EE_HANDSHAKE,
                            payloadJson = initPayload,
                        ),
                    ),
                ),
            )

            val targetSocket =
                wsClient.webSocketSession {
                    url("/signaling/${sessionId.value}/${target.relayDeviceId.value}")
                    header(HttpHeaders.Authorization, "Bearer ${target.authToken}")
                }
            val delivered = withTimeout(2_000) { targetSocket.incoming.receive() as Frame.Text }
            val envelope = relayJson.decodeFromString<RelayWebSocketEnvelope>(delivered.readText())
            assertEquals(RelayPayloadKind.E2EE_HANDSHAKE, envelope.payloadKind)
            assertEquals(initPayload, envelope.payloadJson)

            sourceSocket.close()
            targetSocket.close()
            wsClient.close()
        }

    @Test
    fun closesActiveSignalingSocketWhenSessionExpires() =
        testApplication {
            val now = System.currentTimeMillis()
            val registry =
                RelayIdentityTestFixtures.registry(
                    clock = { System.currentTimeMillis() },
                    tokenTtlMillis = 5_000L,
                    sessionTtlMillis = 1_500L,
                )
            val source =
                RelayIdentityTestFixtures.register(
                    registry,
                    RelayIdentityTestFixtures.p256Identity(),
                    now,
                    displayName = "Android",
                    remoteAccessEnabled = false,
                )
            val target =
                RelayIdentityTestFixtures.register(
                    registry,
                    RelayIdentityTestFixtures.identity(),
                    now,
                    displayName = "Desktop",
                    remoteAccessEnabled = true,
                )
            val rendezvous = assertNotNull(registry.createSession(source.relayDeviceId, target.relayDeviceId))
            val sessionId = rendezvous.session.sessionId
            assertNotNull(registry.decideSession(sessionId, target.relayDeviceId, true))
            application {
                relayServerModule(
                    registry = registry,
                    turnCredentialIssuer = null,
                    identityPolicy = RelayIdentityTestFixtures.policy,
                )
            }
            val wsClient = createClient { install(WebSockets) }
            val socket =
                wsClient.webSocketSession {
                    url("/signaling/${sessionId.value}/${source.relayDeviceId.value}")
                    header(HttpHeaders.Authorization, "Bearer ${source.authToken}")
                }

            val reason = assertNotNull(withTimeout(5_000) { socket.closeReason.await() })
            assertEquals(1008.toShort(), reason.code)
            assertTrue(reason.message.contains("SES-7403"))
            wsClient.close()
        }
}
