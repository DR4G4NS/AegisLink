package dev.aegis.remote.relay

import dev.aegis.remote.core.relay.RelayDeviceChallengeRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

class RelayServerRateLimitTest {
    @Test
    fun rateLimitsIdentityChallengeRequests() =
        testApplication {
            application {
                relayServerModule(
                    registry = RelayIdentityTestFixtures.registry(clock = { 1_000L }),
                    turnCredentialIssuer = null,
                    rateLimiter = InMemoryRelayRateLimiter(clock = { 1_000L }),
                    rateLimitConfig = RelayRateLimitConfig(requestsPerWindow = 1, windowMillis = 60_000),
                    runtimeConfig = RelayServerRuntimeConfig(trustForwardedHeaders = true),
                )
            }

            val first =
                client.post("/v3/devices/challenge") {
                    header("X-Forwarded-For", "203.0.113.10")
                    contentType(ContentType.Application.Json)
                    setBody(relayJson.encodeToString(challengeRequest()))
                }
            val second =
                client.post("/v3/devices/challenge") {
                    header("X-Forwarded-For", "203.0.113.10")
                    contentType(ContentType.Application.Json)
                    setBody(relayJson.encodeToString(challengeRequest()))
                }

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.TooManyRequests, second.status)
            assertEquals("60", second.headers[HttpHeaders.RetryAfter])
        }

    @Test
    fun rateLimitKeysAreSeparatedByForwardedClient() =
        testApplication {
            application {
                relayServerModule(
                    registry = RelayIdentityTestFixtures.registry(clock = { 1_000L }),
                    turnCredentialIssuer = null,
                    rateLimiter = InMemoryRelayRateLimiter(clock = { 1_000L }),
                    rateLimitConfig = RelayRateLimitConfig(requestsPerWindow = 1, windowMillis = 60_000),
                    runtimeConfig = RelayServerRuntimeConfig(trustForwardedHeaders = true),
                )
            }

            val first =
                client.post("/v3/devices/challenge") {
                    header("X-Forwarded-For", "203.0.113.10")
                    contentType(ContentType.Application.Json)
                    setBody(relayJson.encodeToString(challengeRequest()))
                }
            val second =
                client.post("/v3/devices/challenge") {
                    header("X-Forwarded-For", "203.0.113.11")
                    contentType(ContentType.Application.Json)
                    setBody(relayJson.encodeToString(challengeRequest()))
                }

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.OK, second.status)
        }

    @Test
    fun ignoresForwardedClientHeadersUnlessProxyTrustIsExplicit() =
        testApplication {
            application {
                relayServerModule(
                    registry = RelayIdentityTestFixtures.registry(clock = { 1_000L }),
                    turnCredentialIssuer = null,
                    rateLimiter = InMemoryRelayRateLimiter(clock = { 1_000L }),
                    rateLimitConfig = RelayRateLimitConfig(requestsPerWindow = 1, windowMillis = 60_000),
                    runtimeConfig = RelayServerRuntimeConfig(trustForwardedHeaders = false),
                )
            }

            val first =
                client.post("/v3/devices/challenge") {
                    header("X-Forwarded-For", "203.0.113.10")
                    contentType(ContentType.Application.Json)
                    setBody(relayJson.encodeToString(challengeRequest()))
                }
            val second =
                client.post("/v3/devices/challenge") {
                    header("X-Forwarded-For", "203.0.113.11")
                    contentType(ContentType.Application.Json)
                    setBody(relayJson.encodeToString(challengeRequest()))
                }

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.TooManyRequests, second.status)
        }

    @Test
    fun legacyDeviceRegistrationEndpointIsGone() =
        testApplication {
            application {
                relayServerModule(registry = RelayIdentityTestFixtures.registry(clock = { 1_000L }))
            }

            val response = client.post("/devices/register")

            assertEquals(HttpStatusCode.Gone, response.status)
        }

    private fun challengeRequest() =
        RelayDeviceChallengeRequest(
            identity = RelayIdentityTestFixtures.identity().identity,
        )
}
