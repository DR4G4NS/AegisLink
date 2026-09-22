package dev.aegis.remote.core.routing

import dev.aegis.remote.core.model.ConnectionRoute
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.RouteDiagnostics
import dev.aegis.remote.core.model.RouteHealth

interface ConnectionRouteManager {
    suspend fun detectAvailableRoutes(profile: DeviceProfile): RouteDiagnostics

    suspend fun selectBestRoute(
        profile: DeviceProfile,
        diagnostics: RouteDiagnostics,
    ): ConnectionRoute?
}

fun interface RouteHealthChecker {
    suspend fun check(route: ConnectionRoute): RouteHealth
}

class BasicConnectionRouteManager(
    private val healthChecker: RouteHealthChecker,
    private val clock: () -> Long,
) : ConnectionRouteManager {
    override suspend fun detectAvailableRoutes(profile: DeviceProfile): RouteDiagnostics {
        val candidates = profile.candidateRoutes()
        val health = candidates.map { healthChecker.check(it) }
        val selected =
            health
                .filter { it.isProvenAvailable() }
                .minByOrNull { typePriority(it.routeType) }
                ?.routeType
                ?: health
                    .filter { it.isAttemptable() }
                    .minByOrNull { typePriority(it.routeType) }
                    ?.routeType

        return RouteDiagnostics(
            testedAtEpochMillis = clock(),
            health = health,
            selectedRoute = selected,
            failureReason = if (selected == null) "No connection route is currently available" else null,
        )
    }

    override suspend fun selectBestRoute(
        profile: DeviceProfile,
        diagnostics: RouteDiagnostics,
    ): ConnectionRoute? {
        val availableTypes =
            diagnostics.health
                .filter { it.isAttemptable() }
                .map { it.routeType }
                .toSet()
        return profile
            .candidateRoutes()
            .filter { it.type in availableTypes }
            .minByOrNull { it.priority }
    }

    private fun DeviceProfile.candidateRoutes(): List<ConnectionRoute> =
        buildList {
            add(ConnectionRoute.LanRoute(localHost))
            vpnHost?.let { add(ConnectionRoute.VpnRoute(it)) }
            relayDeviceId?.let {
                add(ConnectionRoute.StunDirectRoute(it))
                add(ConnectionRoute.TurnRelayRoute(it))
                if (remoteAccessEnabled) add(ConnectionRoute.ReverseRelayRoute(it))
            }
        }

    private fun typePriority(type: ConnectionRouteType): Int =
        when (type) {
            ConnectionRouteType.Lan -> 10
            ConnectionRouteType.Vpn -> 20
            ConnectionRouteType.StunDirect -> 30
            ConnectionRouteType.TurnRelay -> 40
            ConnectionRouteType.ReverseRelay -> 50
            ConnectionRouteType.ManualSsh -> 60
        }
}
