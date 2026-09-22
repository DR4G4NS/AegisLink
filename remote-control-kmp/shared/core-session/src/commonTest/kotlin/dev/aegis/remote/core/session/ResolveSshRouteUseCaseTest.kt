package dev.aegis.remote.core.session

import dev.aegis.remote.core.model.AuthMethod
import dev.aegis.remote.core.model.ConnectionRoute
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.RouteDiagnostics
import dev.aegis.remote.core.model.RouteHealth
import dev.aegis.remote.core.routing.ConnectionRouteManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ResolveSshRouteUseCaseTest {
    @Test
    fun prefersLanOverVpnWhenBothAreAvailable() =
        runTest {
            val useCase = ResolveSshRouteUseCase(NoOpRouteManager)

            val plan =
                useCase(
                    profile(vpnHost = HostAddress("100.64.0.10")),
                    diagnostics(
                        RouteHealth(ConnectionRouteType.Vpn, available = true),
                        RouteHealth(ConnectionRouteType.Lan, available = true),
                    ),
                )

            assertIs<ConnectionRoute.LanRoute>(plan.route)
            assertEquals(HostAddress("192.168.1.10", 2222), plan.host)
        }

    @Test
    fun usesVpnWhenLanIsUnavailable() =
        runTest {
            val useCase = ResolveSshRouteUseCase(NoOpRouteManager)

            val plan =
                useCase(
                    profile(vpnHost = HostAddress("100.64.0.10")),
                    diagnostics(
                        RouteHealth(ConnectionRouteType.Lan, available = false),
                        RouteHealth(ConnectionRouteType.Vpn, available = true),
                    ),
                )

            assertIs<ConnectionRoute.VpnRoute>(plan.route)
            assertEquals(HostAddress("100.64.0.10", 2222), plan.host)
        }

    @Test
    fun rejectsRemoteOnlyRoutesForSsh() =
        runTest {
            val useCase =
                ResolveSshRouteUseCase(
                    StaticRouteManager(ConnectionRoute.TurnRelayRoute(RelayDeviceId("pc-1"))),
                )

            val error =
                assertFailsWith<ResolveSshRouteException> {
                    useCase(
                        profile(),
                        diagnostics(RouteHealth(ConnectionRouteType.TurnRelay, available = true)),
                    )
                }

            assertIs<dev.aegis.remote.core.model.AppError.Network>(error.appError)
            assertEquals(
                "SSH requires a LAN or VPN route; available remote routes are reserved for WebRTC signaling",
                error.appError.message,
            )
        }

    @Test
    fun rejectsProfileWithoutTerminalOrSftpPermission() =
        runTest {
            val useCase = ResolveSshRouteUseCase(NoOpRouteManager)

            val error =
                assertFailsWith<ResolveSshRouteException> {
                    useCase(profile(permissions = DevicePermissions()))
                }

            assertEquals("SSH access is not permitted for this device", error.appError.message)
        }

    @Test
    fun keepsExplicitVpnPort() =
        runTest {
            val useCase = ResolveSshRouteUseCase(NoOpRouteManager)

            val plan =
                useCase(
                    profile(vpnHost = HostAddress("100.64.0.10", 48291)).copy(remoteSshPort = 2200),
                    diagnostics(RouteHealth(ConnectionRouteType.Vpn, available = true)),
                )

            assertEquals(HostAddress("100.64.0.10", 2200), plan.host)
        }

    @Test
    fun probesLanOnSshPortInsteadOfVisualProtocolPort() =
        runTest {
            val routeManager = CapturingRouteManager(ConnectionRouteType.Lan)
            val useCase = ResolveSshRouteUseCase(routeManager)

            val plan =
                useCase(
                    profile(localHost = HostAddress("192.168.1.10", 48_291)),
                )

            assertEquals(HostAddress("192.168.1.10", 2222), routeManager.probedProfile?.localHost)
            assertEquals(HostAddress("192.168.1.10", 2222), plan.host)
        }

    @Test
    fun probesVpnOnItsExplicitSshPort() =
        runTest {
            val routeManager = CapturingRouteManager(ConnectionRouteType.Vpn)
            val useCase = ResolveSshRouteUseCase(routeManager)

            val plan =
                useCase(
                    profile(
                        localHost = HostAddress("192.168.1.10", 48_291),
                        vpnHost = HostAddress("100.64.0.10", 48291),
                    ).copy(remoteSshPort = 2200),
                )

            assertEquals(HostAddress("100.64.0.10", 2200), routeManager.probedProfile?.vpnHost)
            assertEquals(HostAddress("100.64.0.10", 2200), plan.host)
        }

    @Test
    fun doesNotUsePairedVisualPortForRemoteSsh() =
        runTest {
            val routeManager = CapturingRouteManager(ConnectionRouteType.Vpn)
            val plan = ResolveSshRouteUseCase(routeManager)(profile(vpnHost = HostAddress("100.64.0.10", 48291)))
            assertEquals(HostAddress("100.64.0.10", 2222), plan.host)
            assertEquals(plan.host, routeManager.probedProfile?.vpnHost)
        }

    private fun profile(
        permissions: DevicePermissions = DevicePermissions(terminal = true, sftp = true),
        localHost: HostAddress = HostAddress("192.168.1.10"),
        vpnHost: HostAddress? = null,
    ) = DeviceProfile(
        id = DeviceProfileId("profile-1"),
        displayName = "PC",
        localHost = localHost,
        vpnHost = vpnHost,
        sshPort = 2222,
        username = "ian",
        authMethod = AuthMethod.Password,
        permissions = permissions,
    )

    private fun diagnostics(vararg health: RouteHealth) =
        RouteDiagnostics(
            testedAtEpochMillis = 1_000,
            health = health.toList(),
            selectedRoute = health.firstOrNull { it.available }?.routeType,
            failureReason = if (health.none { it.available }) "No route" else null,
        )
}

private class CapturingRouteManager(
    private val availableRoute: ConnectionRouteType,
) : ConnectionRouteManager {
    var probedProfile: DeviceProfile? = null
        private set

    override suspend fun detectAvailableRoutes(profile: DeviceProfile): RouteDiagnostics {
        probedProfile = profile
        return RouteDiagnostics(
            testedAtEpochMillis = 1_000,
            health = listOf(RouteHealth(availableRoute, available = true)),
            selectedRoute = availableRoute,
        )
    }

    override suspend fun selectBestRoute(
        profile: DeviceProfile,
        diagnostics: RouteDiagnostics,
    ): ConnectionRoute? =
        when (availableRoute) {
            ConnectionRouteType.Lan -> ConnectionRoute.LanRoute(profile.localHost)
            ConnectionRouteType.Vpn -> profile.vpnHost?.let(ConnectionRoute::VpnRoute)
            else -> null
        }
}

private object NoOpRouteManager : ConnectionRouteManager {
    override suspend fun detectAvailableRoutes(profile: DeviceProfile): RouteDiagnostics = RouteDiagnostics(testedAtEpochMillis = 1_000, health = emptyList(), failureReason = "No route")

    override suspend fun selectBestRoute(
        profile: DeviceProfile,
        diagnostics: RouteDiagnostics,
    ): ConnectionRoute? = null
}

private class StaticRouteManager(
    private val route: ConnectionRoute?,
) : ConnectionRouteManager {
    override suspend fun detectAvailableRoutes(profile: DeviceProfile): RouteDiagnostics =
        RouteDiagnostics(
            testedAtEpochMillis = 1_000,
            health = route?.let { listOf(RouteHealth(it.type, available = true)) }.orEmpty(),
            selectedRoute = route?.type,
            failureReason = if (route == null) "No route" else null,
        )

    override suspend fun selectBestRoute(
        profile: DeviceProfile,
        diagnostics: RouteDiagnostics,
    ): ConnectionRoute? = route
}
