package dev.aegis.remote.relayclient

import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DevicePublicIdentity
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.relay.RegisterRelayDeviceV2Response
import dev.aegis.remote.core.relay.RelayDeviceChallengeResponse
import dev.aegis.remote.core.relay.RelayIdentityTranscript
import dev.aegis.remote.core.relay.RelaySession
import dev.aegis.remote.core.relay.RelaySessionApproval
import dev.aegis.remote.core.relay.RelaySessionResponse
import dev.aegis.remote.core.relay.RelayTurnCredentials
import dev.aegis.remote.core.relay.RotateRelayDeviceKeyV2Response
import dev.aegis.remote.core.security.LocalDeviceIdentity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class KtorRelayClientTest {
    @Test
    fun registersDeviceWithChallengeProofAndCreatesSessionWithBearerToken() =
        runTest {
            val requestedPaths = mutableListOf<String>()
            val authHeaders = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    requestedPaths += request.url.encodedPath
                    authHeaders += request.headers[HttpHeaders.Authorization]
                    when (request.url.encodedPath) {
                        "/health" -> {
                            respond("""{"status":"ok"}""")
                        }

                        "/v3/devices/challenge" -> {
                            respond(
                                content =
                                    relayClientJson.encodeToString(
                                        RelayDeviceChallengeResponse(
                                            nonce = "challenge-1",
                                            issuedAtEpochMillis = 1_000L,
                                            expiresAtEpochMillis = 61_000L,
                                            relayOrigin = "https://relay.example.test",
                                        ),
                                    ),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        "/v3/devices/register" -> {
                            respond(
                                content =
                                    relayClientJson.encodeToString(
                                        RegisterRelayDeviceV2Response(
                                            relayDeviceId = RelayDeviceId("android-1"),
                                            identity = testIdentity.publicIdentity,
                                            authToken = "token-1",
                                            expiresAtEpochMillis = 2_000L,
                                        ),
                                    ),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        "/sessions" -> {
                            respond(
                                content =
                                    relayClientJson.encodeToString(
                                        RelaySessionResponse(
                                            session =
                                                RelaySession(
                                                    sessionId = SessionId("session-1"),
                                                    relayDeviceId = RelayDeviceId("pc-1"),
                                                    expiresAtEpochMillis = 3_000L,
                                                ),
                                            sourceRelayDeviceId = RelayDeviceId("android-1"),
                                            targetRelayDeviceId = RelayDeviceId("pc-1"),
                                        ),
                                    ),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        "/sessions/session-1/approval" -> {
                            respond(
                                content =
                                    relayClientJson.encodeToString(
                                        RelaySessionApproval(
                                            sessionId = SessionId("session-1"),
                                            sourceRelayDeviceId = RelayDeviceId("android-1"),
                                            targetRelayDeviceId = RelayDeviceId("pc-1"),
                                            approved = true,
                                            decidedAtEpochMillis = 4_000L,
                                        ),
                                    ),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        "/turn/credentials" -> {
                            respond(
                                content =
                                    relayClientJson.encodeToString(
                                        RelayTurnCredentials(
                                            urls = listOf("turn:turn.example.test:3478"),
                                            username = "1700000600:android-1",
                                            credential = "temporary-secret",
                                            expiresAtEpochMillis = 1_700_000_600_000L,
                                        ),
                                    ),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        else -> {
                            respond("not found", HttpStatusCode.NotFound)
                        }
                    }
                }
            val client =
                KtorRelayClient(
                    baseUrl = "https://relay.example.test",
                    httpClient =
                        HttpClient(engine) {
                            install(ContentNegotiation) { json(relayClientJson) }
                            install(WebSockets)
                        },
                    clock = { 1_000L },
                )

            client.connect()
            val registration =
                client.registerDeviceV2(
                    identity = testIdentity,
                    displayName = "Android",
                    remoteAccessEnabled = false,
                )
            val session = client.createSession(RelayDeviceId("pc-1"))
            val approval = client.approveSession(session.sessionId, approved = true)
            val turn = client.requestTurnCredentials()

            assertEquals(
                listOf("/health", "/v3/devices/challenge", "/v3/devices/register", "/sessions", "/sessions/session-1/approval", "/turn/credentials"),
                requestedPaths,
            )
            assertEquals("android-1", registration.relayDeviceId.value)
            assertEquals("token-1", registration.authToken.tokenRef)
            assertEquals("session-1", session.sessionId.value)
            assertEquals(true, approval.approved)
            assertEquals(listOf("turn:turn.example.test:3478"), turn.urls)
            assertEquals("temporary-secret", turn.credential)
            assertEquals("Bearer token-1", authHeaders.last())
            assertContentEquals(
                RelayIdentityTranscript.registration(
                    protocolVersion = 3,
                    relayOrigin = "https://relay.example.test",
                    identity = testIdentity.publicIdentity,
                    challengeNonce = "challenge-1",
                    timestampEpochMillis = 1_000L,
                ),
                testIdentity.signedPayloads.single(),
            )
        }

    @Test
    fun rebuildsPublicContextAndRetriesRotationAfterProcessDeathWithoutBearerToken() =
        runTest {
            val current =
                RecordingLocalDeviceIdentity(
                    testIdentity.publicIdentity.copy(keyGeneration = 1L),
                )
            val replacement =
                RecordingLocalDeviceIdentity(
                    testIdentity.publicIdentity.copy(
                        deviceId = DeviceId("replacement-device"),
                        publicKeySpki = ByteArray(32) { (it + 1).toByte() },
                        fingerprint = "replacement-device",
                        keyGeneration = 2L,
                    ),
                )
            val requestedPaths = mutableListOf<String>()
            val authorizationHeaders = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    requestedPaths += request.url.encodedPath
                    authorizationHeaders += request.headers[HttpHeaders.Authorization]
                    when (request.url.encodedPath) {
                        "/v3/devices/challenge" -> {
                            respond(
                                relayClientJson.encodeToString(
                                    RelayDeviceChallengeResponse(
                                        nonce = "recovery-challenge",
                                        issuedAtEpochMillis = 1_000L,
                                        expiresAtEpochMillis = 61_000L,
                                        relayOrigin = "https://relay.example.test",
                                    ),
                                ),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        "/v3/devices/rotate-key" -> {
                            respond(
                                relayClientJson.encodeToString(
                                    RotateRelayDeviceKeyV2Response(
                                        relayDeviceId = RelayDeviceId("android-1"),
                                        identity = replacement.publicIdentity,
                                        authToken = "rotated-token",
                                        expiresAtEpochMillis = 62_000L,
                                    ),
                                ),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        else -> {
                            respond("not found", HttpStatusCode.NotFound)
                        }
                    }
                }
            val client =
                KtorRelayClient(
                    baseUrl = "https://relay.example.test",
                    httpClient =
                        HttpClient(engine) {
                            install(ContentNegotiation) { json(relayClientJson) }
                            install(WebSockets)
                        },
                    clock = { 2_000L },
                )
            val operationId = "00000000-0000-0000-0000-000000000401"

            client.prepareIdentityRotationRecoveryV2(current, RelayDeviceId("android-1"))
            val registration = client.rotateDeviceKeyV2(current, replacement, operationId, activateLocally = true)

            assertEquals(listOf("/v3/devices/challenge", "/v3/devices/rotate-key"), requestedPaths)
            assertEquals(listOf<String?>(null, null), authorizationHeaders)
            assertEquals("rotated-token", registration.authToken.tokenRef)
            assertEquals("https://relay.example.test", client.registeredRelayOrigin())
            assertContentEquals(
                RelayIdentityTranscript.rotation(
                    protocolVersion = 3,
                    operationId = operationId,
                    relayOrigin = "https://relay.example.test",
                    relayDeviceId = RelayDeviceId("android-1"),
                    currentIdentity = current.publicIdentity,
                    replacementIdentity = replacement.publicIdentity,
                    timestampEpochMillis = 2_000L,
                ),
                current.signedPayloads.single(),
            )
        }

    private companion object {
        val testIdentity =
            RecordingLocalDeviceIdentity(
                DevicePublicIdentity(
                    deviceId = DeviceId("Yw3NKWbEM2aRElRIu7JbT_QSpJxzLbLIq8G4WBvXEN0"),
                    signingPublicKey = ByteArray(32) { it.toByte() },
                ),
            )
    }
}

private class RecordingLocalDeviceIdentity(
    override val publicIdentity: DevicePublicIdentity,
) : LocalDeviceIdentity {
    val signedPayloads = mutableListOf<ByteArray>()

    override suspend fun sign(payload: ByteArray): ByteArray {
        signedPayloads += payload.copyOf()
        return ByteArray(64) { 7 }
    }
}
