package dev.aegis.remote.android.routing

import dev.aegis.remote.core.model.ConnectionRoute
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.model.NatTraversalState
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.nat.ConnectivityCheckResult
import dev.aegis.remote.core.nat.StunProbe
import dev.aegis.remote.core.relay.RelayAuthToken
import dev.aegis.remote.core.relay.RelayClient
import dev.aegis.remote.core.relay.RelayConnectionState
import dev.aegis.remote.core.relay.RelayDeviceEventChannel
import dev.aegis.remote.core.relay.RelayDeviceRegistration
import dev.aegis.remote.core.relay.RelayRegistration
import dev.aegis.remote.core.relay.RelaySession
import dev.aegis.remote.core.relay.RelaySessionApproval
import dev.aegis.remote.core.relay.RelaySignalingChannel
import dev.aegis.remote.core.relay.RelayTurnCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidRouteHealthCheckerTest {
    @Test
    fun lanRouteWithoutExplicitPortUsesLocalProtocolPort() =
        runTest {
            ServerSocket(0).use { server ->
                val checker =
                    AndroidRouteHealthChecker(
                        timeoutMillis = 500,
                        localProtocolPort = server.localPort,
                    )

                val health = checker.check(ConnectionRoute.LanRoute(HostAddress("127.0.0.1")))

                assertEquals(ConnectionRouteType.Lan, health.routeType)
                assertTrue(health.available)
                assertEquals("LAN TCP ${server.localPort} reachable", health.reason)
            }
        }

    @Test
    fun stunDirectRouteUsesConfiguredStunProbe() =
        runTest {
            val probe = RecordingStunProbe(ConnectivityCheckResult(NatTraversalState.DirectPossible, "srflx ok"))
            val checker =
                AndroidRouteHealthChecker(
                    stunUrls = { listOf("stun:stun.example.test:3478") },
                    stunProbe = probe,
                    clock = { 10L },
                )

            val health = checker.check(ConnectionRoute.StunDirectRoute(RelayDeviceId("pc-relay")))

            assertEquals(ConnectionRouteType.StunDirect, health.routeType)
            assertFalse(health.available)
            assertTrue(health.hint)
            assertEquals("STUN binding is a hint, not a selected ICE pair", health.reason)
            assertEquals(listOf("stun:stun.example.test:3478"), probe.urls)
        }

    @Test
    fun stunDirectRouteUsesSameDefaultBootstrapAsWebRtcPlans() =
        runTest {
            val probe = RecordingStunProbe(ConnectivityCheckResult(NatTraversalState.DirectPossible, "srflx ok"))
            val checker = AndroidRouteHealthChecker(stunProbe = probe, clock = { 10L })

            val health = checker.check(ConnectionRoute.StunDirectRoute(RelayDeviceId("pc-relay")))

            assertFalse(health.available)
            assertTrue(health.hint)
            assertEquals(DEFAULT_ANDROID_STUN_URLS, probe.urls)
        }

    @Test
    fun stunDirectRouteIsAvailableOnlyAfterSelectedIcePair() =
        runTest {
            val probe = RecordingStunProbe(ConnectivityCheckResult(NatTraversalState.DirectPossible, "srflx ok"))
            val checker =
                AndroidRouteHealthChecker(
                    stunUrls = { listOf("stun:stun.example.test:3478") },
                    stunProbe = probe,
                    selectedIceRoute = { dev.aegis.remote.core.nat.ValidatedIceRoute.InternetDirect },
                    clock = { 10L },
                )

            val health = checker.check(ConnectionRoute.StunDirectRoute(RelayDeviceId("pc-relay")))

            assertTrue(health.available)
            assertTrue(health.isProvenAvailable())
        }

    @Test
    fun stunDirectRouteReportsUnavailableWhenProbeRequiresTurn() =
        runTest {
            val checker =
                AndroidRouteHealthChecker(
                    stunUrls = { listOf("stun:stun.example.test:3478") },
                    stunProbe = RecordingStunProbe(ConnectivityCheckResult(NatTraversalState.TurnRequired, "udp blocked")),
                    clock = { 10L },
                )

            val health = checker.check(ConnectionRoute.StunDirectRoute(RelayDeviceId("pc-relay")))

            assertFalse(health.available)
            assertEquals("udp blocked", health.reason)
        }

    @Test
    fun stunDirectRouteRequiresConfiguredUrls() =
        runTest {
            val checker = AndroidRouteHealthChecker(stunUrls = { emptyList() })

            val health = checker.check(ConnectionRoute.StunDirectRoute(RelayDeviceId("pc-relay")))

            assertFalse(health.available)
            assertEquals("No STUN servers are configured", health.reason)
        }

    @Test
    fun reverseRelayRequiresApprovedRelaySession() =
        runTest {
            val checker =
                AndroidRouteHealthChecker(
                    relayClientProvider = { FakeRelayClient() },
                    isRelayRegistered = { true },
                    hasApprovedRelaySession = { relayDeviceId -> relayDeviceId.value == "pc-approved" },
                )

            val pending = checker.check(ConnectionRoute.ReverseRelayRoute(RelayDeviceId("pc-pending")))
            val approved = checker.check(ConnectionRoute.ReverseRelayRoute(RelayDeviceId("pc-approved")))

            assertFalse(pending.available)
            assertEquals("Create a relay session and wait for PC approval before using reverse relay", pending.reason)
            assertFalse(approved.available)
            assertTrue(approved.hint)
            assertEquals("Approved relay session is a hint, not a selected ICE pair", approved.reason)
        }

    @Test
    fun turnRelayRouteIsNotAvailableFromCredentialIssuanceAlone() =
        runTest {
            val checker =
                AndroidRouteHealthChecker(
                    relayClientProvider = { FakeRelayClient() },
                    isRelayRegistered = { true },
                    clock = { 10L },
                )

            val health = checker.check(ConnectionRoute.TurnRelayRoute(RelayDeviceId("pc-relay")))

            assertFalse(health.available)
            assertTrue(health.hint)
            assertEquals("TURN credentials are a hint, not a selected ICE pair", health.reason)
        }

    @Test
    fun reverseRelayRequiresRegisteredRelayClient() =
        runTest {
            val checker =
                AndroidRouteHealthChecker(
                    relayClientProvider = { FakeRelayClient() },
                    isRelayRegistered = { false },
                    hasApprovedRelaySession = { true },
                )

            val health = checker.check(ConnectionRoute.ReverseRelayRoute(RelayDeviceId("pc-approved")))

            assertFalse(health.available)
            assertEquals("Register this phone with the relay before testing reverse relay", health.reason)
        }
}

private class RecordingStunProbe(
    private val result: ConnectivityCheckResult,
) : StunProbe {
    var urls: List<String> = emptyList()

    override suspend fun check(stunUrls: List<String>): ConnectivityCheckResult {
        urls = stunUrls
        return result
    }
}

private class FakeRelayClient : RelayClient {
    override val states: Flow<RelayConnectionState> = MutableStateFlow(RelayConnectionState.Connected)

    override suspend fun connect() = Unit

    override suspend fun registerDevice(registration: RelayDeviceRegistration): RelayRegistration = RelayRegistration(RelayDeviceId("android"), RelayAuthToken("token", 1_000L))

    override suspend fun createSession(targetRelayDeviceId: RelayDeviceId): RelaySession = RelaySession(SessionId("session"), targetRelayDeviceId, 1_000L)

    override suspend fun approveSession(
        sessionId: SessionId,
        approved: Boolean,
    ): RelaySessionApproval =
        RelaySessionApproval(
            sessionId = sessionId,
            sourceRelayDeviceId = RelayDeviceId("android"),
            targetRelayDeviceId = RelayDeviceId("pc"),
            approved = approved,
            decidedAtEpochMillis = 1_000L,
        )

    override suspend fun requestTurnCredentials(): RelayTurnCredentials =
        RelayTurnCredentials(
            urls = listOf("turn:turn.example.test:3478"),
            username = "user",
            credential = "secret",
            expiresAtEpochMillis = 2_000L,
        )

    override suspend fun openSignalingChannel(sessionId: SessionId): RelaySignalingChannel {
        error("Signaling is not used in this test")
    }

    override suspend fun openDeviceEvents(): RelayDeviceEventChannel {
        error("Device events are not used in this test")
    }

    override suspend fun close() = Unit
}
