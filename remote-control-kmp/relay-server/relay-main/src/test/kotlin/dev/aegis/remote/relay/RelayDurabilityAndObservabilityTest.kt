package dev.aegis.remote.relay

import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayDurabilityAndObservabilityTest {
    @Test
    fun inMemoryRuntimeMailboxesAreBoundedBufferedAndInvalidatable() =
        runTest {
            var now = 1_000L
            val bus = InMemoryRelayRuntimeMessageBus { now }
            val sessionId = SessionId("session-buffered")
            val recipient = RelayDeviceId("relay-target")

            assertTrue(bus.enqueueSessionFrame(sessionId, recipient, "opaque-1", 100, 1))
            assertFalse(bus.enqueueSessionFrame(sessionId, recipient, "opaque-2", 100, 1))
            assertEquals(listOf("opaque-1"), bus.takeSessionFrames(sessionId, recipient, 8))
            assertTrue(bus.enqueueSessionFrame(sessionId, recipient, "opaque-expired", 1, 1))
            now += 2
            assertTrue(bus.takeSessionFrames(sessionId, recipient, 8).isEmpty())

            val before = bus.deviceInvalidation(recipient)
            val invalidation = bus.invalidateDevice(recipient, "REL-7405 token rotated", 100)
            assertNull(before)
            assertEquals(invalidation, bus.deviceInvalidation(recipient))
            bus.invalidateSession(sessionId, "SES-7401 invalidated", 100)
            assertEquals("SES-7401 invalidated", bus.sessionInvalidation(sessionId)?.reason)
        }

    @Test
    fun participantLeaseRejectsDuplicateReplicaAndExpiresFailClosed() =
        runTest {
            var now = 1_000L
            val bus = InMemoryRelayRuntimeMessageBus { now }
            val sessionId = SessionId("session-participant-lease")
            val participant = RelayDeviceId("relay-participant")

            assertTrue(bus.claimSessionParticipant(sessionId, participant, "lease-a", 100L))
            assertTrue(bus.claimSessionParticipant(sessionId, participant, "lease-a", 100L))
            assertFalse(bus.claimSessionParticipant(sessionId, participant, "lease-b", 100L))
            assertTrue(bus.ownsSessionParticipant(sessionId, participant, "lease-a"))
            assertFalse(bus.renewSessionParticipant(sessionId, participant, "lease-b", 100L))

            bus.releaseSessionParticipant(sessionId, participant, "lease-b")
            assertTrue(bus.ownsSessionParticipant(sessionId, participant, "lease-a"))
            assertTrue(bus.renewSessionParticipant(sessionId, participant, "lease-a", 100L))
            now += 101L
            assertFalse(bus.ownsSessionParticipant(sessionId, participant, "lease-a"))
            assertTrue(bus.claimSessionParticipant(sessionId, participant, "lease-b", 100L))

            assertTrue(bus.claimDeviceEventSubscriber(participant, "subscriber-a", 100L))
            assertFalse(bus.claimDeviceEventSubscriber(participant, "subscriber-b", 100L))
            assertTrue(bus.ownsDeviceEventSubscriber(participant, "subscriber-a"))
            bus.releaseDeviceEventSubscriber(participant, "subscriber-b")
            assertTrue(bus.renewDeviceEventSubscriber(participant, "subscriber-a", 100L))
            bus.releaseDeviceEventSubscriber(participant, "subscriber-a")
            assertFalse(bus.ownsDeviceEventSubscriber(participant, "subscriber-a"))
        }

    @Test
    fun inMemoryChallengeStoreConsumesNonceExactlyOnceAndExpiresOldEntries() {
        var now = 1_000L
        val store = InMemoryRelayRegistrationChallengeStore { now }
        val identity = RelayIdentityTestFixtures.identity().identity
        val first = PendingRelayRegistrationChallenge("nonce", identity, now, now + 100, "relay")

        assertTrue(store.put(first, 100))
        assertFalse(store.put(first, 100))
        assertNotNull(store.take("nonce"))
        assertNull(store.take("nonce"))

        val expired = first.copy(nonce = "expired", expiresAtEpochMillis = now + 1)
        assertTrue(store.put(expired, 1))
        now += 2
        assertTrue(store.put(expired.copy(expiresAtEpochMillis = now + 1), 1))
    }

    @Test
    fun exposesLivenessReadinessAndPrometheusMetrics() =
        testApplication {
            application {
                relayServerModule(
                    registry = RelayIdentityTestFixtures.registry(clock = { 1_000L }),
                    turnCredentialIssuer = null,
                    runtimeConfig = RelayServerRuntimeConfig(readinessCheck = { false }),
                )
            }

            assertEquals(HttpStatusCode.OK, client.get("/live").status)
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/ready").status)
            val metrics = client.get("/metrics")
            assertEquals(HttpStatusCode.OK, metrics.status)
            assertTrue(metrics.bodyAsText().contains("aegis_relay_websocket_connections 0"))
        }

    @Test
    fun connectionLimiterNeverExceedsConfiguredMaximum() {
        val limiter = RelayConnectionLimiter(maximum = 2)
        assertTrue(limiter.tryOpen())
        assertTrue(limiter.tryOpen())
        assertFalse(limiter.tryOpen())
        assertEquals(2, limiter.active())
        limiter.close()
        assertTrue(limiter.tryOpen())
        assertEquals(2, limiter.active())
    }
}
