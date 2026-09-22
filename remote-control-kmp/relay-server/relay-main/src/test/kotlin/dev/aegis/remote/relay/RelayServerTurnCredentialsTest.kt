package dev.aegis.remote.relay

import dev.aegis.remote.core.relay.RelayTurnCredentials
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals

class RelayServerTurnCredentialsTest {
    @Test
    fun returnsTurnCredentialsForAuthenticatedRelayDevice() =
        testApplication {
            val registry = RelayIdentityTestFixtures.registry(clock = { 1_000L })
            val registration =
                RelayIdentityTestFixtures.register(
                    registry = registry,
                    identity = RelayIdentityTestFixtures.identity(),
                    displayName = "PC",
                    now = 1_000L,
                )
            application {
                relayServerModule(
                    registry = registry,
                    turnCredentialIssuer =
                        HmacTurnCredentialIssuer(
                            urls = listOf("turn:turn.example.test:3478"),
                            sharedSecret = "shared-secret",
                            ttlMillis = 600_000,
                            clock = { 1_700_000_000_000 },
                        ),
                )
            }

            val response =
                client.get("/turn/credentials") {
                    header(HttpHeaders.Authorization, "Bearer ${registration.authToken}")
                    header("X-Relay-Device-Id", registration.relayDeviceId.value)
                }
            val credentials = relayJson.decodeFromString<RelayTurnCredentials>(response.bodyAsText())

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf("turn:turn.example.test:3478"), credentials.urls)
            assertEquals("1700000600:${registration.relayDeviceId.value}", credentials.username)
        }

    @Test
    fun rejectsTurnCredentialRequestsWithoutValidToken() =
        testApplication {
            val registry = RelayIdentityTestFixtures.registry(clock = { 1_000L })
            val registration =
                RelayIdentityTestFixtures.register(
                    registry = registry,
                    identity = RelayIdentityTestFixtures.identity(),
                    displayName = "PC",
                    now = 1_000L,
                )
            application {
                relayServerModule(
                    registry = registry,
                    turnCredentialIssuer =
                        HmacTurnCredentialIssuer(
                            urls = listOf("turn:turn.example.test:3478"),
                            sharedSecret = "shared-secret",
                        ),
                )
            }

            val response =
                client.get("/turn/credentials") {
                    header(HttpHeaders.Authorization, "Bearer wrong")
                    header("X-Relay-Device-Id", registration.relayDeviceId.value)
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun reportsUnavailableWhenTurnIssuerIsNotConfigured() =
        testApplication {
            application {
                relayServerModule(
                    registry = RelayIdentityTestFixtures.registry(clock = { 1_000L }),
                    turnCredentialIssuer = null,
                )
            }

            val response = client.get("/turn/credentials")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }
}
