package dev.aegis.remote.core.session

import dev.aegis.remote.core.model.AEGIS_P256_AESGCM_V1
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.DeviceId
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.FailureCategory
import dev.aegis.remote.core.model.SessionId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RemoteSessionRegistryTest {
    private var now = 10_000L
    private val local = DeviceId("desktop")
    private val registry =
        RemoteSessionRegistry(
            RemoteSessionPolicy(local, "relay.example", setOf(AEGIS_P256_AESGCM_V1.id), AEGIS_P256_AESGCM_V1.id, maxActiveSessions = 4),
            clock = { now },
        )

    @Test
    fun acceptsFullLifecycleAndRejectsReplayedEvents() =
        runTest {
            val admitted = registry.admit(admission())
            assertEquals(RemoteSessionState.Requested, admitted.state)
            val states =
                listOf(
                    RemoteSessionState.AwaitingApproval,
                    RemoteSessionState.Approved,
                    RemoteSessionState.EstablishingE2ee,
                    RemoteSessionState.OpeningSignaling,
                    RemoteSessionState.NegotiatingIce,
                    RemoteSessionState.Connected,
                    RemoteSessionState.Closing,
                    RemoteSessionState.Closed,
                )
            states.forEachIndexed { index, state -> registry.transition(SessionId("s1"), 1, (index + 1).toULong(), state) }
            assertFails("SESSION_EVENT_REPLAY") { registry.transition(SessionId("s1"), 1, 8u, RemoteSessionState.Closed) }
            assertTrue(registry.removeTerminal(SessionId("s1")))
            assertFails("SESSION_REPLAY") { registry.admit(admission()) }
        }

    @Test
    fun rejectsWrongTargetOriginSuiteGenerationAndExpiredAdmission() =
        runTest {
            assertFails("WRONG_LOCAL_DEVICE") { registry.admit(admission(target = DeviceId("other"), source = DeviceId("peer2"))) }
            val relayMismatch = assertFails("WRONG_RELAY_ORIGIN") { registry.admit(admission(id = "s2", origin = "evil")) }
            assertEquals(FailureCategory.AUTHENTICATION, relayMismatch.failure.category)
            assertEquals("relay.example", relayMismatch.failure.expected)
            assertEquals("evil", relayMismatch.failure.actual)
            assertFails("CRYPTO_SUITE_NOT_ALLOWED") { registry.admit(admission(id = "s3", suite = "legacy")) }
            assertFails("SESSION_EXPIRED") { registry.admit(admission(id = "s4", expires = now)) }
            registry.admit(admission(id = "s5"))
            assertFails("STALE_SESSION_GENERATION") {
                registry.transition(SessionId("s5"), 2, 1u, RemoteSessionState.AwaitingApproval)
            }
        }

    @Test
    fun expiryAndRevocationFailClosedWithoutAffectingOtherProfiles() =
        runTest {
            registry.admit(admission(id = "a", profile = "profile-a", source = DeviceId("phone-a"), expires = now + 5))
            registry.admit(admission(id = "b", profile = "profile-b", source = DeviceId("phone-b"), expires = now + 50))
            now += 6
            val expired = registry.expireDueSessions()
            assertEquals(listOf("a"), expired.map { it.admission.sessionId.value })
            val revoked = registry.revokeDevice(DeviceId("phone-b"))
            val failed = assertIs<RemoteSessionState.Failed>(revoked.single().state)
            assertEquals(AegisFailureCodes.IDENTITY_DEVICE_REVOKED, failed.failure.code)
            assertEquals(FailureCategory.AUTHORIZATION, failed.failure.category)
            assertEquals("b", failed.failure.correlationId)
            assertNotNull(failed.failure.technicalCause)
            assertEquals(2, registry.snapshot.value.size)
            assertNotNull(registry.get(SessionId("a")))
        }

    @Test
    fun invalidTransitionAndCapacityAreRejected() =
        runTest {
            registry.admit(admission(id = "s1"))
            assertFails("INVALID_SESSION_TRANSITION") {
                registry.transition(SessionId("s1"), 1, 1u, RemoteSessionState.Connected)
            }
            registry.admit(admission(id = "s2", source = DeviceId("p2")))
            registry.admit(admission(id = "s3", source = DeviceId("p3")))
            registry.admit(admission(id = "s4", source = DeviceId("p4")))
            assertFails("SESSION_CAPACITY_EXCEEDED") { registry.admit(admission(id = "s5", source = DeviceId("p5"))) }
        }

    @Test
    fun bindsLegacyFailedStateToTheActiveSessionCorrelation() =
        runTest {
            registry.admit(admission())

            val failed =
                registry.transition(
                    SessionId("s1"),
                    generation = 1,
                    eventSequence = 1u,
                    next = RemoteSessionState.Failed("SESSION_ESTABLISHMENT_FAILED"),
                )

            val state = assertIs<RemoteSessionState.Failed>(failed.state)
            assertEquals(AegisFailureCodes.SESSION_PROTOCOL_PROCESSING_FAILED, state.failure.code)
            assertEquals(FailureCategory.CAUSE_UNCONFIRMED, state.failure.category)
            assertEquals("s1", state.failure.correlationId)
        }

    @Test
    fun nativeIceRestartReturnsThroughNegotiatingIceWithoutClaimingANewE2eeHandshake() =
        runTest {
            registry.admit(admission())
            val initial =
                listOf(
                    RemoteSessionState.AwaitingApproval,
                    RemoteSessionState.Approved,
                    RemoteSessionState.EstablishingE2ee,
                    RemoteSessionState.OpeningSignaling,
                    RemoteSessionState.NegotiatingIce,
                    RemoteSessionState.Connected,
                    RemoteSessionState.Reconnecting,
                    RemoteSessionState.NegotiatingIce,
                    RemoteSessionState.Connected,
                )

            initial.forEachIndexed { index, state ->
                registry.transition(SessionId("s1"), 1, (index + 1).toULong(), state)
            }

            assertEquals(RemoteSessionState.Connected, registry.get(SessionId("s1"))?.state)
        }

    private fun admission(
        id: String = "s1",
        profile: String = "profile",
        source: DeviceId = DeviceId("phone"),
        target: DeviceId = local,
        origin: String = "relay.example",
        suite: String = AEGIS_P256_AESGCM_V1.id,
        expires: Long = now + 1_000,
    ) = RemoteSessionAdmission(
        SessionId(id),
        DeviceProfileId(profile),
        source,
        target,
        1,
        1,
        suite,
        origin,
        now - 1,
        expires,
        1,
    )

    private suspend fun assertFails(
        code: String,
        block: suspend () -> Unit,
    ): RemoteSessionRejectedException {
        val error = assertFailsWith<RemoteSessionRejectedException> { block() }
        assertEquals(code, error.code)
        assertEquals(stableCodeFor(code), error.failure.code)
        assertEquals("remote-session-registry", error.failure.component)
        assertTrue(error.failure.correlationId.isNotBlank())
        assertNotNull(error.failure.technicalCause)
        assertNotNull(error.failure.nextAction)
        return error
    }

    private fun stableCodeFor(code: String): String =
        when (code) {
            "SESSION_NOT_FOUND" -> AegisFailureCodes.SESSION_NOT_FOUND
            "SESSION_REPLAY" -> AegisFailureCodes.SESSION_REPLAY
            "SESSION_CAPACITY_EXCEEDED" -> AegisFailureCodes.SESSION_CAPACITY_EXCEEDED
            "STALE_SESSION_GENERATION" -> AegisFailureCodes.SESSION_STALE_GENERATION
            "SESSION_EVENT_REPLAY" -> AegisFailureCodes.SESSION_EVENT_REPLAY
            "INVALID_SESSION_TRANSITION" -> AegisFailureCodes.SESSION_INVALID_TRANSITION
            "SESSION_NOT_TERMINAL" -> AegisFailureCodes.SESSION_NOT_TERMINAL
            "WRONG_LOCAL_DEVICE" -> AegisFailureCodes.SESSION_WRONG_LOCAL_DEVICE
            "WRONG_RELAY_ORIGIN" -> AegisFailureCodes.RELAY_ORIGIN_MISMATCH
            "CRYPTO_SUITE_NOT_ALLOWED" -> AegisFailureCodes.E2EE_CRYPTO_SUITE_NOT_ALLOWED
            "CRYPTO_SUITE_DOWNGRADE" -> AegisFailureCodes.E2EE_DOWNGRADE_DETECTED
            "DEVICE_REVOKED" -> AegisFailureCodes.IDENTITY_DEVICE_REVOKED
            "SESSION_EXPIRED" -> AegisFailureCodes.SESSION_EXPIRED
            "SESSION_NOT_YET_VALID" -> AegisFailureCodes.SESSION_NOT_YET_VALID
            else -> error("Missing stable-code assertion for $code")
        }
}
