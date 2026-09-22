package dev.aegis.remote.core.routing

import dev.aegis.remote.core.model.AuthMethod
import dev.aegis.remote.core.model.ConnectionRoute
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.RouteEvidence
import dev.aegis.remote.core.model.RouteHealth
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BasicConnectionRouteManagerTest {
    @Test
    fun selectsLanBeforeVpnAndRelayRoutes() =
        runTest {
            val manager =
                BasicConnectionRouteManager(
                    healthChecker = RouteHealthChecker { route -> RouteHealth(route.type, available = true) },
                    clock = { 100L },
                )
            val profile = profile()

            val diagnostics = manager.detectAvailableRoutes(profile)
            val selected = manager.selectBestRoute(profile, diagnostics)

            assertEquals(ConnectionRouteType.Lan, diagnostics.selectedRoute)
            assertEquals(ConnectionRouteType.Lan, selected?.type)
        }

    @Test
    fun fallsBackToTurnWhenLanVpnAndStunAreUnavailable() =
        runTest {
            val manager =
                BasicConnectionRouteManager(
                    healthChecker =
                        RouteHealthChecker { route ->
                            RouteHealth(route.type, available = route is ConnectionRoute.TurnRelayRoute)
                        },
                    clock = { 100L },
                )

            val diagnostics = manager.detectAvailableRoutes(profile())
            val selected = manager.selectBestRoute(profile(), diagnostics)

            assertEquals(ConnectionRouteType.TurnRelay, selected?.type)
        }

    @Test
    fun stunHintWithoutSelectedPairIsStillAttemptable() =
        runTest {
            val manager =
                BasicConnectionRouteManager(
                    healthChecker =
                        RouteHealthChecker { route ->
                            when (route) {
                                is ConnectionRoute.StunDirectRoute -> {
                                    RouteHealth(
                                        route.type,
                                        available = false,
                                        evidence = RouteEvidence.UnprovenHint,
                                        hint = true,
                                    )
                                }

                                else -> {
                                    RouteHealth(route.type, available = false)
                                }
                            }
                        },
                    clock = { 100L },
                )

            val diagnostics = manager.detectAvailableRoutes(profile())
            val selected = manager.selectBestRoute(profile(), diagnostics)

            assertEquals(ConnectionRouteType.StunDirect, selected?.type)
            assertEquals(false, diagnostics.health.first { it.routeType == ConnectionRouteType.StunDirect }.available)
        }

    @Test
    fun keepsGenericLanAndVpnRoutesSeparateFromSshPort() =
        runTest {
            val checkedRoutes = mutableListOf<ConnectionRoute>()
            val manager =
                BasicConnectionRouteManager(
                    healthChecker =
                        RouteHealthChecker { route ->
                            checkedRoutes += route
                            RouteHealth(route.type, available = false)
                        },
                    clock = { 100L },
                )

            manager.detectAvailableRoutes(profile())

            val lan = assertIs<ConnectionRoute.LanRoute>(checkedRoutes[0])
            val vpn = assertIs<ConnectionRoute.VpnRoute>(checkedRoutes[1])
            assertEquals(HostAddress("192.168.1.10"), lan.host)
            assertEquals(HostAddress("100.64.0.2"), vpn.host)
        }

    private fun profile() =
        DeviceProfile(
            id = DeviceProfileId("pc-1"),
            displayName = "Workstation",
            localHost = HostAddress("192.168.1.10"),
            vpnHost = HostAddress("100.64.0.2"),
            relayDeviceId = RelayDeviceId("relay-pc-1"),
            username = "ian",
            authMethod = AuthMethod.Password,
            remoteAccessEnabled = true,
        )
}
