package dev.aegis.remote.android.webrtc

import dev.aegis.remote.core.input.RemoteInputEvent
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.ConnectionStats
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.MonitorInfo
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.protocol.ControlCommand
import dev.aegis.remote.protocol.ProtocolMessage
import dev.aegis.remote.protocol.ProtocolMessageJsonCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AndroidVisualProtocolBridgeTest {
    private val sessionId = SessionId("session-1")
    private val codec = ProtocolMessageJsonCodec()
    private val bridge = AndroidVisualProtocolBridge(codec)

    @Test
    fun decodesStatsForMatchingSession() {
        val stats =
            ConnectionStats(
                rttMs = 42,
                bitrateKbps = 2200,
                packetLossPercent = 0.5f,
                framesDropped = 2,
                encodeMs = 7f,
                decodeMs = 6f,
                fps = 30,
                resolution = "1920x1080",
                networkType = "wifi",
                routeType = ConnectionRouteType.Lan,
            )

        val event =
            bridge.decodeIncomingEvent(
                sessionId,
                codec.encode(ProtocolMessage.Stats(sessionId, stats)),
            )

        assertEquals(AndroidVisualProtocolEvent.StatsUpdated(stats), event)
    }

    @Test
    fun decodesMonitorSnapshotForMatchingSession() {
        val monitors =
            listOf(
                MonitorInfo(
                    id = MonitorId("primary"),
                    name = "Primary",
                    width = 1920,
                    height = 1080,
                    primary = true,
                ),
            )

        val event =
            bridge.decodeIncomingEvent(
                sessionId,
                codec.encode(ProtocolMessage.Monitors(sessionId, monitors)),
            )

        assertEquals(AndroidVisualProtocolEvent.MonitorsUpdated(monitors), event)
    }

    @Test
    fun decodesControlErrorForMatchingSession() {
        val event =
            bridge.decodeIncomingEvent(
                sessionId,
                codec.encode(
                    ProtocolMessage.Control(
                        sessionId = sessionId,
                        command = ControlCommand.Error("WEBRTC_FAILED", "Peer connection closed"),
                    ),
                ),
            )

        assertEquals(
            AndroidVisualProtocolEvent.ControlError("WEBRTC_FAILED", "Peer connection closed"),
            event,
        )
    }

    @Test
    fun ignoresOtherSessionsAndNonTelemetryMessages() {
        val otherSession = SessionId("session-2")

        val wrongSessionEvent =
            bridge.decodeIncomingEvent(
                sessionId,
                codec.encode(ProtocolMessage.Monitors(otherSession, emptyList())),
            )
        val inputEvent =
            bridge.decodeIncomingEvent(
                sessionId,
                codec.encode(ProtocolMessage.Input(sessionId, RemoteInputEvent.TextInput("ignored"))),
            )

        assertNull(wrongSessionEvent)
        assertNull(inputEvent)
    }

    @Test
    fun reportsMalformedPayload() {
        val event = bridge.decodeIncomingEvent(sessionId, "{")

        assertIs<AndroidVisualProtocolEvent.MalformedPayload>(event)
    }
}
