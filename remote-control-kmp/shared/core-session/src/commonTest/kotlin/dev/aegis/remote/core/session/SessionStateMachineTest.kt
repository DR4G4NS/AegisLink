package dev.aegis.remote.core.session

import dev.aegis.remote.core.model.AppError
import dev.aegis.remote.core.model.SessionEvent
import dev.aegis.remote.core.model.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SessionStateMachineTest {
    private val machine = SessionStateMachine()

    @Test
    fun reachesTerminalReadyThroughSshFlow() {
        val final =
            listOf(
                SessionEvent.ConnectRequested,
                SessionEvent.RoutesResolved,
                SessionEvent.HostResolved,
                SessionEvent.SshConnected,
                SessionEvent.HostKeyVerified,
                SessionEvent.Authenticated,
            ).fold(SessionState.Idle as SessionState, machine::reduce)

        assertEquals(SessionState.TerminalReady, final)
    }

    @Test
    fun recoverableStreamingFailureMovesToReconnecting() {
        val next = machine.reduce(SessionState.Streaming, SessionEvent.RecoverableFailure)
        assertEquals(SessionState.Reconnecting, next)
    }

    @Test
    fun fatalFailureKeepsTypedError() {
        val error = AppError.Authentication("bad credentials")
        val next = machine.reduce(SessionState.ConnectingSsh, SessionEvent.FatalFailure(error))
        val failed = assertIs<SessionState.Failed>(next)
        assertEquals(error, failed.error)
    }
}
