package dev.aegis.remote.android.webrtc

import dev.aegis.remote.core.model.HostAddress
import dev.aegis.remote.core.model.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidLocalProtocolClientTest {
    @Test
    fun localProtocolAlwaysUsesPinnedTlsWebSocketUrl() {
        assertEquals(
            "wss://192.168.1.92:48291/protocol/session-1?deviceId=device-1",
            localProtocolWebSocketUrl(
                host = HostAddress("192.168.1.92"),
                pairingPort = 48_291,
                sessionId = SessionId("session-1"),
                authorizedDeviceId = "device-1",
            ),
        )
    }

    @Test
    fun localProtocolUrlRejectsUntrustedPathAndQueryComponents() {
        assertFailsWith<IllegalArgumentException> {
            localProtocolWebSocketUrl(
                host = HostAddress("192.168.1.92"),
                pairingPort = 48_291,
                sessionId = SessionId("session/1"),
                authorizedDeviceId = "device 1",
            )
        }
    }
}
