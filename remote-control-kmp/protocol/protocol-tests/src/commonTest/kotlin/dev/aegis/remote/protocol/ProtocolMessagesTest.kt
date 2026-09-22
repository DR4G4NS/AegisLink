package dev.aegis.remote.protocol

import dev.aegis.remote.core.model.AegisFailure
import dev.aegis.remote.core.model.AegisFailureCodes
import dev.aegis.remote.core.model.ConnectionRouteType
import dev.aegis.remote.core.model.FailureCategory
import dev.aegis.remote.core.model.MonitorId
import dev.aegis.remote.core.model.SessionId
import dev.aegis.remote.core.model.StunTurnConfig
import dev.aegis.remote.core.model.VideoConfig
import dev.aegis.remote.core.webrtc.SignalingMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ProtocolMessagesTest {
    private val json =
        Json {
            classDiscriminator = "kind"
            ignoreUnknownKeys = true
        }

    @Test
    fun roundTripsSignalingOffer() {
        val original: ProtocolMessage =
            ProtocolMessage.Signaling(
                sessionId = SessionId("session-1"),
                message =
                    SignalingMessage.Offer(
                        sessionId = SessionId("session-1"),
                        sdp = "v=0\r\n",
                    ),
            )

        val decoded = json.decodeFromString<ProtocolMessage>(json.encodeToString(original))

        val message = assertIs<ProtocolMessage.Signaling>(decoded)
        val offer = assertIs<SignalingMessage.Offer>(message.message)
        assertEquals("session-1", message.sessionId.value)
        assertEquals("v=0\r\n", offer.sdp)
    }

    @Test
    fun roundTripsStartVisualControlCommand() {
        val config =
            VideoConfig(
                width = 1280,
                height = 720,
                fps = 30,
                bitrateKbps = 2500,
                monitorId = MonitorId("primary"),
                routeType = ConnectionRouteType.ReverseRelay,
            )
        val original: ProtocolMessage =
            ProtocolMessage.Control(
                sessionId = SessionId("session-1"),
                command =
                    ControlCommand.StartVisualSession(
                        videoConfig = config,
                        iceConfig = StunTurnConfig(stunUrls = listOf("stun:stun.example.test:3478")),
                    ),
            )

        val decoded = json.decodeFromString<ProtocolMessage>(json.encodeToString(original))

        val message = assertIs<ProtocolMessage.Control>(decoded)
        val command = assertIs<ControlCommand.StartVisualSession>(message.command)
        assertEquals(1280, command.videoConfig.width)
        assertEquals(ConnectionRouteType.ReverseRelay, command.videoConfig.routeType)
        assertEquals(listOf("stun:stun.example.test:3478"), command.iceConfig.stunUrls)
    }

    @Test
    fun roundTripsPingPongControlCommands() {
        val ping: ProtocolMessage =
            ProtocolMessage.Control(
                sessionId = SessionId("session-1"),
                command = ControlCommand.Ping(sentAtEpochMillis = 1000),
            )
        val pong: ProtocolMessage =
            ProtocolMessage.Control(
                sessionId = SessionId("session-1"),
                command =
                    ControlCommand.Pong(
                        pingSentAtEpochMillis = 1000,
                        receivedAtEpochMillis = 1200,
                    ),
            )

        val decodedPing = json.decodeFromString<ProtocolMessage>(json.encodeToString(ping))
        val decodedPong = json.decodeFromString<ProtocolMessage>(json.encodeToString(pong))

        assertEquals(1000, assertIs<ControlCommand.Ping>(assertIs<ProtocolMessage.Control>(decodedPing).command).sentAtEpochMillis)
        assertEquals(1200, assertIs<ControlCommand.Pong>(assertIs<ProtocolMessage.Control>(decodedPong).command).receivedAtEpochMillis)
    }

    @Test
    fun roundTripsSessionCapabilities() {
        val original: ProtocolMessage =
            ProtocolMessage.Control(
                sessionId = SessionId("session-1"),
                command =
                    ControlCommand.SessionCapabilities(
                        protocolRev = 1,
                        features = listOf("iceRestart", "ping", "turnRefresh"),
                    ),
            )
        val decoded = json.decodeFromString<ProtocolMessage>(json.encodeToString(original))
        val command = assertIs<ControlCommand.SessionCapabilities>(assertIs<ProtocolMessage.Control>(decoded).command)
        assertEquals(1, command.protocolRev)
        assertEquals(listOf("iceRestart", "ping", "turnRefresh"), command.features)
    }

    @Test
    fun roundTripsCausalProtocolErrorWhileLegacyErrorsRemainValid() {
        val failure =
            AegisFailure(
                code = AegisFailureCodes.SESSION_PROTOCOL_PROCESSING_FAILED,
                component = "desktop-protocol-bridge",
                operation = "process-protocol-message",
                stage = "dispatch",
                category = FailureCategory.CAUSE_UNCONFIRMED,
                summary = "The desktop agent could not process this request.",
                technicalCause = "An unexpected processor exception escaped.",
                expected = "A handling result",
                actual = "An exception",
                retryable = false,
                correlationId = "session-1",
                nextAction = "Inspect the correlated trace.",
                underlyingType = "java.lang.IllegalStateException",
            )
        val original: ProtocolMessage =
            ProtocolMessage.Control(
                sessionId = SessionId("session-1"),
                command = ControlCommand.Error(failure.code, failure.summary, failure),
            )

        val decoded = json.decodeFromString<ProtocolMessage>(json.encodeToString(original))
        val decodedError = assertIs<ControlCommand.Error>(assertIs<ProtocolMessage.Control>(decoded).command)
        val legacy: ProtocolMessage =
            ProtocolMessage.Control(
                sessionId = SessionId("session-2"),
                command = ControlCommand.Error("legacy-error", "Legacy message"),
            )
        val decodedLegacy = json.decodeFromString<ProtocolMessage>(json.encodeToString(legacy))
        val decodedLegacyError = assertIs<ControlCommand.Error>(assertIs<ProtocolMessage.Control>(decodedLegacy).command)

        assertEquals(failure, decodedError.failure)
        assertEquals("legacy-error", decodedLegacyError.code)
        assertNull(decodedLegacyError.failure)
    }
}
