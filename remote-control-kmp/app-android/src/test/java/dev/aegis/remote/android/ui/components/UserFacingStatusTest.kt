package dev.aegis.remote.android.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class UserFacingStatusTest {
    @Test
    fun mapsIceAndPingJargonToRecovering() {
        assertEquals(UserFacingStatusKind.Recovering, userFacingStatusKind("ICE Failed but Ping is alive"))
        assertEquals(UserFacingStatusKind.Recovering, userFacingStatusKind("ICE restart in flight; peer is not rebuilt"))
        assertEquals(UserFacingStatusKind.Recovering, userFacingStatusKind("The connection is recovering"))
    }

    @Test
    fun mapsProtocolHandshakeToMismatch() {
        assertEquals(UserFacingStatusKind.ProtocolMismatch, userFacingStatusKind("capability handshake failed"))
        assertEquals(UserFacingStatusKind.ProtocolMismatch, userFacingStatusKind("SES-1014 protocol skew"))
    }

    @Test
    fun mapsReconnectAndStreamingWithoutLeakingTransportNames() {
        assertEquals(UserFacingStatusKind.Reconnecting, userFacingStatusKind("Ping timed out; reconnecting"))
        assertEquals(UserFacingStatusKind.Connected, userFacingStatusKind("Streaming"))
        assertEquals(UserFacingStatusKind.Connected, userFacingStatusKind("WebRTC streaming is active"))
        assertEquals(UserFacingStatusKind.Preparing, userFacingStatusKind("Negotiating"))
        assertEquals(UserFacingStatusKind.Idle, userFacingStatusKind("Idle"))
        assertEquals(UserFacingStatusKind.Loading, userFacingStatusKind("Verifying pinned SSH, shell and SFTP..."))
        assertEquals(null, userFacingStatusKind("The power-on command was sent, but the computer has not answered yet."))
        assertEquals(null, userFacingStatusKind("Se envió la orden de encendido, pero el equipo aún no responde."))
    }
}
