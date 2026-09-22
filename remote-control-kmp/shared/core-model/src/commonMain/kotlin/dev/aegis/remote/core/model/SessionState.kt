package dev.aegis.remote.core.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface SessionState {
    @Serializable data object Idle : SessionState

    @Serializable data object ResolvingRoutes : SessionState

    @Serializable data object TestingLan : SessionState

    @Serializable data object TestingVpn : SessionState

    @Serializable data object TestingStun : SessionState

    @Serializable data object TestingTurn : SessionState

    @Serializable data object ConnectingRelay : SessionState

    @Serializable data object ResolvingHost : SessionState

    @Serializable data object ConnectingSsh : SessionState

    @Serializable data object VerifyingHostKey : SessionState

    @Serializable data object Authenticating : SessionState

    @Serializable data object TerminalReady : SessionState

    @Serializable data object StartingAgent : SessionState

    @Serializable data object Pairing : SessionState

    @Serializable data object RemotePairing : SessionState

    @Serializable data object NegotiatingWebRtc : SessionState

    @Serializable data object Streaming : SessionState

    @Serializable data object Reconnecting : SessionState

    @Serializable data object SwitchingRoute : SessionState

    @Serializable data object Disconnecting : SessionState

    @Serializable data class Failed(
        val error: AppError,
    ) : SessionState
}

@Serializable
sealed interface SessionEvent {
    @Serializable data object ConnectRequested : SessionEvent

    @Serializable data object RoutesResolved : SessionEvent

    @Serializable data object RouteUnavailable : SessionEvent

    @Serializable data object HostResolved : SessionEvent

    @Serializable data object SshConnected : SessionEvent

    @Serializable data object HostKeyVerified : SessionEvent

    @Serializable data object Authenticated : SessionEvent

    @Serializable data object TerminalStarted : SessionEvent

    @Serializable data object VisualRequested : SessionEvent

    @Serializable data object WebRtcNegotiated : SessionEvent

    @Serializable data object Disconnected : SessionEvent

    @Serializable data object DisconnectRequested : SessionEvent

    @Serializable data object RecoverableFailure : SessionEvent

    @Serializable data class FatalFailure(
        val error: AppError,
    ) : SessionEvent
}
