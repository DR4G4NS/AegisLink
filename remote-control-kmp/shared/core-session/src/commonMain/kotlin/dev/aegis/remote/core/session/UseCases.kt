package dev.aegis.remote.core.session

import dev.aegis.remote.core.model.AppError
import dev.aegis.remote.core.model.ConnectionRoute
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.ConnectionStats
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.model.RouteDiagnostics
import dev.aegis.remote.core.model.SessionEvent
import dev.aegis.remote.core.model.SessionState
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.TurnConfig
import dev.aegis.remote.core.model.VideoConfig
import dev.aegis.remote.core.model.WebRtcIceTransportPolicy
import dev.aegis.remote.core.nat.TurnConfigProvider
import dev.aegis.remote.core.quality.AdaptiveQualityController
import dev.aegis.remote.core.routing.ConnectionRouteManager
import dev.aegis.remote.core.terminal.SshTerminalClient
import dev.aegis.remote.core.terminal.TerminalSession
import dev.aegis.remote.core.webrtc.RemoteVideoSession
import dev.aegis.remote.core.webrtc.SignalingClient
import dev.aegis.remote.core.webrtc.VideoSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DetectAvailableRoutesUseCase(
    private val routeManager: ConnectionRouteManager,
) {
    suspend operator fun invoke(profile: DeviceProfile): RouteDiagnostics = routeManager.detectAvailableRoutes(profile)
}

class SelectBestRouteUseCase(
    private val routeManager: ConnectionRouteManager,
) {
    suspend operator fun invoke(
        profile: DeviceProfile,
        diagnostics: RouteDiagnostics,
    ) = routeManager.selectBestRoute(profile, diagnostics)
}

class StartTerminalUseCase(
    private val sshTerminalClient: SshTerminalClient,
) {
    suspend operator fun invoke(
        profile: DeviceProfile,
        routeHost: String,
    ): TerminalSession = sshTerminalClient.connect(profile, routeHost)
}

data class SshRoutePlan(
    val route: ConnectionRoute,
    val host: HostAddress,
)

class ResolveSshRouteException(
    val appError: AppError,
) : RuntimeException(appError.message)

class ResolveSshRouteUseCase(
    private val routeManager: ConnectionRouteManager,
) {
    suspend operator fun invoke(
        profile: DeviceProfile,
        diagnostics: RouteDiagnostics? = null,
    ): SshRoutePlan {
        if (!profile.permissions.terminal && !profile.permissions.sftp) {
            throw ResolveSshRouteException(AppError.Authorization("SSH access is not permitted for this device"))
        }

        // Route diagnostics are shared with the visual protocol, whose LAN port
        // can differ from OpenSSH. Probe the exact TCP endpoints that this use
        // case will subsequently connect to so an available SSH server is not
        // rejected merely because the visual protocol is offline (or vice versa).
        val sshProbeProfile =
            profile.copy(
                localHost = profile.localHost.copy(port = profile.sshPort),
                vpnHost = profile.vpnHost?.let { it.copy(port = profile.remoteSshPort ?: profile.sshPort) },
            )
        val resolvedDiagnostics = diagnostics ?: routeManager.detectAvailableRoutes(sshProbeProfile)
        val availableTypes =
            resolvedDiagnostics.health
                .filter { it.available }
                .map { it.routeType }
                .toSet()
        val vpnHost = profile.vpnHost

        val route =
            when {
                ConnectionRouteType.Lan in availableTypes -> {
                    ConnectionRoute.LanRoute(profile.localHost.copy(port = profile.sshPort))
                }

                ConnectionRouteType.Vpn in availableTypes && vpnHost != null -> {
                    ConnectionRoute.VpnRoute(vpnHost.copy(port = profile.remoteSshPort ?: profile.sshPort))
                }

                else -> {
                    routeManager
                        .selectBestRoute(profile, resolvedDiagnostics)
                        ?.takeIf { it.type in sshRouteTypes }
                        ?.withDefaultSshPort(profile)
                }
            } ?: throw ResolveSshRouteException(AppError.Network(resolvedDiagnostics.sshFailureMessage()))

        return SshRoutePlan(route = route, host = route.host())
    }

    private fun RouteDiagnostics.sshFailureMessage(): String {
        val availableTypes = health.filter { it.available }.map { it.routeType }.toSet()
        return when {
            availableTypes.any { it in remoteOnlyRouteTypes } -> {
                "SSH requires a LAN or VPN route; available remote routes are reserved for WebRTC signaling"
            }

            failureReason != null -> {
                failureReason ?: "No LAN or VPN route is currently available for SSH"
            }

            else -> {
                "No LAN or VPN route is currently available for SSH"
            }
        }
    }

    private fun ConnectionRoute.withDefaultSshPort(profile: DeviceProfile): ConnectionRoute =
        when (this) {
            is ConnectionRoute.LanRoute -> copy(host = host.copy(port = host.port ?: profile.sshPort))

            is ConnectionRoute.VpnRoute -> copy(host = host.copy(port = profile.remoteSshPort ?: profile.sshPort))

            is ConnectionRoute.ManualSshRoute -> copy(host = host.copy(port = host.port ?: profile.sshPort))

            is ConnectionRoute.StunDirectRoute,
            is ConnectionRoute.TurnRelayRoute,
            is ConnectionRoute.ReverseRelayRoute,
            -> this
        }

    private fun ConnectionRoute.host(): HostAddress =
        when (this) {
            is ConnectionRoute.LanRoute -> host

            is ConnectionRoute.VpnRoute -> host

            is ConnectionRoute.ManualSshRoute -> host

            is ConnectionRoute.StunDirectRoute,
            is ConnectionRoute.TurnRelayRoute,
            is ConnectionRoute.ReverseRelayRoute,
            -> error("Route ${type.name} does not expose an SSH host")
        }

    private companion object {
        val sshRouteTypes = setOf(ConnectionRouteType.Lan, ConnectionRouteType.Vpn, ConnectionRouteType.ManualSsh)
        val remoteOnlyRouteTypes =
            setOf(
                ConnectionRouteType.StunDirect,
                ConnectionRouteType.TurnRelay,
                ConnectionRouteType.ReverseRelay,
            )
    }
}

data class VisualSessionPlan(
    val route: ConnectionRoute,
    val videoConfig: VideoConfig,
    val ice: StunTurnConfig,
)

class PrepareVisualSessionException(
    val appError: AppError,
) : RuntimeException(appError.message)

class PrepareVisualSessionUseCase(
    private val routeManager: ConnectionRouteManager,
    private val qualityController: AdaptiveQualityController = AdaptiveQualityController(),
    private val turnConfigProvider: TurnConfigProvider? = null,
    private val stunUrls: List<String> = emptyList(),
    private val clock: () -> Long,
) {
    suspend operator fun invoke(
        profile: DeviceProfile,
        diagnostics: RouteDiagnostics? = null,
        stats: ConnectionStats? = null,
    ): VisualSessionPlan {
        if (!profile.permissions.visual) {
            throw PrepareVisualSessionException(AppError.Authorization("Visual remote access is not permitted for this device"))
        }

        val resolvedDiagnostics = diagnostics ?: routeManager.detectAvailableRoutes(profile)
        val route =
            routeManager.selectBestRoute(profile, resolvedDiagnostics)
                ?: throw PrepareVisualSessionException(
                    AppError.Network(resolvedDiagnostics.failureReason ?: "No route available for visual session"),
                )

        val ice = route.toIceConfig()
        val baselineStats = stats ?: defaultStats(route.type)
        return VisualSessionPlan(
            route = route,
            videoConfig = qualityController.choose(profile.qualityPreference, baselineStats, profile.defaultMonitorId),
            ice = ice,
        )
    }

    private suspend fun ConnectionRoute.toIceConfig(): StunTurnConfig =
        when (this) {
            is ConnectionRoute.LanRoute,
            is ConnectionRoute.ManualSshRoute,
            -> {
                StunTurnConfig()
            }

            // Custom Internet addresses use authenticated direct signaling and discover the video path.
            is ConnectionRoute.VpnRoute -> {
                StunTurnConfig(stunUrls = stunUrls)
            }

            is ConnectionRoute.StunDirectRoute -> {
                if (stunUrls.isEmpty()) {
                    throw PrepareVisualSessionException(AppError.Network("STUN route selected but no STUN servers are configured"))
                }
                StunTurnConfig(stunUrls = stunUrls)
            }

            is ConnectionRoute.TurnRelayRoute -> {
                val config =
                    turnConfigProvider?.getTurnConfig()
                        ?: throw PrepareVisualSessionException(AppError.Network("TURN route selected but no TURN config provider is available"))
                config.requireUsableTurn().copy(iceTransportPolicy = WebRtcIceTransportPolicy.RelayOnly)
            }

            is ConnectionRoute.ReverseRelayRoute -> {
                val turnConfig = turnConfigProvider?.getTurnConfig()
                StunTurnConfig(stunUrls = stunUrls, turnConfig = turnConfig?.turnConfig?.takeIf { !it.isExpired() })
            }
        }

    private fun StunTurnConfig.requireUsableTurn(): StunTurnConfig {
        val turn =
            turnConfig
                ?: throw PrepareVisualSessionException(AppError.Network("TURN route selected but the provider returned no TURN config"))
        if (turn.urls.isEmpty()) {
            throw PrepareVisualSessionException(AppError.Network("TURN route selected but the provider returned no TURN URLs"))
        }
        if (turn.isExpired()) {
            throw PrepareVisualSessionException(AppError.Network("TURN credentials are expired"))
        }
        return this
    }

    private fun TurnConfig.isExpired(): Boolean {
        val expiresAt = expiresAtEpochMillis ?: return false
        return expiresAt <= clock()
    }

    private fun defaultStats(routeType: ConnectionRouteType) =
        ConnectionStats(
            routeType = routeType,
        )
}

class StartVisualSessionUseCase(
    private val stateMachine: SessionStateMachine = SessionStateMachine(),
) {
    operator fun invoke(
        session: RemoteVideoSession,
        signalingClient: SignalingClient,
        config: VideoConfig,
        ice: StunTurnConfig,
        initialState: SessionState = SessionState.TerminalReady,
    ): Flow<SessionState> =
        flow {
            var currentState = stateMachine.reduce(initialState, SessionEvent.VisualRequested)
            emit(currentState)

            try {
                signalingClient.connect()
                session.start(config, ice)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                emitFailure(currentState, error)
                return@flow
            }

            session.states.collect { videoState ->
                val event = videoState.toSessionEvent() ?: return@collect
                val nextState = stateMachine.reduce(currentState, event)
                if (nextState != currentState) {
                    currentState = nextState
                    emit(currentState)
                }
            }
        }

    suspend operator fun invoke(session: RemoteVideoSession): RemoteVideoSession = session

    private suspend fun kotlinx.coroutines.flow.FlowCollector<SessionState>.emitFailure(
        currentState: SessionState,
        error: Throwable,
    ) {
        emit(
            stateMachine.reduce(
                currentState,
                SessionEvent.FatalFailure(AppError.Network(error.message ?: "Unable to start visual WebRTC session")),
            ),
        )
    }

    private fun VideoSessionState.toSessionEvent(): SessionEvent? =
        when (this) {
            VideoSessionState.Streaming -> SessionEvent.WebRtcNegotiated

            VideoSessionState.Reconnecting -> SessionEvent.RecoverableFailure

            is VideoSessionState.Failed -> SessionEvent.FatalFailure(AppError.Network(reason))

            VideoSessionState.Idle,
            VideoSessionState.Negotiating,
            -> null
        }
}

class StopVisualSessionUseCase {
    suspend operator fun invoke(
        session: RemoteVideoSession,
        signalingClient: SignalingClient,
    ) {
        session.stop()
        signalingClient.close()
    }
}

interface ReconnectionManager {
    suspend fun shouldRetry(
        attempt: Int,
        lastFailureEpochMillis: Long,
    ): Boolean

    fun nextDelayMillis(attempt: Int): Long

    fun effectiveAttempt(
        attempt: Int,
        lastFailureEpochMillis: Long,
        lastStableEpochMillis: Long?,
    ): Int = attempt
}

class ExponentialBackoffReconnectionManager(
    private val baseDelayMillis: Long = 500,
    private val maxDelayMillis: Long = 30_000,
    private val maxAttempts: Int = 8,
    private val stableResetAfterMillis: Long = ConnectionSupervisor.DEFAULT_STABLE_RESET_MILLIS,
) : ReconnectionManager {
    override suspend fun shouldRetry(
        attempt: Int,
        lastFailureEpochMillis: Long,
    ): Boolean = attempt < maxAttempts

    override fun nextDelayMillis(attempt: Int): Long {
        val multiplier = 1L shl attempt.coerceIn(0, 16)
        return (baseDelayMillis * multiplier).coerceAtMost(maxDelayMillis)
    }

    override fun effectiveAttempt(
        attempt: Int,
        lastFailureEpochMillis: Long,
        lastStableEpochMillis: Long?,
    ): Int {
        if (lastStableEpochMillis != null &&
            lastFailureEpochMillis - lastStableEpochMillis >= stableResetAfterMillis
        ) {
            return 0
        }
        return attempt
    }
}
