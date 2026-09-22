package dev.aegis.remote.relay

import dev.aegis.remote.core.relay.RegisterRelayDeviceV2Response
import dev.aegis.remote.core.relay.RelayDeviceChallengeRequest
import dev.aegis.remote.core.relay.RelayDeviceChallengeResponse
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RelayServerIdentityV2Test {
    @Test
    fun rejectsNonP256IdentityBeforeIssuingRegistrationChallenge() {
        val registry = RelayIdentityTestFixtures.registry(clock = { 1_000L })
        val identity = RelayIdentityTestFixtures.p256Identity(curveName = "secp384r1")

        val failure =
            assertFailsWith<RelayIdentityRejectedException> {
                registry.issueRegistrationChallenge(
                    RelayDeviceChallengeRequest(identity = identity.identity),
                    RelayIdentityTestFixtures.policy,
                )
            }

        assertEquals(RelayIdentityRejectionCode.P256CurveSubstitution, failure.code)
    }

    @Test
    fun issuesChallengeAndRegistersSignedP256Identity() =
        testApplication {
            val now = 1_000L
            val identity = RelayIdentityTestFixtures.p256Identity()
            val registry = RelayIdentityTestFixtures.registry(clock = { now })
            application {
                relayServerModule(registry = registry, turnCredentialIssuer = null, identityPolicy = RelayIdentityTestFixtures.policy)
            }
            val challengeResponse =
                client.post("/v3/devices/challenge") {
                    contentType(ContentType.Application.Json)
                    setBody(relayJson.encodeToString(RelayDeviceChallengeRequest(identity = identity.identity)))
                }
            val challenge = relayJson.decodeFromString<RelayDeviceChallengeResponse>(challengeResponse.bodyAsText())
            val request = RelayIdentityTestFixtures.registrationRequest(identity, challenge.nonce, now)
            val registrationResponse =
                client.post("/v3/devices/register") {
                    contentType(ContentType.Application.Json)
                    setBody(relayJson.encodeToString(request))
                }
            val registration = relayJson.decodeFromString<RegisterRelayDeviceV2Response>(registrationResponse.bodyAsText())
            assertEquals(HttpStatusCode.OK, registrationResponse.status)
            assertTrue(registration.identity.matches(identity.identity))
            assertTrue(registry.authenticate(registration.relayDeviceId, registration.authToken))
        }

    @Test
    fun issuesChallengeAndRegistersSignedEd25519Identity() =
        testApplication {
            val now = 1_000L
            val identity = RelayIdentityTestFixtures.identity()
            val registry = RelayIdentityTestFixtures.registry(clock = { now })
            application {
                relayServerModule(
                    registry = registry,
                    turnCredentialIssuer = null,
                    identityPolicy = RelayIdentityTestFixtures.policy,
                )
            }

            val challengeResponse =
                client.post("/v3/devices/challenge") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        relayJson.encodeToString(
                            RelayDeviceChallengeRequest(identity = identity.identity),
                        ),
                    )
                }
            val challenge = relayJson.decodeFromString<RelayDeviceChallengeResponse>(challengeResponse.bodyAsText())
            val registrationRequest =
                RelayIdentityTestFixtures.registrationRequest(
                    identity = identity,
                    nonce = challenge.nonce,
                    timestampEpochMillis = now,
                )

            val registrationResponse =
                client.post("/v3/devices/register") {
                    contentType(ContentType.Application.Json)
                    setBody(relayJson.encodeToString(registrationRequest))
                }
            val registration = relayJson.decodeFromString<RegisterRelayDeviceV2Response>(registrationResponse.bodyAsText())

            assertEquals(HttpStatusCode.OK, challengeResponse.status)
            assertEquals(HttpStatusCode.OK, registrationResponse.status)
            assertTrue(registration.relayDeviceId.value.startsWith("relay-"))
            assertTrue(registration.identity.matches(identity.identity))
            assertTrue(registry.authenticate(registration.relayDeviceId, registration.authToken))
        }
}
