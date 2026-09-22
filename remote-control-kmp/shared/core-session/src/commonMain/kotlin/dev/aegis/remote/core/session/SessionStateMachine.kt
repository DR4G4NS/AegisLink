package dev.aegis.remote.core.session

import dev.aegis.remote.core.model.SessionEvent
import dev.aegis.remote.core.model.SessionState

class SessionStateMachine {
    fun reduce(
        state: SessionState,
        event: SessionEvent,
    ): SessionState =
        when (state) {
            SessionState.Idle -> {
                when (event) {
                    SessionEvent.ConnectRequested -> SessionState.ResolvingRoutes
                    is SessionEvent.FatalFailure -> SessionState.Failed(event.error)
                    else -> state
                }
            }

            SessionState.ResolvingRoutes -> {
                when (event) {
                    SessionEvent.RoutesResolved -> SessionState.ResolvingHost
                    SessionEvent.RouteUnavailable -> SessionState.Failed(routeFailure())
                    is SessionEvent.FatalFailure -> SessionState.Failed(event.error)
                    else -> state
                }
            }

            SessionState.ResolvingHost -> {
                when (event) {
                    SessionEvent.HostResolved -> SessionState.ConnectingSsh
                    is SessionEvent.FatalFailure -> SessionState.Failed(event.error)
                    else -> state
                }
            }

            SessionState.ConnectingSsh -> {
                when (event) {
                    SessionEvent.SshConnected -> SessionState.VerifyingHostKey
                    SessionEvent.RecoverableFailure -> SessionState.Reconnecting
                    is SessionEvent.FatalFailure -> SessionState.Failed(event.error)
                    else -> state
                }
            }

            SessionState.VerifyingHostKey -> {
                when (event) {
                    SessionEvent.HostKeyVerified -> SessionState.Authenticating
                    is SessionEvent.FatalFailure -> SessionState.Failed(event.error)
                    else -> state
                }
            }

            SessionState.Authenticating -> {
                when (event) {
                    SessionEvent.Authenticated -> SessionState.TerminalReady
                    is SessionEvent.FatalFailure -> SessionState.Failed(event.error)
                    else -> state
                }
            }

            SessionState.TerminalReady -> {
                when (event) {
                    SessionEvent.VisualRequested -> SessionState.NegotiatingWebRtc
                    SessionEvent.DisconnectRequested -> SessionState.Disconnecting
                    SessionEvent.RecoverableFailure -> SessionState.Reconnecting
                    is SessionEvent.FatalFailure -> SessionState.Failed(event.error)
                    else -> state
                }
            }

            SessionState.NegotiatingWebRtc -> {
                when (event) {
                    SessionEvent.WebRtcNegotiated -> SessionState.Streaming
                    SessionEvent.RecoverableFailure -> SessionState.Reconnecting
                    is SessionEvent.FatalFailure -> SessionState.Failed(event.error)
                    else -> state
                }
            }

            SessionState.Streaming -> {
                when (event) {
                    SessionEvent.DisconnectRequested -> SessionState.Disconnecting
                    SessionEvent.RecoverableFailure -> SessionState.Reconnecting
                    is SessionEvent.FatalFailure -> SessionState.Failed(event.error)
                    else -> state
                }
            }

            SessionState.Reconnecting -> {
                when (event) {
                    SessionEvent.ConnectRequested -> SessionState.ResolvingRoutes
                    SessionEvent.DisconnectRequested -> SessionState.Disconnecting
                    is SessionEvent.FatalFailure -> SessionState.Failed(event.error)
                    else -> state
                }
            }

            SessionState.Disconnecting -> {
                when (event) {
                    SessionEvent.Disconnected -> SessionState.Idle
                    else -> state
                }
            }

            SessionState.TestingLan,
            SessionState.TestingVpn,
            SessionState.TestingStun,
            SessionState.TestingTurn,
            SessionState.ConnectingRelay,
            SessionState.StartingAgent,
            SessionState.Pairing,
            SessionState.RemotePairing,
            SessionState.SwitchingRoute,
            -> {
                when (event) {
                    SessionEvent.DisconnectRequested -> SessionState.Disconnecting
                    SessionEvent.RecoverableFailure -> SessionState.Reconnecting
                    is SessionEvent.FatalFailure -> SessionState.Failed(event.error)
                    else -> state
                }
            }

            is SessionState.Failed -> {
                when (event) {
                    SessionEvent.ConnectRequested -> SessionState.ResolvingRoutes
                    SessionEvent.DisconnectRequested -> SessionState.Disconnecting
                    else -> state
                }
            }
        }

    private fun routeFailure() =
        dev.aegis.remote.core.model.AppError
            .Network("No route available")
}
