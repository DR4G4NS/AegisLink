package dev.aegis.remote.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ConnectionRouteType {
    Lan,
    Vpn,
    StunDirect,
    TurnRelay,
    ReverseRelay,
    ManualSsh,
}

@Serializable
sealed interface ConnectionRoute {
    val type: ConnectionRouteType
    val priority: Int
    val description: String

    @Serializable
    data class LanRoute(
        val host: HostAddress,
    ) : ConnectionRoute {
        override val type = ConnectionRouteType.Lan
        override val priority = 10
        override val description = "LAN directa"
    }

    @Serializable
    data class VpnRoute(
        val host: HostAddress,
    ) : ConnectionRoute {
        override val type = ConnectionRouteType.Vpn
        override val priority = 20
        override val description = "VPN mesh o IP personalizada"
    }

    @Serializable
    data class StunDirectRoute(
        val relayDeviceId: RelayDeviceId,
    ) : ConnectionRoute {
        override val type = ConnectionRouteType.StunDirect
        override val priority = 30
        override val description = "Internet directo con ICE/STUN"
    }

    @Serializable
    data class TurnRelayRoute(
        val relayDeviceId: RelayDeviceId,
    ) : ConnectionRoute {
        override val type = ConnectionRouteType.TurnRelay
        override val priority = 40
        override val description = "TURN relay para WebRTC"
    }

    @Serializable
    data class ReverseRelayRoute(
        val relayDeviceId: RelayDeviceId,
    ) : ConnectionRoute {
        override val type = ConnectionRouteType.ReverseRelay
        override val priority = 50
        override val description = "Relay con conexion saliente desde la PC"
    }

    @Serializable
    data class ManualSshRoute(
        val host: HostAddress,
    ) : ConnectionRoute {
        override val type = ConnectionRouteType.ManualSsh
        override val priority = 60
        override val description = "SSH manual avanzado"
    }
}

@Serializable
enum class RouteEvidence {
    /** TCP, STUN binding, or TURN issuance. Not a working WebRTC path. */
    UnprovenHint,

    /** [IcePathValidator] selected a candidate pair of this route type. */
    SelectedIcePair,

    Unavailable,
}

@Serializable
data class RouteHealth(
    val routeType: ConnectionRouteType,
    val available: Boolean,
    val latencyMs: Long? = null,
    val reason: String? = null,
    val evidence: RouteEvidence = if (available) RouteEvidence.UnprovenHint else RouteEvidence.Unavailable,
    val hint: Boolean = evidence == RouteEvidence.UnprovenHint,
) {
    fun isProvenAvailable(): Boolean = available && evidence == RouteEvidence.SelectedIcePair

    fun isAttemptable(): Boolean = available || hint
}

@Serializable
data class RouteDiagnostics(
    val testedAtEpochMillis: Long,
    val health: List<RouteHealth>,
    val selectedRoute: ConnectionRouteType? = null,
    val failureReason: String? = null,
)

@Serializable
sealed interface NatTraversalState {
    @Serializable data object Unknown : NatTraversalState

    @Serializable data object Checking : NatTraversalState

    @Serializable data object DirectPossible : NatTraversalState

    @Serializable data object TurnRequired : NatTraversalState

    @Serializable data object Blocked : NatTraversalState

    @Serializable data class Failed(
        val reason: String,
    ) : NatTraversalState
}
