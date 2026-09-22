package dev.aegis.remote.core.session

import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.AegisLocalPorts
import dev.aegis.remote.core.model.AuthMethod
import dev.aegis.remote.core.model.ConnectionRoute
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.RouteDiagnostics
import dev.aegis.remote.core.model.RouteEvidence
import dev.aegis.remote.core.model.RouteHealth
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VisualReconnectionPolicyTest {
    @Test
    fun retriesSameRouteWhenItIsStillTheOnlyAvailableRoute() =
        runTest {
            val failedRoute = ConnectionRoute.LanRoute(HostAddress("192.168.1.10", 22))

            val decision =
                policy().decide(
                    context(
                        failedRoute = failedRoute,
                        health = listOf(RouteHealth(ConnectionRouteType.Lan, available = true)),
                        attempt = 1,
                    ),
                )

            val retry = assertIs<VisualReconnectionDecision.RetrySameRoute>(decision)
            assertEquals(ConnectionRouteType.Lan, retry.route.type)
            assertEquals(200, retry.delayMillis)
        }

    @Test
    fun switchesToNextFallbackWhenFailedRouteIsUnavailable() =
        runTest {
            val decision =
                policy().decide(
                    context(
                        failedRoute = ConnectionRoute.LanRoute(HostAddress("192.168.1.10", 22)),
                        health =
                            listOf(
                                RouteHealth(ConnectionRouteType.Lan, available = false, reason = "timeout"),
                                RouteHealth(ConnectionRouteType.Vpn, available = true),
                                RouteHealth(ConnectionRouteType.TurnRelay, available = true),
                            ),
                        attempt = 0,
                    ),
                )

            val switch = assertIs<VisualReconnectionDecision.SwitchRoute>(decision)
            assertEquals(ConnectionRouteType.Vpn, switch.route.type)
            assertEquals(100, switch.delayMillis)
            assertEquals("Switching from Lan to Vpn", switch.reason)
        }

    @Test
    fun fallsForwardToTurnWhenLanVpnAndStunAreNotAvailable() =
        runTest {
            val decision =
                policy().decide(
                    context(
                        failedRoute = ConnectionRoute.StunDirectRoute(RelayDeviceId("pc-relay")),
                        health =
                            listOf(
                                RouteHealth(ConnectionRouteType.Lan, available = false),
                                RouteHealth(ConnectionRouteType.Vpn, available = false),
                                RouteHealth(ConnectionRouteType.StunDirect, available = false),
                                RouteHealth(ConnectionRouteType.TurnRelay, available = true),
                                RouteHealth(ConnectionRouteType.ReverseRelay, available = true),
                            ),
                        attempt = 1,
                    ),
                )

            val switch = assertIs<VisualReconnectionDecision.SwitchRoute>(decision)
            assertEquals(ConnectionRouteType.TurnRelay, switch.route.type)
        }

    @Test
    fun givesUpWhenAttemptsAreExhausted() =
        runTest {
            val decision =
                policy(maxAttempts = 2).decide(
                    context(
                        failedRoute = ConnectionRoute.TurnRelayRoute(RelayDeviceId("pc-relay")),
                        health = listOf(RouteHealth(ConnectionRouteType.TurnRelay, available = true)),
                        attempt = 2,
                    ),
                )

            val giveUp = assertIs<VisualReconnectionDecision.GiveUp>(decision)
            assertEquals("Visual reconnection attempts exhausted", giveUp.reason)
        }

    @Test
    fun waitsOfflineWithoutGivingUp() =
        runTest {
            val decision =
                policy(maxAttempts = 1).decide(
                    context(
                        failedRoute = ConnectionRoute.LanRoute(HostAddress("192.168.1.10", 48291)),
                        health = listOf(RouteHealth(ConnectionRouteType.Lan, available = true)),
                        attempt = 0,
                    ).copy(networkOnline = false),
                )

            assertIs<VisualReconnectionDecision.WaitOffline>(decision)
        }

    @Test
    fun protocolSkewStopsTheRetryLoop() =
        runTest {
            val decision =
                policy().decide(
                    context(
                        failedRoute = ConnectionRoute.TurnRelayRoute(RelayDeviceId("pc-relay")),
                        health = listOf(RouteHealth(ConnectionRouteType.TurnRelay, available = true)),
                    ).copy(failureKind = ConnectionFailureKind.ProtocolSkew),
                )

            val giveUp = assertIs<VisualReconnectionDecision.GiveUp>(decision)
            assertEquals(AegisFailureCodes.SESSION_PROTOCOL_SKEW, giveUp.code)
        }

    @Test
    fun givesUpWhenNoVisualRouteIsAvailable() =
        runTest {
            val decision =
                policy().decide(
                    context(
                        failedRoute = ConnectionRoute.ReverseRelayRoute(RelayDeviceId("pc-relay")),
                        health =
                            listOf(
                                RouteHealth(ConnectionRouteType.Lan, available = false),
                                RouteHealth(ConnectionRouteType.TurnRelay, available = false),
                                RouteHealth(ConnectionRouteType.ReverseRelay, available = false),
                            ),
                    ),
                )

            val giveUp = assertIs<VisualReconnectionDecision.GiveUp>(decision)
            assertEquals("No route is currently available for visual reconnection", giveUp.reason)
        }

    @Test
    fun transientFailuresKeepRetryingTheSameRouteInsteadOfGivingUp() =
        runTest {
            val decision =
                policy(maxAttempts = Int.MAX_VALUE).decide(
                    context(
                        failedRoute = ConnectionRoute.LanRoute(HostAddress("192.168.1.10", 22)),
                        health = listOf(RouteHealth(ConnectionRouteType.Lan, available = true)),
                        attempt = 40,
                    ),
                )

            assertIs<VisualReconnectionDecision.RetrySameRoute>(decision)
        }

    @Test
    fun switchesWhenSelectedPairProvesCurrentTypeImpossible() =
        runTest {
            val decision =
                policy().decide(
                    context(
                        failedRoute = ConnectionRoute.LanRoute(HostAddress("192.168.1.10", 48291)),
                        health =
                            listOf(
                                RouteHealth(
                                    ConnectionRouteType.Lan,
                                    available = true,
                                    evidence = RouteEvidence.UnprovenHint,
                                ),
                                RouteHealth(
                                    ConnectionRouteType.StunDirect,
                                    available = true,
                                    evidence = RouteEvidence.SelectedIcePair,
                                ),
                            ),
                    ).copy(
                        selectedPairProvesCurrentTypeImpossible = true,
                        lastSelectedPairType = ConnectionRouteType.StunDirect,
                    ),
                )

            val switch = assertIs<VisualReconnectionDecision.SwitchRoute>(decision)
            assertEquals(ConnectionRouteType.StunDirect, switch.route.type)
        }

    @Test
    fun lanCandidateUsesVisualProtocolPortNotSsh() =
        runTest {
            val routes = profile().visualCandidateRoutes()
            val lan = assertIs<ConnectionRoute.LanRoute>(routes.first())
            assertEquals(AegisLocalPorts.VISUAL_PROTOCOL, lan.host.port)
        }

    private fun policy(maxAttempts: Int = 4) =
        VisualReconnectionPolicy(
            reconnectionManager =
                ExponentialBackoffReconnectionManager(
                    baseDelayMillis = 100,
                    maxDelayMillis = 1_000,
                    maxAttempts = maxAttempts,
                ),
        )

    private fun context(
        failedRoute: ConnectionRoute,
        health: List<RouteHealth>,
        attempt: Int = 0,
    ) = VisualReconnectionDecisionContext(
        profile = profile(),
        failedRoute = failedRoute,
        diagnostics =
            RouteDiagnostics(
                testedAtEpochMillis = 1_000,
                health = health,
                selectedRoute = health.firstOrNull { it.available }?.routeType,
                failureReason = "No route is currently available for visual reconnection",
            ),
        attempt = attempt,
        lastFailureEpochMillis = 1_000,
    )

    private fun profile() =
        DeviceProfile(
            id = DeviceProfileId("profile-1"),
            displayName = "PC",
            localHost = HostAddress("192.168.1.10"),
            vpnHost = HostAddress("100.64.0.2"),
            relayDeviceId = RelayDeviceId("pc-relay"),
            sshPort = 22,
            username = "ian",
            authMethod = AuthMethod.Password,
            permissions = DevicePermissions(visual = true, input = true),
            remoteAccessEnabled = true,
        )
}
