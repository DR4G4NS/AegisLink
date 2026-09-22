package dev.aegis.remote.android.routing

import dev.aegis.remote.core.model.ConnectionRoute
import dev.aegis.remote.core.model.NatTraversalState
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.RouteEvidence
import dev.aegis.remote.core.model.RouteHealth
import dev.aegis.remote.core.nat.StunProbe
import dev.aegis.remote.core.nat.ValidatedIceRoute
import dev.aegis.remote.core.nat.routeHealthFromSelectedPair
import dev.aegis.remote.core.relay.RelayClient
import dev.aegis.remote.core.routing.RouteHealthChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class AndroidRouteHealthChecker(
    private val timeoutMillis: Int = 1_200,
    private val localProtocolPort: Int = 48_291,
    private val relayClientProvider: () -> RelayClient? = { null },
    private val isRelayRegistered: () -> Boolean = { false },
    private val hasApprovedRelaySession: (RelayDeviceId) -> Boolean = { false },
    private val stunUrls: () -> List<String> = { DEFAULT_ANDROID_STUN_URLS },
    private val stunProbe: StunProbe = UdpStunProbe(timeoutMillis),
    private val selectedIceRoute: () -> ValidatedIceRoute? = { null },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RouteHealthChecker {
    override suspend fun check(route: ConnectionRoute): RouteHealth =
        when (route) {
            is ConnectionRoute.LanRoute -> checkTcpRoute(route, "LAN")
            is ConnectionRoute.VpnRoute -> checkTcpRoute(route, "VPN")
            is ConnectionRoute.StunDirectRoute -> checkStunDirectRoute(route)
            is ConnectionRoute.TurnRelayRoute -> checkTurnRelayRoute(route)
            is ConnectionRoute.ReverseRelayRoute -> checkReverseRelayRoute(route)
            is ConnectionRoute.ManualSshRoute -> checkTcpRoute(route, "manual SSH")
        }

    private suspend fun checkTcpRoute(
        route: ConnectionRoute,
        label: String,
    ): RouteHealth =
        withContext(Dispatchers.IO) {
            val host =
                when (route) {
                    is ConnectionRoute.LanRoute -> route.host
                    is ConnectionRoute.VpnRoute -> route.host
                    is ConnectionRoute.ManualSshRoute -> route.host
                    else -> error("Unsupported TCP route")
                }
            val port = host.port ?: defaultTcpPort(route)
            val started = System.currentTimeMillis()
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host.host, port), timeoutMillis)
                }
                RouteHealth(
                    routeType = route.type,
                    available = true,
                    latencyMs = System.currentTimeMillis() - started,
                    reason = "$label TCP $port reachable",
                    evidence = RouteEvidence.UnprovenHint,
                    hint = true,
                )
            } catch (error: Exception) {
                RouteHealth(
                    routeType = route.type,
                    available = false,
                    latencyMs = System.currentTimeMillis() - started,
                    reason = "$label TCP $port failed: ${error.javaClass.simpleName}",
                    evidence = RouteEvidence.Unavailable,
                    hint = false,
                )
            }
        }

    private fun defaultTcpPort(route: ConnectionRoute): Int =
        when (route) {
            is ConnectionRoute.LanRoute,
            is ConnectionRoute.VpnRoute,
            -> localProtocolPort

            is ConnectionRoute.ManualSshRoute -> 22

            else -> error("Unsupported TCP route")
        }

    private suspend fun checkStunDirectRoute(route: ConnectionRoute.StunDirectRoute): RouteHealth {
        val configuredUrls = stunUrls()
        if (configuredUrls.isEmpty()) {
            return RouteHealth(
                routeType = route.type,
                available = false,
                reason = "No STUN servers are configured",
                evidence = RouteEvidence.Unavailable,
                hint = false,
            )
        }
        val started = clock()
        return try {
            val result = stunProbe.check(configuredUrls)
            if (result.state != NatTraversalState.DirectPossible) {
                return RouteHealth(
                    routeType = route.type,
                    available = false,
                    latencyMs = clock() - started,
                    reason = result.reason ?: result.state.toString(),
                    evidence = RouteEvidence.Unavailable,
                    hint = false,
                )
            }
            routeHealthFromSelectedPair(
                expectedType = route.type,
                selectedRoute = selectedIceRoute(),
                reasonWhenMissing = "STUN binding is a hint, not a selected ICE pair",
            ).copy(latencyMs = clock() - started)
        } catch (error: Exception) {
            RouteHealth(
                routeType = route.type,
                available = false,
                latencyMs = clock() - started,
                reason = "STUN probe failed: ${error.javaClass.simpleName}",
                evidence = RouteEvidence.Unavailable,
                hint = false,
            )
        }
    }

    private suspend fun checkTurnRelayRoute(route: ConnectionRoute.TurnRelayRoute): RouteHealth {
        if (!isRelayRegistered()) {
            return RouteHealth(
                routeType = route.type,
                available = false,
                reason = "Register this phone with the relay before requesting TURN credentials",
                evidence = RouteEvidence.Unavailable,
                hint = false,
            )
        }
        val client =
            relayClientProvider()
                ?: return RouteHealth(
                    routeType = route.type,
                    available = false,
                    reason = "Relay client is not connected",
                    evidence = RouteEvidence.Unavailable,
                    hint = false,
                )

        val started = clock()
        return try {
            val credentials = client.requestTurnCredentials()
            val usable = credentials.urls.isNotEmpty() && credentials.expiresAtEpochMillis > clock()
            if (!usable) {
                return RouteHealth(
                    routeType = route.type,
                    available = false,
                    latencyMs = clock() - started,
                    reason = "Relay returned expired or empty TURN credentials",
                    evidence = RouteEvidence.Unavailable,
                    hint = false,
                )
            }
            routeHealthFromSelectedPair(
                expectedType = route.type,
                selectedRoute = selectedIceRoute(),
                reasonWhenMissing = "TURN credentials are a hint, not a selected ICE pair",
            ).copy(latencyMs = clock() - started)
        } catch (error: Exception) {
            RouteHealth(
                routeType = route.type,
                available = false,
                latencyMs = clock() - started,
                reason = "TURN credential request failed: ${error.javaClass.simpleName}",
                evidence = RouteEvidence.Unavailable,
                hint = false,
            )
        }
    }

    private fun checkReverseRelayRoute(route: ConnectionRoute.ReverseRelayRoute): RouteHealth {
        if (!isRelayRegistered()) {
            return RouteHealth(
                routeType = route.type,
                available = false,
                reason = "Register this phone with the relay before testing reverse relay",
                evidence = RouteEvidence.Unavailable,
                hint = false,
            )
        }
        relayClientProvider()
            ?: return RouteHealth(
                routeType = route.type,
                available = false,
                reason = "Relay client is not connected",
                evidence = RouteEvidence.Unavailable,
                hint = false,
            )
        val approved = hasApprovedRelaySession(route.relayDeviceId)
        return if (!approved) {
            RouteHealth(
                routeType = route.type,
                available = false,
                reason = "Create a relay session and wait for PC approval before using reverse relay",
                evidence = RouteEvidence.Unavailable,
                hint = false,
            )
        } else {
            RouteHealth(
                routeType = route.type,
                available = false,
                reason = "Approved relay session is a hint, not a selected ICE pair",
                evidence = RouteEvidence.UnprovenHint,
                hint = true,
            )
        }
    }
}
