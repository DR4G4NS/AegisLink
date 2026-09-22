package dev.aegis.remote.core.session

import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.AegisLocalPorts
import dev.aegis.remote.core.model.ConnectionRoute
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.DeviceProfile
import dev.aegis.remote.core.model.RouteDiagnostics

data class VisualReconnectionDecisionContext(
    val profile: DeviceProfile,
    val failedRoute: ConnectionRoute,
    val diagnostics: RouteDiagnostics,
    val attempt: Int,
    val lastFailureEpochMillis: Long,
    val lastStableEpochMillis: Long? = null,
    val networkOnline: Boolean = true,
    val failureKind: ConnectionFailureKind = ConnectionFailureKind.Transient,
    val lastSelectedPairType: ConnectionRouteType? = null,
    val selectedPairProvesCurrentTypeImpossible: Boolean = false,
)

sealed interface VisualReconnectionDecision {
    val delayMillis: Long

    data class RetrySameRoute(
        val route: ConnectionRoute,
        override val delayMillis: Long,
    ) : VisualReconnectionDecision

    data class SwitchRoute(
        val route: ConnectionRoute,
        override val delayMillis: Long,
        val reason: String,
    ) : VisualReconnectionDecision

    data class WaitOffline(
        val reason: String,
    ) : VisualReconnectionDecision {
        override val delayMillis: Long = 0
    }

    data class GiveUp(
        val reason: String,
        val code: String? = null,
    ) : VisualReconnectionDecision {
        override val delayMillis: Long = 0
    }
}

class VisualReconnectionPolicy(
    private val reconnectionManager: ReconnectionManager =
        ExponentialBackoffReconnectionManager(
            baseDelayMillis = ConnectionSupervisor.DEFAULT_BASE_DELAY_MILLIS,
            maxDelayMillis = ConnectionSupervisor.DEFAULT_MAX_DELAY_MILLIS,
            maxAttempts = Int.MAX_VALUE,
            stableResetAfterMillis = ConnectionSupervisor.DEFAULT_STABLE_RESET_MILLIS,
        ),
) {
    suspend fun decide(context: VisualReconnectionDecisionContext): VisualReconnectionDecision {
        if (context.failureKind == ConnectionFailureKind.ProtocolSkew) {
            return VisualReconnectionDecision.GiveUp(
                reason = "Protocol capability handshake failed; retries are stopped",
                code = AegisFailureCodes.SESSION_PROTOCOL_SKEW,
            )
        }
        if (context.failureKind == ConnectionFailureKind.FatalAuth) {
            return VisualReconnectionDecision.GiveUp(
                reason = "Identity or authorization failed; retries are blocked until a wakeup",
                code = AegisFailureCodes.IDENTITY_AUTHENTICATION_FAILED,
            )
        }
        if (!context.networkOnline || context.failureKind == ConnectionFailureKind.Offline) {
            return VisualReconnectionDecision.WaitOffline("Network is offline; retry attempts are not consumed")
        }

        val attempt =
            reconnectionManager.effectiveAttempt(
                context.attempt,
                context.lastFailureEpochMillis,
                context.lastStableEpochMillis,
            )
        if (!reconnectionManager.shouldRetry(attempt, context.lastFailureEpochMillis)) {
            return VisualReconnectionDecision.GiveUp("Visual reconnection attempts exhausted")
        }

        val delay = reconnectionManager.nextDelayMillis(attempt)
        val candidateRoutes = context.profile.visualCandidateRoutes()
        val attemptable =
            candidateRoutes.filter { route -> context.diagnostics.isAttemptable(route.type) }.sortedBy { it.priority }

        if (attemptable.isEmpty()) {
            return VisualReconnectionDecision.GiveUp(
                context.diagnostics.failureReason ?: "No route is currently available for visual reconnection",
            )
        }

        val switchTarget = switchTarget(context, attemptable)
        return if (switchTarget != null) {
            VisualReconnectionDecision.SwitchRoute(
                route = switchTarget,
                delayMillis = delay,
                reason = "Switching from ${context.failedRoute.type.name} to ${switchTarget.type.name}",
            )
        } else {
            val same =
                attemptable.firstOrNull { it.type == context.failedRoute.type } ?: context.failedRoute
            VisualReconnectionDecision.RetrySameRoute(route = same, delayMillis = delay)
        }
    }

    private fun switchTarget(
        context: VisualReconnectionDecisionContext,
        attemptable: List<ConnectionRoute>,
    ): ConnectionRoute? {
        if (context.selectedPairProvesCurrentTypeImpossible) {
            val provenTypes =
                context.diagnostics.health
                    .filter { it.isProvenAvailable() }
                    .map { it.routeType }
                    .toSet()
            val selected = context.lastSelectedPairType
            return attemptable.firstOrNull { route ->
                route.type != context.failedRoute.type &&
                    (route.type == selected || route.type in provenTypes)
            } ?: attemptable.firstOrNull { it.type != context.failedRoute.type }
        }
        if (context.diagnostics.isAttemptable(context.failedRoute.type)) {
            return null
        }
        return attemptable.firstOrNull { it.type != context.failedRoute.type }
    }

    private fun RouteDiagnostics.isAttemptable(type: ConnectionRouteType): Boolean =
        health.any { item ->
            item.routeType == type && item.isAttemptable()
        }
}

fun DeviceProfile.visualCandidateRoutes(): List<ConnectionRoute> =
    buildList {
        add(ConnectionRoute.LanRoute(localHost.copy(port = localHost.port ?: AegisLocalPorts.VISUAL_PROTOCOL)))
        vpnHost?.let { add(ConnectionRoute.VpnRoute(it.copy(port = it.port ?: AegisLocalPorts.VISUAL_PROTOCOL))) }
        relayDeviceId?.let {
            add(ConnectionRoute.StunDirectRoute(it))
            add(ConnectionRoute.TurnRelayRoute(it))
            if (remoteAccessEnabled) add(ConnectionRoute.ReverseRelayRoute(it))
        }
    }
