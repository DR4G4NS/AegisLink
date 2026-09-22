package dev.aegis.remote.core.session

import dev.aegis.remote.core.model.AuthMethod
import dev.aegis.remote.core.model.ConnectionRoute
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.DevicePermissions
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.DeviceProfileId
import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.QualityMode
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.model.RouteDiagnostics
import dev.aegis.remote.core.model.RouteHealth
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.TurnConfig
import dev.aegis.remote.core.model.WebRtcIceTransportPolicy
import dev.aegis.remote.core.nat.TurnConfigProvider
import dev.aegis.remote.core.routing.ConnectionRouteManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PrepareVisualSessionUseCaseTest {
    @Test
    fun customInternetAddressUsesDirectSignalingAndStun() =
        runTest {
            val route = ConnectionRoute.VpnRoute(HostAddress("home.example.com", 48291))
            val plan =
                PrepareVisualSessionUseCase(
                    routeManager = FakeRouteManager(route),
                    stunUrls = listOf("stun:stun.example.com:3478"),
                    clock = { 1_000 },
                )(profile())
            assertEquals(route, plan.route)
            assertEquals(listOf("stun:stun.example.com:3478"), plan.ice.stunUrls)
        }

    @Test
    fun rejectsProfilesWithoutVisualPermission() =
        runTest {
            val useCase =
                PrepareVisualSessionUseCase(
                    routeManager = FakeRouteManager(ConnectionRoute.LanRoute(HostAddress("192.168.1.10"))),
                    clock = { 1_000 },
                )

            val error =
                assertFailsWith<PrepareVisualSessionException> {
                    useCase(profile(permissions = DevicePermissions(visual = false)))
                }

            assertEquals("Visual remote access is not permitted for this device", error.appError.message)
        }

    @Test
    fun preparesLanPlanWithoutIceServers() =
        runTest {
            val route = ConnectionRoute.LanRoute(HostAddress("192.168.1.10", 22))
            val useCase =
                PrepareVisualSessionUseCase(
                    routeManager = FakeRouteManager(route),
                    clock = { 1_000 },
                )

            val plan = useCase(profile(defaultMonitorId = MonitorId("display-1")))

            assertEquals(route, plan.route)
            assertEquals(ConnectionRouteType.Lan, plan.videoConfig.routeType)
            assertEquals(MonitorId("display-1"), plan.videoConfig.monitorId)
            assertEquals(StunTurnConfig(), plan.ice)
        }

    @Test
    fun preparesStunDirectPlanWithConfiguredStunServers() =
        runTest {
            val route = ConnectionRoute.StunDirectRoute(RelayDeviceId("pc-1"))
            val useCase =
                PrepareVisualSessionUseCase(
                    routeManager = FakeRouteManager(route),
                    stunUrls = listOf("stun:stun.example.test:3478"),
                    clock = { 1_000 },
                )

            val plan = useCase(profile())

            assertEquals(route, plan.route)
            assertEquals(ConnectionRouteType.StunDirect, plan.videoConfig.routeType)
            assertEquals(listOf("stun:stun.example.test:3478"), plan.ice.stunUrls)
        }

    @Test
    fun preparesTurnPlanWithSecureTurnProvider() =
        runTest {
            val route = ConnectionRoute.TurnRelayRoute(RelayDeviceId("pc-1"))
            val turnConfig =
                StunTurnConfig(
                    turnConfig =
                        TurnConfig(
                            urls = listOf("turn:turn.example.test:3478"),
                            username = "1600:android",
                            credentialRef = "android-keystore:turn-ref",
                            expiresAtEpochMillis = 2_000,
                        ),
                )
            val useCase =
                PrepareVisualSessionUseCase(
                    routeManager = FakeRouteManager(route),
                    turnConfigProvider = StaticTurnConfigProvider(turnConfig),
                    clock = { 1_000 },
                )

            val plan = useCase(profile(qualityMode = QualityMode.QualityFirst))

            assertEquals(route, plan.route)
            assertEquals(turnConfig.copy(iceTransportPolicy = WebRtcIceTransportPolicy.RelayOnly), plan.ice)
            assertEquals(ConnectionRouteType.TurnRelay, plan.videoConfig.routeType)
            assertEquals(QualityMode.RelaySaver, plan.videoConfig.qualityMode)
        }

    @Test
    fun failsTurnPlanWhenCredentialsAreExpired() =
        runTest {
            val useCase =
                PrepareVisualSessionUseCase(
                    routeManager = FakeRouteManager(ConnectionRoute.TurnRelayRoute(RelayDeviceId("pc-1"))),
                    turnConfigProvider =
                        StaticTurnConfigProvider(
                            StunTurnConfig(
                                turnConfig =
                                    TurnConfig(
                                        urls = listOf("turn:turn.example.test:3478"),
                                        credentialRef = "android-keystore:turn-ref",
                                        expiresAtEpochMillis = 999,
                                    ),
                            ),
                        ),
                    clock = { 1_000 },
                )

            val error =
                assertFailsWith<PrepareVisualSessionException> {
                    useCase(profile())
                }

            assertIs<dev.aegis.remote.core.model.AppError.Network>(error.appError)
            assertEquals("TURN credentials are expired", error.appError.message)
        }

    private fun profile(
        permissions: DevicePermissions = DevicePermissions(visual = true, input = true),
        defaultMonitorId: MonitorId? = null,
        qualityMode: QualityMode = QualityMode.Balanced,
    ) = DeviceProfile(
        id = DeviceProfileId("profile-1"),
        displayName = "PC",
        localHost = HostAddress("192.168.1.10"),
        sshPort = 22,
        username = "ian",
        authMethod = AuthMethod.Password,
        permissions = permissions,
        defaultMonitorId = defaultMonitorId,
        qualityPreference = qualityMode,
    )
}

private class FakeRouteManager(
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

private class StaticTurnConfigProvider(
    private val config: StunTurnConfig?,
) : TurnConfigProvider {
    override suspend fun getTurnConfig(): StunTurnConfig? = config
}
